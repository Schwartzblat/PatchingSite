"""Email verification: the users.email_verified_at migration, the email_verify
token, /auth/verify-email, /auth/resend-verification, and the rules that hang
off it (checkout, forgot-password, reset-password, /users/me).

Mail is always a recorder here; nothing reaches an SMTP server.
"""
import argparse
import sqlite3
from datetime import datetime, timedelta, timezone

import jwt
import pytest
from starlette.exceptions import HTTPException

import config
import database
from utils import jwt_utils, passwords

PASSWORD = "correct horse battery"


def register(username, email=None, password=PASSWORD):
    assert database.register_user(database.RegisterRequest(
        username=username, email=email or f"{username}@example.com", password=password))


def _columns(path):
    return {row[1] for row in sqlite3.connect(path).execute("PRAGMA table_info(users)")}


def _verified_at(path, username):
    conn = sqlite3.connect(path)
    row = conn.execute("SELECT email_verified_at FROM users WHERE username = ?", (username,)).fetchone()
    conn.close()
    return row[0]


# --- migration: grandfathering ----------------------------------------------------

def _live_schema_db(path, usernames=("first", "second")):
    """The users table exactly as it stands on the live host before this change,
    with rows in it: every column up to devices_reset_at, no email_verified_at."""
    conn = sqlite3.connect(path)
    conn.execute("""CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL, email TEXT NOT NULL,
                        password_hash TEXT NOT NULL, password_salt TEXT NOT NULL,
                        password_scheme TEXT NOT NULL DEFAULT 'legacy',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        customer_id TEXT DEFAULT NULL,
                        device_fingerprints TEXT DEFAULT "[]",
                        devices_reset_at TEXT DEFAULT NULL)""")
    for username in usernames:
        conn.execute("INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, 'x', 'y')",
                     (username, f"{username}@example.com"))
    conn.commit()
    conn.close()


@pytest.fixture
def live_db(monkeypatch, tmp_path):
    db_path = tmp_path / "live.db"
    _live_schema_db(db_path)
    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path)
    return db_path


def test_the_live_upgrade_grandfathers_existing_accounts_and_only_them(live_db):
    """What the deploy does: the new code starts against the live file, and the
    service's startup runs the migration. Then life goes on."""
    from starlette.testclient import TestClient

    import main
    import manage

    before = datetime.now(timezone.utc).replace(microsecond=0)

    with TestClient(main.app) as service:  # the deploy's restart
        assert "email_verified_at" in _columns(live_db)
        for username in ("first", "second"):
            stamped = database.get_user(username).email_verified_at
            assert stamped is not None, f"{username} existed before the deploy and must count as verified"
            assert stamped >= before

        # Someone signs up after the deploy.
        assert service.post("/api/register", json={
            "username": "newcomer", "email": "newcomer@example.com", "password": PASSWORD}).status_code == 200
        assert database.get_user("newcomer").email_verified_at is None

    # The next deploy restarts the service, and the owner runs migrate by hand.
    with TestClient(main.app):
        pass
    manage.cmd_migrate(argparse.Namespace(dry_run=False))

    assert database.get_user("newcomer").email_verified_at is None, \
        "a re-run of the migration verified an account that never proved its inbox"
    assert database.get_user("first").email_verified_at is not None


def test_a_fresh_database_verifies_nobody(tmp_db):
    """No rows exist when the column is created, so there is nobody to grandfather."""
    assert "email_verified_at" in _columns(tmp_db)
    register("alice")
    database.init()
    assert database.get_user("alice").email_verified_at is None
    assert _verified_at(tmp_db, "alice") is None


def test_the_grandfathering_stamp_is_the_sites_timestamp_format(live_db):
    database.init()
    raw = _verified_at(live_db, "first")
    assert datetime.strptime(raw, database.TIMESTAMP_FORMAT)


class _FailingUpdate:
    """A connection that fails the grandfathering UPDATE, as a crash mid-migration would."""

    def __init__(self, conn):
        self._conn = conn

    def execute(self, sql, *args):
        if sql.lstrip().upper().startswith("UPDATE USERS SET EMAIL_VERIFIED_AT"):
            raise sqlite3.OperationalError("disk I/O error")
        return self._conn.execute(sql, *args)

    def __getattr__(self, name):
        return getattr(self._conn, name)


def test_a_failed_grandfathering_leaves_no_column_behind(live_db, monkeypatch):
    """The column and the stamp are one transaction. Were the column to survive
    a failed UPDATE, the next start would see it present, skip the step, and
    every existing customer would be locked out of subscribing and of password
    reset for good."""
    real_connect = database._connect
    monkeypatch.setattr(database, "_connect", lambda: _FailingUpdate(real_connect()))

    with pytest.raises(sqlite3.OperationalError):
        database.init()

    assert "email_verified_at" not in _columns(live_db)

    monkeypatch.setattr(database, "_connect", real_connect)
    database.init()
    assert database.get_user("first").email_verified_at is not None


def test_a_corrupt_stamp_reads_as_unverified(tmp_db):
    """Only this code writes the column; if it ever holds junk, the account can
    still verify again rather than the whole row becoming unreadable."""
    register("alice")
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET email_verified_at = 'not a date' WHERE username = 'alice'")
    conn.commit()
    conn.close()
    assert database.get_user("alice").email_verified_at is None


# --- manage.py check ------------------------------------------------------------------

def test_check_reports_the_pending_column_without_adding_it(live_db, capsys):
    import manage

    manage.cmd_check(None)

    out = capsys.readouterr().out
    [line] = [line for line in out.splitlines() if "email_verified_at" in line]
    assert "missing" in line and "2" in line, "check must say how many accounts will be grandfathered"
    assert "email_verified_at" not in _columns(live_db), "check must stay read-only"


def test_check_reports_verified_and_unverified_counts_once_present(live_db, capsys):
    import manage

    database.init()
    register("newcomer")
    capsys.readouterr()

    manage.cmd_check(None)

    [line] = [line for line in capsys.readouterr().out.splitlines() if "email_verified_at" in line]
    assert "present" in line
    assert "2 verified" in line and "1 unverified" in line


# --- database.mark_email_verified -----------------------------------------------------

def test_mark_email_verified_needs_the_address_on_the_account(tmp_db):
    register("alice")
    now = datetime.now(timezone.utc).replace(microsecond=0)

    assert not database.mark_email_verified("alice", "someone-else@example.com", now)
    assert database.get_user("alice").email_verified_at is None

    assert database.mark_email_verified("ALICE", "Alice@Example.com", now)
    assert database.get_user("alice").email_verified_at == now


def test_mark_email_verified_keeps_the_first_stamp(tmp_db):
    register("alice")
    first = datetime(2026, 1, 1, tzinfo=timezone.utc)
    later = datetime(2026, 6, 1, tzinfo=timezone.utc)

    assert database.mark_email_verified("alice", "alice@example.com", first)
    assert database.mark_email_verified("alice", "alice@example.com", later)
    assert database.get_user("alice").email_verified_at == first


def test_mark_email_verified_refuses_an_unknown_account(tmp_db):
    assert not database.mark_email_verified("ghost", "ghost@example.com", datetime.now(timezone.utc))


# --- the token --------------------------------------------------------------------

def _user(username="alice"):
    return database.get_user(username)


def _request(header, value):
    from starlette.requests import Request

    return Request({
        "type": "http", "http_version": "1.1", "method": "GET", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(header, value.encode())],
        "client": ("203.0.113.7", 40000), "server": ("testserver", 80),
    })


def test_the_token_carries_type_username_address_and_a_48_hour_expiry(tmp_db):
    register("alice")
    token = jwt_utils.create_email_verification_token("alice", "alice@example.com")

    claims = jwt.decode(token, config.JWT_SECRET_KEY, algorithms=["HS256"])

    assert claims["typ"] == "email_verify"
    assert claims["username"] == "alice"
    assert claims["email"] == "alice@example.com"
    assert claims["exp"] - claims["iat"] == pytest.approx(48 * 3600, abs=5)
    assert jwt_utils.read_email_verification_token(token) == ("alice", "alice@example.com")


def test_a_verification_token_is_never_a_session(tmp_db):
    token = jwt_utils.create_email_verification_token("alice", "alice@example.com")

    for request in (_request(b"cookie", f"token=Bearer {token}"),
                    _request(b"authorization", f"Bearer {token}")):
        with pytest.raises(HTTPException) as excinfo:
            jwt_utils.get_token_data(request)
        assert excinfo.value.status_code == 401


def test_a_verification_token_is_never_an_app_token(tmp_db):
    """Signed with the session key, so it lands on the legacy app-token path; that path must refuse it."""
    token = jwt_utils.create_email_verification_token("alice", "alice@example.com")

    with pytest.raises(HTTPException):
        jwt_utils.get_app_token_data(token)


def test_a_verification_token_is_never_a_password_reset_token(tmp_db):
    token = jwt_utils.create_email_verification_token("alice", "alice@example.com")

    assert jwt_utils.read_password_reset_token(token) is None


def test_the_verification_validator_refuses_every_other_token(tmp_db):
    register("alice")
    user = _user()
    now = datetime.now(timezone.utc)
    others = [
        jwt_utils.create_access_token(user, client=None),
        jwt_utils.create_access_token(user, expires_in=jwt_utils.MOBILE_TOKEN_EXPIRE, client=None),
        jwt_utils.create_app_jwt(jwt_utils.TokenData(username="alice", exp=now + timedelta(days=1)), "com.whatsapp"),
        jwt_utils.create_password_reset_token(user),
        # a legacy app token: session key, no typ, no exp
        jwt.encode({"username": "alice", "package_name": "com.whatsapp"}, jwt_utils.SECRET_KEY, algorithm="HS256"),
        # the right shape under the wrong key
        jwt.encode({"typ": "email_verify", "username": "alice", "email": "alice@example.com",
                    "exp": now + timedelta(hours=1)}, jwt_utils.APP_SECRET_KEY, algorithm="HS256"),
        # no exp
        jwt.encode({"typ": "email_verify", "username": "alice", "email": "alice@example.com"},
                   jwt_utils.SECRET_KEY, algorithm="HS256"),
        # no address
        jwt.encode({"typ": "email_verify", "username": "alice", "exp": now + timedelta(hours=1)},
                   jwt_utils.SECRET_KEY, algorithm="HS256"),
        # expired
        jwt.encode({"typ": "email_verify", "username": "alice", "email": "alice@example.com",
                    "exp": now - timedelta(seconds=5)}, jwt_utils.SECRET_KEY, algorithm="HS256"),
        "not a jwt",
    ]
    for token in others:
        assert jwt_utils.read_email_verification_token(token) is None, token


# --- the endpoints ------------------------------------------------------------------

VERIFIED = {"message": "Your email address is verified.", "username": "alice"}
INVALID = {"detail": "This verification link is invalid or has expired."}


@pytest.fixture
def client(tmp_db):
    from starlette.testclient import TestClient

    import main

    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def outbox(monkeypatch):
    """Mail configured and accepted; each send recorded instead of delivered."""
    from concurrent.futures import Future

    from utils import mailer

    sent = []

    def _send(to, subject, body, reply_to=None):
        sent.append({"to": to, "subject": subject, "body": body, "reply_to": reply_to})
        future = Future()
        future.set_result(True)
        return future

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "send", _send)
    return sent


def sign_up(client, username="alice", email=None):
    return client.post("/api/register", json={
        "username": username, "email": email or f"{username}@example.com", "password": PASSWORD})


def sign_in(client, username="alice"):
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200


def verify(client, token):
    return client.post("/api/auth/verify-email", json={"token": token})


def resend(client, **kwargs):
    return client.post("/api/auth/resend-verification", **kwargs)


def links_in(mail):
    import re

    return re.findall(r"https?://\S+/verify-email\?token=\S+", mail["body"])


def token_from(link):
    from urllib.parse import parse_qs, urlparse

    return parse_qs(urlparse(link).query)["token"][0]


def mailed_token(outbox):
    [link] = links_in(outbox[-1])
    return token_from(link)


# register

def test_registering_mails_a_verification_link(client, outbox):
    response = sign_up(client)

    assert response.status_code == 200
    assert response.json() == {"message": "User registered successfully", "verification_sent": True}
    [mail] = outbox
    assert mail["to"] == "alice@example.com"
    assert mail["subject"] == f"Verify your email for {config.SITE_HOST}"
    assert mail["reply_to"] == config.SUPPORT_EMAIL
    assert "48 hours" in mail["body"]
    [link] = links_in(mail)
    assert link.startswith(f"{config.FRONTEND_URL}/verify-email?token=")
    assert jwt_utils.read_email_verification_token(token_from(link)) == ("alice", "alice@example.com")
    assert database.get_user("alice").email_verified_at is None, "registering verifies nothing"


def test_registering_without_smtp_still_creates_the_account(client, monkeypatch):
    from utils import mailer

    monkeypatch.setattr(mailer, "is_configured", lambda: False)

    response = sign_up(client)

    assert response.status_code == 200
    assert response.json() == {"message": "User registered successfully", "verification_sent": False}
    assert database.get_user("alice") is not None


def test_verification_mail_shares_the_reset_mail_budget(client, outbox, monkeypatch):
    """One daily budget for every mail a stranger can make the site send to an
    address, so signups from many IPs cannot spend the sending account's quota
    on top of what resets may use."""
    from utils.ratelimit import reset_mail_budget

    monkeypatch.setattr(reset_mail_budget, "limit", 1)

    assert sign_up(client, "alice").json()["verification_sent"] is True
    second = sign_up(client, "bob")

    assert second.status_code == 200
    assert second.json() == {"message": "User registered successfully", "verification_sent": False}
    assert [mail["to"] for mail in outbox] == ["alice@example.com"]


def test_a_mail_failure_never_fails_the_registration(client, monkeypatch):
    from utils import mailer

    def _explode(*args, **kwargs):
        raise ValueError("bad header")

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "send", _explode)

    response = sign_up(client)

    assert response.status_code == 200
    assert response.json()["verification_sent"] is False
    assert database.get_user("alice") is not None


def test_the_smtp_session_runs_off_the_request_thread(client, monkeypatch):
    """The real mailer.send, with the executor replaced by one that only
    records: the response comes back without any delivery having run."""
    from concurrent.futures import Future

    from utils import mailer

    submitted = []

    class _Recorder:
        def submit(self, fn, *args):
            submitted.append((fn, args))
            return Future()

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "_get_executor", lambda: _Recorder())

    def _never(message):
        raise AssertionError("delivered on the request thread")

    monkeypatch.setattr(mailer, "deliver", _never)

    assert sign_up(client).json()["verification_sent"] is True
    [(fn, (message,))] = submitted
    assert fn is mailer._deliver_logged
    assert message["To"] == "alice@example.com"


# verify-email

def test_the_mailed_link_verifies_the_account(client, outbox):
    sign_up(client)

    response = verify(client, mailed_token(outbox))

    assert response.status_code == 200
    assert response.json() == VERIFIED
    assert database.get_user("alice").email_verified_at is not None


def test_verifying_twice_is_the_same_200(client, outbox):
    sign_up(client)
    token = mailed_token(outbox)
    assert verify(client, token).status_code == 200
    first = database.get_user("alice").email_verified_at

    again = verify(client, token)

    assert again.status_code == 200
    assert again.json() == VERIFIED
    assert database.get_user("alice").email_verified_at == first


def test_verify_email_needs_no_session(client, outbox):
    """The link is opened wherever the mail is read, often not where the user signed in."""
    sign_up(client)
    client.cookies.clear()
    assert verify(client, mailed_token(outbox)).status_code == 200


def test_the_username_in_the_answer_is_the_accounts(client, outbox):
    sign_up(client, "MixedCase", email="mixed@example.com")
    assert verify(client, mailed_token(outbox)).json()["username"] == "MixedCase"


def _forged(now=None, **claims):
    now = now or datetime.now(timezone.utc)
    payload = {"typ": "email_verify", "username": "alice", "email": "alice@example.com",
               "iat": now, "exp": now + timedelta(hours=48)}
    payload.update(claims)
    return jwt.encode(payload, jwt_utils.SECRET_KEY, algorithm="HS256")


def test_an_expired_link_is_refused(client, outbox):
    sign_up(client)
    stale = _forged(now=datetime.now(timezone.utc) - timedelta(hours=49))

    response = verify(client, stale)

    assert response.status_code == 400
    assert response.json() == INVALID
    assert database.get_user("alice").email_verified_at is None


def test_a_link_for_an_address_the_account_no_longer_uses_is_refused(client, outbox, tmp_db):
    sign_up(client)
    token = mailed_token(outbox)
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET email = 'moved@example.com' WHERE username = 'alice'")
    conn.commit()
    conn.close()

    response = verify(client, token)

    assert response.status_code == 400
    assert response.json() == INVALID
    assert database.get_user("alice").email_verified_at is None


def test_a_link_for_an_unknown_account_is_refused(client):
    response = verify(client, _forged(username="ghost", email="ghost@example.com"))
    assert response.status_code == 400
    assert response.json() == INVALID


@pytest.mark.parametrize("kind", ["session", "mobile", "reset", "wrong-key", "junk"])
def test_no_other_token_verifies_an_address(client, kind):
    register("alice")
    user = _user()
    token = {
        "session": lambda: jwt_utils.create_access_token(user, client=None),
        "mobile": lambda: jwt_utils.create_access_token(user, expires_in=jwt_utils.MOBILE_TOKEN_EXPIRE, client=None),
        "reset": lambda: jwt_utils.create_password_reset_token(user),
        "wrong-key": lambda: jwt.encode(jwt.decode(_forged(), options={"verify_signature": False}),
                                        jwt_utils.APP_SECRET_KEY, algorithm="HS256"),
        "junk": lambda: "not a jwt",
    }[kind]()

    response = verify(client, token)

    assert response.status_code == 400
    assert response.json() == INVALID
    assert database.get_user("alice").email_verified_at is None


def test_a_verification_token_cannot_reset_a_password(client, outbox):
    sign_up(client)
    response = client.post("/api/auth/reset-password",
                           json={"token": mailed_token(outbox), "password": "a brand new passphrase"})
    assert response.status_code == 400


def test_a_verification_token_is_not_a_session_cookie_or_bearer(client, outbox):
    sign_up(client)
    token = mailed_token(outbox)

    client.cookies.set("token", f"Bearer {token}")
    assert client.get("/api/users/me").status_code == 401
    client.cookies.clear()
    assert client.get("/api/users/me", headers={"Authorization": f"Bearer {token}"}).status_code == 401


def test_verify_email_is_rate_limited_per_ip(client):
    from utils.ratelimit import verify_email_rate_limit

    for _ in range(verify_email_rate_limit.limit):
        assert verify(client, "junk").status_code == 400
    response = verify(client, "junk")
    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) > 0


def test_verify_email_rejects_a_missing_token(client):
    assert client.post("/api/auth/verify-email", json={}).status_code == 422


# resend-verification

def test_resend_needs_a_session(client, outbox):
    assert resend(client).status_code == 401
    assert outbox == []


def test_resend_mails_a_fresh_link(client, outbox):
    sign_up(client)
    sign_in(client)
    outbox.clear()

    response = resend(client)

    assert response.status_code == 200
    assert response.json() == {"message": "We sent a new verification link to alice@example.com."}
    [mail] = outbox
    assert mail["to"] == "alice@example.com"
    assert verify(client, mailed_token(outbox)).status_code == 200


def test_resend_takes_a_bearer_token(client, outbox):
    sign_up(client)
    token = client.post("/api/auth/token", json={"username": "alice", "password": PASSWORD}).json()["access_token"]

    response = resend(client, headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200


def test_resend_for_a_verified_account_is_a_409(client, outbox):
    sign_up(client)
    verify(client, mailed_token(outbox))
    sign_in(client)
    outbox.clear()

    response = resend(client)

    assert response.status_code == 409
    assert response.json() == {"detail": "Your email address is already verified."}
    assert outbox == []


def test_a_grandfathered_account_is_already_verified(client, outbox, tmp_db):
    """What a pre-existing customer sees if a client offers them the button."""
    sign_up(client)
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET email_verified_at = '2026-09-30T12:00:00Z' WHERE username = 'alice'")
    conn.commit()
    conn.close()
    sign_in(client)

    assert resend(client).status_code == 409


def test_resend_without_smtp_is_a_503_naming_support(client, monkeypatch):
    from utils import mailer

    monkeypatch.setattr(mailer, "is_configured", lambda: False)
    sign_up(client)
    sign_in(client)

    response = resend(client)

    assert response.status_code == 503
    assert response.json() == {
        "detail": f"Email isn't available right now. Contact {config.SUPPORT_EMAIL} for help."}
    assert config.SUPPORT_EMAIL == "sudorm.ext@gmail.com"


def test_resend_with_the_daily_budget_spent_is_the_same_503(client, outbox, monkeypatch):
    from utils.ratelimit import reset_mail_budget

    sign_up(client)
    sign_in(client)
    monkeypatch.setattr(reset_mail_budget, "limit", 0)

    response = resend(client)

    assert response.status_code == 503
    assert config.SUPPORT_EMAIL in response.json()["detail"]


def test_resend_is_limited_per_account_whatever_the_ip(client, outbox):
    """Otherwise one account on someone else's address, driven from many IPs,
    turns the site into a mail cannon aimed at that inbox."""
    from utils.ratelimit import resend_verification_rate_limit

    sign_up(client)
    sign_in(client)
    outbox.clear()
    statuses = []
    for _ in range(5):
        resend_verification_rate_limit.reset()  # as if from a fresh IP every time
        statuses.append(resend(client))

    assert [r.status_code for r in statuses] == [200, 200, 200, 429, 429]
    assert int(statuses[-1].headers["Retry-After"]) > 0
    assert len(outbox) == 3


def test_resend_is_limited_per_ip(client, outbox):
    from utils.ratelimit import resend_verification_per_account_limit, resend_verification_rate_limit

    sign_up(client)
    sign_in(client)
    for _ in range(resend_verification_rate_limit.limit):
        resend_verification_per_account_limit.reset()
        assert resend(client).status_code == 200
    resend_verification_per_account_limit.reset()
    assert resend(client).status_code == 429


# --- checkout ------------------------------------------------------------------------

VERIFY_FIRST = {"detail": "Verify your email address before subscribing. Check your inbox for the link, "
                          "or send a new one from the Subscription page."}


def _explode(*args, **kwargs):
    raise AssertionError("Stripe was consulted for an account that may not subscribe")


class _NoStripe:
    def __getattr__(self, name):
        _explode()


@pytest.fixture
def no_stripe(monkeypatch):
    """Every road create-checkout could take to Stripe, closed. The customer
    lookup lives in stripe_utils, which holds its own reference to the client."""
    from api import payment
    from utils import stripe_utils

    monkeypatch.setattr(payment, "stripe_client", _NoStripe())
    monkeypatch.setattr(stripe_utils, "stripe_client", _NoStripe())
    monkeypatch.setattr(payment, "get_default_price_id", _explode)
    monkeypatch.setattr(payment, "customer_subscription", _explode)
    monkeypatch.setattr(payment, "find_subscription", _explode)


@pytest.fixture
def open_stripe(monkeypatch):
    """A Stripe that lets any checkout through, recording the sessions it opens."""
    from types import SimpleNamespace

    from api import payment
    from utils import stripe_utils

    opened = []
    fake = SimpleNamespace(v1=SimpleNamespace(
        customers=SimpleNamespace(
            list=lambda params=None, options=None: {"data": []},
            search=lambda params=None, options=None: {"data": []},
            create=lambda params=None, options=None: {"id": "cus_new"},
        ),
        checkout=SimpleNamespace(sessions=SimpleNamespace(
            create=lambda params=None, options=None: opened.append(params) or {"url": "https://checkout.test/s"},
        )),
    ))
    monkeypatch.setattr(payment, "get_default_price_id", lambda: "price_x")
    monkeypatch.setattr(payment, "customer_subscription", lambda customer_id: (True, None))
    monkeypatch.setattr(payment, "stripe_client", fake)
    monkeypatch.setattr(stripe_utils, "stripe_client", fake)
    return opened


def checkout(client):
    return client.post("/api/subscription/create-checkout", json={})


def test_an_unverified_account_cannot_start_a_checkout(client, outbox, no_stripe):
    sign_up(client)
    sign_in(client)

    response = checkout(client)

    assert response.status_code == 403
    assert response.json() == VERIFY_FIRST


def test_the_403_comes_before_the_active_subscription_409(client, outbox, no_stripe):
    """Checked first, so an account that may not subscribe costs no Stripe calls
    at all - not even the ones the duplicate-subscription guard makes."""
    sign_up(client)
    database.update_user_customer_id("username", "alice", "cus_paying")
    sign_in(client)

    assert checkout(client).status_code == 403


def test_verifying_unlocks_checkout_for_the_session_already_open(client, outbox, open_stripe):
    """The cookie was minted before the link was opened and is not re-minted:
    the state comes from the row."""
    sign_up(client)
    sign_in(client)
    assert checkout(client).status_code == 403

    assert verify(client, mailed_token(outbox)).status_code == 200

    response = checkout(client)
    assert response.status_code == 200
    assert response.json() == {"url": "https://checkout.test/s"}
    assert len(open_stripe) == 1


def test_checkout_trusts_the_row_not_the_token(client, outbox, no_stripe, tmp_db):
    """A token minted while the account was verified proves nothing now."""
    sign_up(client)
    verify(client, mailed_token(outbox))
    sign_in(client)
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET email_verified_at = NULL WHERE username = 'alice'")
    conn.commit()
    conn.close()

    assert checkout(client).status_code == 403


def test_a_grandfathered_account_checks_out_without_verifying(monkeypatch, live_db, open_stripe):
    from starlette.testclient import TestClient

    import main

    database.init()  # the deploy
    password_hash, scheme = passwords.hash_password(PASSWORD)  # so the old account can sign in
    conn = sqlite3.connect(live_db)
    conn.execute("UPDATE users SET password_hash = ?, password_salt = '', password_scheme = ? "
                 "WHERE username = 'first'", (password_hash, scheme))
    conn.commit()
    conn.close()

    with TestClient(main.app) as live_client:
        sign_in(live_client, "first")
        assert checkout(live_client).status_code == 200


def test_checkout_for_an_account_that_no_longer_exists_is_a_401(client, outbox, no_stripe, tmp_db):
    sign_up(client)
    sign_in(client)
    conn = sqlite3.connect(tmp_db)
    conn.execute("DELETE FROM users WHERE username = 'alice'")
    conn.commit()
    conn.close()

    assert checkout(client).status_code == 401


# --- forgot-password and reset-password ----------------------------------------------

def forgot(client, email):
    return client.post("/api/auth/forgot-password", json={"email": email})


def reset_links_in(mail):
    import re

    return re.findall(r"https?://\S+/reset-password\?token=\S+", mail["body"])


def test_an_unverified_account_is_not_sent_a_reset_link(client, outbox):
    """Registration does not prove the address, so a reset link could hand the
    account to whoever does own that inbox - or aim mail at a stranger."""
    sign_up(client)
    outbox.clear()

    response = forgot(client, "alice@example.com")

    assert response.status_code == 200
    assert outbox == []


def test_forgot_password_answers_the_same_whatever_the_account_state(client, outbox):
    """Unknown, unverified and verified addresses must be indistinguishable."""
    sign_up(client, "unverified")
    sign_up(client, "verified")
    verify(client, mailed_token(outbox))
    outbox.clear()

    answers = [forgot(client, email) for email in
               ("nobody@example.com", "unverified@example.com", "verified@example.com")]

    assert len({response.status_code for response in answers}) == 1
    assert len({response.text for response in answers}) == 1
    assert len({tuple(sorted((k, v) for k, v in response.headers.items() if k != "date"))
                for response in answers}) == 1
    assert [mail["to"] for mail in outbox] == ["verified@example.com"]


def test_a_shared_address_lists_only_its_verified_accounts(client, outbox, tmp_db):
    sign_up(client, "first", email="shared@example.com")
    verify(client, mailed_token(outbox))
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP INDEX idx_users_email_unique")  # as on a database with historical duplicates
    conn.close()
    register("second", email="shared@example.com")
    outbox.clear()

    forgot(client, "shared@example.com")

    [mail] = outbox
    [link] = reset_links_in(mail)
    assert "first" in mail["body"] and "second" not in mail["body"]


def test_completing_a_reset_verifies_the_address(client):
    """Only the inbox could have received the link, so using it proves the address."""
    register("alice")
    token = jwt_utils.create_password_reset_token(_user())

    response = client.post("/api/auth/reset-password", json={"token": token, "password": "a brand new passphrase"})

    assert response.status_code == 200
    assert _user().email_verified_at is not None


def test_a_reset_keeps_an_earlier_verification_stamp(client):
    register("alice")
    first = datetime(2026, 1, 1, tzinfo=timezone.utc)
    database.mark_email_verified("alice", "alice@example.com", first)
    token = jwt_utils.create_password_reset_token(_user())

    client.post("/api/auth/reset-password", json={"token": token, "password": "a brand new passphrase"})

    assert _user().email_verified_at == first


def test_a_refused_reset_verifies_nothing(client):
    register("alice")
    client.post("/api/auth/reset-password", json={"token": "junk", "password": "a brand new passphrase"})
    assert _user().email_verified_at is None


# /users/me

def test_me_says_whether_the_address_is_verified(client, outbox):
    sign_up(client)
    sign_in(client)
    assert client.get("/api/users/me").json()["email_verified"] is False

    verify(client, mailed_token(outbox))

    me = client.get("/api/users/me").json()
    assert me["email_verified"] is True
    assert me["username"] == "alice" and me["email"] == "alice@example.com"
    assert "email_verified_at" not in me
