"""Password reset by email: POST /api/auth/forgot-password and /api/auth/reset-password.

Mail is always a recorder; nothing here reaches an SMTP server.
"""
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlparse

import jwt
import pytest
from starlette.exceptions import HTTPException
from starlette.testclient import TestClient

import config
import database
from utils import jwt_utils, mailer, passwords
from utils.ratelimit import forgot_password_rate_limit, reset_password_rate_limit

PASSWORD = "correct horse battery"
NEW_PASSWORD = "a brand new passphrase"
GENERIC = {"message": "If an account uses that email, we've sent a reset link. It expires in 1 hour."}


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def outbox(monkeypatch):
    """Mail configured, and every send recorded instead of delivered."""
    sent = []

    def _send(to, subject, body, reply_to=None):
        sent.append({"to": to, "subject": subject, "body": body, "reply_to": reply_to})

    monkeypatch.setattr(mailer, "is_configured", lambda: True)
    monkeypatch.setattr(mailer, "send", _send)
    return sent


def register(username, email, password=PASSWORD):
    """An account with a verified address: only those are sent reset links.
    The unverified case is covered in test_email_verification.py."""
    assert database.register_user(database.RegisterRequest(username=username, email=email, password=password))
    assert database.mark_email_verified(username, email, datetime.now(timezone.utc))


def forgot(client, email):
    return client.post("/api/auth/forgot-password", json={"email": email})


def reset(client, token, password=NEW_PASSWORD):
    return client.post("/api/auth/reset-password", json={"token": token, "password": password})


def links_in(mail):
    return re.findall(r"https?://\S+/reset-password\?token=\S+", mail["body"])


def token_from(link):
    return parse_qs(urlparse(link).query)["token"][0]


def login(username, password):
    return database.login_user(database.LoginRequest(username=username, password=password))


# --- forgot-password ------------------------------------------------------------

def test_a_known_email_gets_a_link_and_the_generic_answer(client, outbox):
    register("alice", "alice@example.com")

    response = forgot(client, "alice@example.com")

    assert response.status_code == 200
    assert response.json() == GENERIC
    [mail] = outbox
    assert mail["to"].lower() == "alice@example.com"
    assert mail["reply_to"] == config.SUPPORT_EMAIL
    [link] = links_in(mail)
    assert link.startswith(f"{config.FRONTEND_URL}/reset-password?token=")
    assert "alice" in mail["body"]


def test_an_unknown_email_gets_the_same_answer_and_no_mail(client, outbox):
    response = forgot(client, "nobody@example.com")

    assert response.status_code == 200
    assert response.json() == GENERIC
    assert outbox == []


def test_the_answer_is_sent_before_any_account_lookup(client, outbox, monkeypatch):
    """Doing the lookup and building the mail inside the request made it ~0.8 ms
    slower for an address with accounts - a timing oracle for the enumeration the
    identical answer is meant to prevent. The work now runs after the response,
    so even a lookup that blows up cannot change what the caller sees."""
    def _explode(email):
        raise RuntimeError("lookup must not run on the response path")

    monkeypatch.setattr(database, "get_users_by_email", _explode)

    response = forgot(client, "alice@example.com")

    assert response.status_code == 200
    assert response.json() == GENERIC


def test_the_lookup_ignores_case(client, outbox):
    register("alice", "Alice@Example.com")
    forgot(client, "alice@example.COM")
    assert len(outbox) == 1


def test_a_shared_email_lists_every_account_with_its_own_link(client, outbox, tmp_db):
    """users.email is not unique on the live database, so one address can own several."""
    import sqlite3

    register("first", "shared@example.com")
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP INDEX idx_users_email_unique")  # as on a database with historical duplicates
    conn.close()
    register("second", "shared@example.com")

    forgot(client, "shared@example.com")

    [mail] = outbox
    links = links_in(mail)
    assert len(links) == 2
    usernames = {jwt.decode(token_from(link), options={"verify_signature": False})["username"] for link in links}
    assert usernames == {"first", "second"}


def test_no_smtp_is_a_503_naming_the_support_address(client, monkeypatch):
    monkeypatch.setattr(mailer, "is_configured", lambda: False)

    response = forgot(client, "alice@example.com")

    assert response.status_code == 503
    assert response.json() == {"detail": "Password reset by email isn't available right now. "
                                         f"Email {config.SUPPORT_EMAIL} for help."}


def test_forgot_password_rejects_a_malformed_email(client, outbox):
    assert forgot(client, "not an email").status_code == 422


def test_forgot_password_is_rate_limited_per_ip(client, outbox):
    for _ in range(forgot_password_rate_limit.limit):
        assert forgot(client, "nobody@example.com").status_code == 200
    response = forgot(client, "nobody@example.com")
    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) > 0


def test_one_address_cannot_be_mail_bombed_from_many_ips(client, outbox):
    """Per-IP limits alone would let a botnet flood one inbox (and get the
    sending account throttled). Past the per-address cap the answer stays the
    same - revealing the cap would reveal that the address has an account."""
    register("alice", "alice@example.com")
    responses = []
    for _ in range(10):
        forgot_password_rate_limit.reset()  # as if from a fresh IP every time
        responses.append(forgot(client, "alice@example.com"))

    assert all(response.json() == GENERIC for response in responses)
    assert 0 < len(outbox) < 10


def test_reset_mail_has_its_own_daily_budget(client, outbox, monkeypatch):
    """A global cap, separate from bug reports', so guessing addresses from many
    IPs cannot spend the sending account's whole daily quota."""
    from utils.ratelimit import reset_mail_budget

    monkeypatch.setattr(reset_mail_budget, "limit", 1)
    register("alice", "alice@example.com")
    register("bob", "bob@example.com")

    assert forgot(client, "alice@example.com").json() == GENERIC
    assert forgot(client, "bob@example.com").json() == GENERIC

    assert [mail["to"] for mail in outbox] == ["alice@example.com"]


# --- the token --------------------------------------------------------------------

def _user(username="alice"):
    return database.get_user(username)


def test_the_token_carries_type_expiry_and_a_password_digest(tmp_db):
    register("alice", "alice@example.com")
    token = jwt_utils.create_password_reset_token(_user())

    claims = jwt.decode(token, jwt_utils.SECRET_KEY, algorithms=[jwt_utils.ALGORITHM])

    assert claims["typ"] == "password_reset"
    assert claims["username"] == "alice"
    assert claims["pwh"] and _user().password_hash not in token
    lifetime = claims["exp"] - claims["iat"]
    assert lifetime == pytest.approx(3600, abs=5)


def _request(header, value):
    from starlette.requests import Request

    return Request({
        "type": "http", "http_version": "1.1", "method": "GET", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(header, value.encode())],
        "client": ("203.0.113.7", 40000), "server": ("testserver", 80),
    })


def test_a_reset_token_is_never_a_session(tmp_db):
    register("alice", "alice@example.com")
    token = jwt_utils.create_password_reset_token(_user())

    for request in (_request(b"cookie", f"token=Bearer {token}"),
                    _request(b"authorization", f"Bearer {token}")):
        with pytest.raises(HTTPException) as excinfo:
            jwt_utils.get_token_data(request)
        assert excinfo.value.status_code == 401


def test_a_reset_token_is_never_an_app_token(tmp_db):
    """Signed with the session key, so it lands on the legacy app-token path; that path must refuse it."""
    register("alice", "alice@example.com")
    token = jwt_utils.create_password_reset_token(_user())

    with pytest.raises(HTTPException):
        jwt_utils.get_app_token_data(token)


def test_the_reset_validator_refuses_every_other_token(tmp_db):
    register("alice", "alice@example.com")
    user = _user()
    now = datetime.now(timezone.utc)
    others = [
        jwt_utils.create_access_token(user, client=None),
        jwt_utils.create_app_jwt(jwt_utils.TokenData(username="alice", exp=now + timedelta(days=1)), "com.whatsapp"),
        # a legacy app token: session key, no typ, no exp
        jwt.encode({"username": "alice", "package_name": "com.whatsapp"}, jwt_utils.SECRET_KEY, algorithm="HS256"),
        # the right shape under the wrong key
        jwt.encode({"typ": "password_reset", "username": "alice", "pwh": "x", "exp": now + timedelta(hours=1)},
                   jwt_utils.APP_SECRET_KEY, algorithm="HS256"),
        # no exp
        jwt.encode({"typ": "password_reset", "username": "alice", "pwh": "x"}, jwt_utils.SECRET_KEY, algorithm="HS256"),
        "not a jwt",
    ]
    for token in others:
        assert jwt_utils.read_password_reset_token(token) is None, token


# --- reset-password ---------------------------------------------------------------

def _mailed_token(client, outbox, email="alice@example.com"):
    forgot(client, email)
    return token_from(links_in(outbox[-1])[0])


def test_reset_sets_a_new_argon2_password(client, outbox, tmp_db):
    register("alice", "alice@example.com")
    token = _mailed_token(client, outbox)

    response = reset(client, token)

    assert response.status_code == 200
    assert response.json() == {"message": "Your password has been updated. You can log in now."}
    assert login("alice", NEW_PASSWORD) is not None
    assert login("alice", PASSWORD) is None
    assert _user().password_scheme == passwords.SCHEME_ARGON2
    assert _user().password_hash.startswith("$argon2")


def test_a_reset_link_works_once(client, outbox):
    register("alice", "alice@example.com")
    token = _mailed_token(client, outbox)

    assert reset(client, token).status_code == 200
    second = reset(client, token, password="yet another passphrase")

    assert second.status_code == 400
    assert second.json() == {"detail": "This reset link is invalid or has expired."}
    assert login("alice", NEW_PASSWORD) is not None


def test_a_reset_link_dies_when_the_password_changes_another_way(client, outbox):
    register("alice", "alice@example.com")
    first = _mailed_token(client, outbox)
    second = _mailed_token(client, outbox)

    assert reset(client, second).status_code == 200
    assert reset(client, first).status_code == 400


def test_an_expired_link_is_refused(client, outbox, tmp_db):
    register("alice", "alice@example.com")
    user = _user()
    expired = jwt.encode({
        "typ": "password_reset", "username": "alice",
        "pwh": jwt_utils.password_hash_digest(user.password_hash),
        "iat": datetime.now(timezone.utc) - timedelta(hours=2),
        "exp": datetime.now(timezone.utc) - timedelta(hours=1),
    }, jwt_utils.SECRET_KEY, algorithm="HS256")

    assert reset(client, expired).status_code == 400


def test_a_session_token_cannot_reset_a_password(client):
    register("alice", "alice@example.com")
    assert reset(client, jwt_utils.create_access_token(_user(), client=None)).status_code == 400


def test_the_new_password_is_validated_like_registration(client, outbox):
    register("alice", "alice@example.com")
    token = _mailed_token(client, outbox)

    assert reset(client, token, password="short").status_code == 422
    assert reset(client, token, password="x" * (passwords.MAX_PASSWORD_LENGTH + 1)).status_code == 422
    # a rejected attempt does not burn the link
    assert reset(client, token).status_code == 200


def test_reset_password_is_rate_limited_per_ip(client):
    for _ in range(reset_password_rate_limit.limit):
        assert reset(client, "junk").status_code == 400
    assert reset(client, "junk").status_code == 429
