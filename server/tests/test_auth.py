"""Regression tests for the auth layer hardening."""
import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone

import jwt
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.exceptions import HTTPException
from starlette.requests import Request

import config
import database
from utils import jwt_utils, passwords
from utils.ratelimit import RateLimiter, client_ip, login_rate_limit, register_rate_limit

PASSWORD = "correct horse battery"


def register(username, email="user@example.com", password=PASSWORD):
    return database.register_user(
        database.RegisterRequest(username=username, email=email, password=password)
    )


def login(username, password=PASSWORD):
    return database.login_user(database.LoginRequest(username=username, password=password))


def fetch_row(db_path, username):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    row = conn.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    conn.close()
    return row


def insert_legacy_user(db_path, username, password, email="legacy@example.com"):
    """Write a row exactly the way the pre-migration code did."""
    salt = passwords.new_salt()
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO users (username, email, password_hash, password_salt, password_scheme) "
        "VALUES (?, ?, ?, ?, ?)",
        (username, email, passwords.legacy_hash(password, salt), salt, passwords.SCHEME_LEGACY),
    )
    conn.commit()
    conn.close()
    return salt


def cookie_request(value, host="203.0.113.7"):
    return Request({
        "type": "http", "http_version": "1.1", "method": "GET", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(b"cookie", f"token={value}".encode())],
        "client": (host, 40000), "server": ("testserver", 80),
    })


def header_request(value, host="203.0.113.7"):
    return Request({
        "type": "http", "http_version": "1.1", "method": "GET", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(b"authorization", value.encode())],
        "client": (host, 40000), "server": ("testserver", 80),
    })


def plain_request(host="203.0.113.7", headers=None):
    return Request({
        "type": "http", "http_version": "1.1", "method": "POST", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(k.encode(), v.encode()) for k, v in (headers or {}).items()],
        "client": (host, 40000), "server": ("testserver", 80),
    })


@pytest.fixture
def client(tmp_db):
    from api import users as users_api

    login_rate_limit.reset()
    register_rate_limit.reset()
    app = FastAPI()
    app.include_router(users_api.router, prefix="/api")
    with TestClient(app) as test_client:
        yield test_client
    login_rate_limit.reset()
    register_rate_limit.reset()


# --- password storage -------------------------------------------------------

def test_same_password_gives_different_hashes_and_salts(tmp_db):
    assert register("alice", "alice@example.com")
    assert register("bob", "bob@example.com")

    alice = fetch_row(tmp_db, "alice")
    bob = fetch_row(tmp_db, "bob")

    assert alice["password_scheme"] == passwords.SCHEME_ARGON2
    assert bob["password_scheme"] == passwords.SCHEME_ARGON2
    assert alice["password_hash"] != bob["password_hash"]
    # argon2 encodes its per-hash salt in field 4 of the PHC string
    assert alice["password_hash"].split("$")[4] != bob["password_hash"].split("$")[4]
    assert login("alice") is not None and login("bob") is not None


def test_new_registration_is_argon2(tmp_db):
    assert register("carol", "carol@example.com")
    assert fetch_row(tmp_db, "carol")["password_hash"].startswith("$argon2")


# --- transparent legacy migration ------------------------------------------

def test_legacy_user_logs_in_and_is_upgraded(tmp_db):
    legacy_salt = insert_legacy_user(tmp_db, "olduser", PASSWORD)
    assert fetch_row(tmp_db, "olduser")["password_scheme"] == passwords.SCHEME_LEGACY

    user = login("olduser")
    assert user is not None

    upgraded = fetch_row(tmp_db, "olduser")
    assert upgraded["password_scheme"] == passwords.SCHEME_ARGON2
    assert upgraded["password_hash"].startswith("$argon2")
    assert upgraded["password_hash"] != legacy_salt
    # the same password keeps working after the upgrade, nobody is logged out
    assert login("olduser") is not None
    assert login("olduser", "not the password") is None


def test_a_login_upgrade_cannot_undo_a_password_reset(tmp_db):
    """A legacy login that verified the OLD password, landing after a reset,
    must not write an Argon2 hash of that old password back over the new one."""
    insert_legacy_user(tmp_db, "olduser", PASSWORD)
    stale = database.get_user("olduser")  # what the in-flight login read

    assert database.set_password(database.get_user("olduser"), "the brand new password")
    database._upgrade_password(stale, PASSWORD)

    assert login("olduser", "the brand new password") is not None
    assert login("olduser", PASSWORD) is None


def test_wrong_password_fails_for_both_schemes(tmp_db):
    insert_legacy_user(tmp_db, "olduser", PASSWORD)
    assert register("newuser", "new@example.com")

    assert login("olduser", "wrong") is None
    assert login("newuser", "wrong") is None
    assert fetch_row(tmp_db, "olduser")["password_scheme"] == passwords.SCHEME_LEGACY
    assert login("nobody") is None


class _VerifySpy:
    """Wraps the real Argon2 hasher and counts verify() calls."""

    def __init__(self, real):
        self._real = real
        self.verifies = 0

    def __getattr__(self, name):
        return getattr(self._real, name)

    def verify(self, stored_hash, password):
        self.verifies += 1
        return self._real.verify(stored_hash, password)


@pytest.fixture
def verify_spy(monkeypatch):
    spy = _VerifySpy(passwords._hasher)
    monkeypatch.setattr(passwords, "_hasher", spy)
    return spy


def test_an_unknown_username_still_costs_an_argon2_verify(tmp_db, verify_spy):
    """Returning early made a login for a name that does not exist ~50 ms faster
    than one for a name that does - a username oracle, rate limit or not."""
    assert login("nobody-by-this-name") is None
    assert verify_spy.verifies == 1


def test_a_legacy_account_costs_an_argon2_verify_too(tmp_db, verify_spy):
    """Otherwise the fast SHA-512 path would single out accounts that exist."""
    insert_legacy_user(tmp_db, "olduser", PASSWORD)
    assert login("olduser", "wrong password") is None
    assert verify_spy.verifies == 1


def test_an_argon2_account_is_verified_once(tmp_db, verify_spy):
    assert register("modern", "modern@example.com")
    assert login("modern", "wrong password") is None
    assert verify_spy.verifies == 1


def test_init_migrates_a_pre_migration_database(monkeypatch, tmp_path):
    db_path = tmp_path / "legacy.db"
    conn = sqlite3.connect(db_path)
    conn.execute("""CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL, email TEXT NOT NULL,
                        password_hash TEXT NOT NULL, password_salt TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        customer_id TEXT DEFAULT NULL,
                        device_fingerprints TEXT DEFAULT "[]")""")
    salt = passwords.new_salt()
    conn.execute(
        "INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, ?, ?)",
        ("live", "live@example.com", passwords.legacy_hash(PASSWORD, salt), salt),
    )
    conn.commit()
    conn.close()

    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path)
    database.init()
    database.init()  # idempotent

    columns = {row[1] for row in sqlite3.connect(db_path).execute("PRAGMA table_info(users)")}
    assert "password_scheme" in columns
    assert fetch_row(db_path, "live")["password_scheme"] == passwords.SCHEME_LEGACY
    indexes = {row[0] for row in sqlite3.connect(db_path).execute(
        "SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert "idx_users_username_unique" in indexes
    assert "idx_users_customer_id_unique" in indexes
    assert "idx_users_email" in indexes
    assert login("live") is not None


def test_init_survives_duplicate_usernames_already_in_the_database(monkeypatch, tmp_path, caplog):
    db_path = tmp_path / "dupes.db"
    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path)
    database.init()

    conn = sqlite3.connect(db_path)
    # Simulate a database that predates both unique indexes.
    conn.execute("DROP INDEX idx_users_username_unique")
    conn.execute("DROP INDEX idx_users_email_unique")
    for name in ("Dupe", "dupe"):
        conn.execute(
            "INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, '', '')",
            (name, f"{name}@example.com"),
        )
    conn.commit()
    conn.close()

    database.init()  # must not raise

    indexes = {row[0] for row in sqlite3.connect(db_path).execute(
        "SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert "idx_users_username_unique" not in indexes
    assert "idx_users_email" in indexes
    assert any("dupe" in record.getMessage() for record in caplog.records)


# --- uniqueness -------------------------------------------------------------

def test_duplicate_username_registration_fails(tmp_db):
    assert register("taken", "a@example.com")
    # Truthiness, not `is False`: RegisterResult members are enum values, so an
    # identity check against False reads every rejection as success.
    assert register("taken", "b@example.com") is database.RegisterResult.TAKEN
    assert register("TAKEN", "c@example.com") is database.RegisterResult.TAKEN
    assert not register("taken", "d@example.com")

    conn = sqlite3.connect(tmp_db)
    assert conn.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 1
    conn.close()


def test_username_lookup_is_case_insensitive(tmp_db):
    assert register("MixedCase", "mixed@example.com")
    assert database.get_user("mixedcase") is not None
    assert login("MIXEDCASE") is not None
    # stored as entered
    assert database.get_user("mixedcase").username == "MixedCase"


def test_duplicate_customer_id_is_rejected(tmp_db):
    assert register("one", "one@example.com")
    assert register("two", "two@example.com")
    assert database.update_user_customer_id("username", "one", "cus_123")
    assert database.update_user_customer_id("username", "two", "cus_123") is False
    assert database.get_user_by_customer_id("cus_123").username == "one"


def test_update_user_customer_id_rejects_unknown_identifier(tmp_db):
    with pytest.raises(ValueError):
        database.update_user_customer_id("username = 'x' OR '1'='1'", "v", "cus_1")


# --- device fingerprints ----------------------------------------------------

def test_device_registration_results(tmp_db, monkeypatch):
    monkeypatch.setattr(database, "MAX_DEVICES_ALLOWED", 2)
    assert register("dev", "dev@example.com")

    assert database.update_user_device_fingerprints("dev", "a") is database.DeviceResult.ADDED
    assert database.update_user_device_fingerprints("dev", "a") is database.DeviceResult.ALREADY_REGISTERED
    assert database.update_user_device_fingerprints("dev", "b") is database.DeviceResult.ADDED
    assert database.update_user_device_fingerprints("dev", "c") is database.DeviceResult.LIMIT_REACHED
    assert database.update_user_device_fingerprints("ghost", "a") is database.DeviceResult.USER_NOT_FOUND

    # still truthy where the old boolean return was used as "device allowed"
    assert database.update_user_device_fingerprints("dev", "a")
    assert not database.update_user_device_fingerprints("dev", "c")
    assert database.get_user("dev").device_fingerprints == ["a", "b"]


def test_concurrent_device_registration_respects_the_limit(tmp_db, monkeypatch):
    monkeypatch.setattr(database, "MAX_DEVICES_ALLOWED", 3)
    assert register("racer", "racer@example.com")

    start = threading.Barrier(8)

    def add(index):
        start.wait()
        database.update_user_device_fingerprints("racer", f"device-{index}")

    threads = [threading.Thread(target=add, args=(i,)) for i in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(database.get_user("racer").device_fingerprints) == 3


# --- token model ------------------------------------------------------------

def session_token(**claims):
    payload = {"username": "tokenuser", "email": "t@example.com", "customer_id": "",
               "typ": jwt_utils.TOKEN_TYPE_SESSION,
               "exp": datetime.now(timezone.utc) + timedelta(minutes=5)}
    payload.update(claims)
    payload = {k: v for k, v in payload.items() if v is not None}
    return jwt.encode(payload, jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM)


def test_valid_session_token_is_accepted():
    data = jwt_utils.get_token_data(cookie_request(f"Bearer {session_token()}"))
    assert data.username == "tokenuser"
    assert data.typ == jwt_utils.TOKEN_TYPE_SESSION


def test_app_token_is_rejected_as_a_session_cookie():
    """The privilege escalation regression: a token pulled out of an APK must not log anyone in."""
    app_token = jwt_utils.create_app_jwt(
        jwt_utils.TokenData(username="victim", customer_id="cus_1",
                            exp=datetime.now(timezone.utc) + timedelta(minutes=5)),
        "com.whatsapp",
    )
    with pytest.raises(HTTPException) as excinfo:
        jwt_utils.get_token_data(cookie_request(f"Bearer {app_token}"))
    assert excinfo.value.status_code == 401

    # ... and not even if it were signed with the session key
    forged = jwt.encode(
        {"username": "victim", "package_name": "com.whatsapp", "typ": jwt_utils.TOKEN_TYPE_APP,
         "exp": datetime.now(timezone.utc) + timedelta(days=400)},
        jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM,
    )
    with pytest.raises(HTTPException):
        jwt_utils.get_token_data(cookie_request(f"Bearer {forged}"))


def test_expired_session_token_is_rejected():
    expired = session_token(exp=datetime.now(timezone.utc) - timedelta(seconds=5))
    with pytest.raises(HTTPException) as excinfo:
        jwt_utils.get_token_data(cookie_request(f"Bearer {expired}"))
    assert excinfo.value.status_code == 401


def test_session_token_without_exp_is_rejected():
    forever = jwt.encode(
        {"username": "tokenuser", "typ": jwt_utils.TOKEN_TYPE_SESSION},
        jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM,
    )
    with pytest.raises(HTTPException):
        jwt_utils.get_token_data(cookie_request(f"Bearer {forever}"))


def test_missing_or_malformed_cookie_is_rejected():
    for value in ("", "Bearer ", "Bearer not-a-jwt", "garbage"):
        with pytest.raises(HTTPException):
            jwt_utils.get_token_data(cookie_request(value))


def test_app_token_round_trip():
    token = jwt_utils.create_app_jwt(
        jwt_utils.TokenData(username="appuser", customer_id="cus_9",
                            exp=datetime.now(timezone.utc) + timedelta(minutes=5)),
        "com.tranzmate",
    )
    data = jwt_utils.get_app_token_data(token)
    assert data.username == "appuser"
    assert data.customer_id == "cus_9"
    assert data.package_name == "com.tranzmate"
    assert data.exp is not None


def test_legacy_app_token_is_still_accepted(caplog):
    """APKs already on customers' phones carry an old-key, typ-less, exp-less token."""
    legacy = jwt.encode(
        {"username": "appuser", "customer_id": "cus_9", "package_name": "com.whatsapp"},
        jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM,
    )
    data = jwt_utils.get_app_token_data(legacy)
    assert data.username == "appuser"
    assert data.package_name == "com.whatsapp"
    assert data.typ is None
    assert any("legacy app token" in record.getMessage() for record in caplog.records)


def _legacy_token():
    return jwt.encode(
        {"username": "appuser", "customer_id": "cus_9", "package_name": "com.whatsapp"},
        jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM,
    )


def test_legacy_app_tokens_still_work_before_the_cutoff(monkeypatch):
    monkeypatch.setattr(config, "LEGACY_APP_TOKEN_CUTOFF", datetime.now(timezone.utc) + timedelta(days=1))
    assert jwt_utils.get_app_token_data(_legacy_token()).username == "appuser"


def test_legacy_app_tokens_are_refused_once_the_cutoff_has_passed(monkeypatch, caplog):
    monkeypatch.setattr(config, "LEGACY_APP_TOKEN_CUTOFF", datetime.now(timezone.utc) - timedelta(seconds=1))

    with pytest.raises(HTTPException) as excinfo:
        jwt_utils.get_app_token_data(_legacy_token())

    assert excinfo.value.status_code == 401
    assert any("LEGACY_APP_TOKEN_CUTOFF" in record.getMessage() for record in caplog.records)


def test_the_cutoff_never_touches_current_app_tokens(monkeypatch):
    monkeypatch.setattr(config, "LEGACY_APP_TOKEN_CUTOFF", datetime.now(timezone.utc) - timedelta(days=1))
    token = jwt_utils.create_app_jwt(
        jwt_utils.TokenData(username="appuser", customer_id="cus_9",
                            exp=datetime.now(timezone.utc) + timedelta(minutes=5)),
        "com.whatsapp",
    )
    assert jwt_utils.get_app_token_data(token).username == "appuser"


@pytest.mark.parametrize("raw, expected", [
    (None, None),
    ("", None),
    ("  ", None),
    ("2027-01-31", datetime(2027, 1, 31, tzinfo=timezone.utc)),
    ("2027-01-31T12:00:00+02:00", datetime(2027, 1, 31, 10, tzinfo=timezone.utc)),
])
def test_the_cutoff_is_an_iso_date_read_as_utc(raw, expected):
    assert config._parse_cutoff("LEGACY_APP_TOKEN_CUTOFF", raw) == expected


def test_a_blank_number_in_the_env_file_means_the_default(monkeypatch):
    """`SMTP_PORT=` in .env raised ValueError at import, which fails doctor
    (the unit's ExecStartPre) and keeps the whole site down."""
    monkeypatch.setenv("SOME_KNOB", "   ")
    assert config._int_env("SOME_KNOB", 465) == 465
    monkeypatch.delenv("SOME_KNOB")
    assert config._int_env("SOME_KNOB", 465) == 465
    monkeypatch.setenv("SOME_KNOB", " 587 ")
    assert config._int_env("SOME_KNOB", 465) == 587


def test_a_malformed_number_names_the_variable():
    import os

    os.environ["SOME_KNOB"] = "sixty"
    try:
        with pytest.raises(config.ConfigError, match="SOME_KNOB"):
            config._int_env("SOME_KNOB", 60)
    finally:
        del os.environ["SOME_KNOB"]


def test_a_malformed_cutoff_fails_loudly_at_startup():
    """Silently ignoring a typo would leave the owner believing old tokens were retired."""
    with pytest.raises(config.ConfigError):
        config._parse_cutoff("LEGACY_APP_TOKEN_CUTOFF", "31/01/2027")


def test_session_token_is_not_accepted_as_an_app_token():
    with pytest.raises(HTTPException):
        jwt_utils.get_app_token_data(session_token())
    with pytest.raises(HTTPException):
        jwt_utils.get_app_token_data("")


def test_session_and_app_tokens_use_different_keys():
    assert jwt_utils.SECRET_KEY != jwt_utils.APP_SECRET_KEY


def test_token_is_never_written_to_the_log(caplog):
    token = session_token(exp=datetime.now(timezone.utc) - timedelta(seconds=5))
    with pytest.raises(HTTPException):
        jwt_utils.get_token_data(cookie_request(f"Bearer {token}"))
    assert all(token not in record.getMessage() for record in caplog.records)


def test_user_in_db_never_dumps_credentials(tmp_db):
    assert register("dumped", "dumped@example.com")
    user = database.get_user("dumped")
    dumped = user.model_dump()
    assert "password_hash" not in dumped and "password_salt" not in dumped
    assert "password_scheme" not in dumped
    assert user.password_hash and repr(user).find(user.password_hash) == -1


# --- rate limiting ----------------------------------------------------------

def test_rate_limiter_blocks_after_the_threshold():
    limiter = RateLimiter("unit", limit=3, window_seconds=60)
    assert [limiter.retry_after("1.2.3.4") for _ in range(3)] == [0, 0, 0]
    wait = limiter.retry_after("1.2.3.4")
    assert wait > 0
    # a different client is unaffected
    assert limiter.retry_after("5.6.7.8") == 0
    limiter.reset("1.2.3.4")
    assert limiter.retry_after("1.2.3.4") == 0


def test_rate_limiter_window_slides():
    limiter = RateLimiter("unit", limit=1, window_seconds=0.05)
    assert limiter.retry_after("1.2.3.4") == 0
    assert limiter.retry_after("1.2.3.4") > 0
    time.sleep(0.06)
    assert limiter.retry_after("1.2.3.4") == 0


def test_rate_limiter_dependency_raises_429():
    limiter = RateLimiter("unit", limit=1, window_seconds=60)
    limiter(plain_request())
    with pytest.raises(HTTPException) as excinfo:
        limiter(plain_request())
    assert excinfo.value.status_code == 429
    assert int(excinfo.value.headers["Retry-After"]) > 0


def test_forwarded_headers_are_ignored_outside_production(monkeypatch):
    # No trusted proxy in dev, so both headers are attacker-supplied: key on the
    # socket address no matter what the client claims.
    request = plain_request(host="10.0.0.1", headers={
        "x-forwarded-for": "9.9.9.9, 10.0.0.2", "x-real-ip": "9.9.9.9",
    })
    monkeypatch.setattr(config, "IS_PRODUCTION", False)
    assert client_ip(request) == "10.0.0.1"


def test_production_keys_on_x_real_ip(monkeypatch):
    # nginx sets X-Real-IP to the real peer with proxy_set_header, overwriting any
    # client-supplied value, so that is what we trust - even when a spoofed
    # X-Forwarded-For prefix is also present.
    monkeypatch.setattr(config, "IS_PRODUCTION", True)
    request = plain_request(host="127.0.0.1", headers={
        "x-real-ip": "198.51.100.4", "x-forwarded-for": "9.9.9.9, 198.51.100.4",
    })
    assert client_ip(request) == "198.51.100.4"


def test_production_falls_back_to_rightmost_forwarded_hop(monkeypatch):
    # With no X-Real-IP, use the LAST X-Forwarded-For entry - the peer nginx
    # appended via $proxy_add_x_forwarded_for - never the client-supplied prefix.
    monkeypatch.setattr(config, "IS_PRODUCTION", True)
    request = plain_request(headers={"x-forwarded-for": "9.9.9.9, 10.0.0.2, 198.51.100.4"})
    assert client_ip(request) == "198.51.100.4"


def test_spoofed_forwarded_for_cannot_mint_new_buckets(monkeypatch):
    # The point of the fix: a client cannot vary the rate-limit key by prepending a
    # fake hop to X-Forwarded-For. Every request from one real peer maps to one key,
    # whether we trust X-Real-IP or fall back to the rightmost forwarded hop.
    monkeypatch.setattr(config, "IS_PRODUCTION", True)
    with_real_ip = {
        client_ip(plain_request(headers={
            "x-real-ip": "198.51.100.4",
            "x-forwarded-for": f"10.0.0.{n}, 198.51.100.4",
        }))
        for n in range(5)
    }
    without_real_ip = {
        client_ip(plain_request(headers={"x-forwarded-for": f"10.0.0.{n}, 198.51.100.4"}))
        for n in range(5)
    }
    assert with_real_ip == {"198.51.100.4"}
    assert without_real_ip == {"198.51.100.4"}


def test_ipv6_clients_are_limited_per_64_prefix():
    """One IPv6 customer gets a whole /64; keying on the full address let them
    mint a fresh bucket per request just by changing the interface id."""
    from utils.ratelimit import rate_limit_key

    assert rate_limit_key("2001:db8:1:2:aaaa::1") == rate_limit_key("2001:db8:1:2:bbbb:cccc:dddd:9")
    assert rate_limit_key("2001:db8:1:2::1") != rate_limit_key("2001:db8:1:3::1")
    assert rate_limit_key("198.51.100.4") == "198.51.100.4"
    assert rate_limit_key("::ffff:198.51.100.4") == "198.51.100.4"
    assert rate_limit_key("unknown") == "unknown"


def test_the_limiter_dependency_keys_ipv6_by_prefix():
    limiter = RateLimiter("unit", limit=1, window_seconds=60)
    limiter(plain_request(host="2001:db8:1:2::1"))
    with pytest.raises(HTTPException):
        limiter(plain_request(host="2001:db8:1:2::ffff"))


def test_login_route_returns_429_after_the_threshold(client):
    body = {"username": "ghost", "password": PASSWORD}
    for _ in range(login_rate_limit.limit):
        assert client.post("/api/login", json=body).status_code == 401
    response = client.post("/api/login", json=body)
    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) > 0


def test_register_route_returns_429_after_the_threshold(client):
    for index in range(register_rate_limit.limit):
        response = client.post("/api/register", json={
            "username": f"user{index}", "email": f"user{index}@example.com", "password": PASSWORD})
        assert response.status_code == 200
    response = client.post("/api/register", json={
        "username": "overflow", "email": "overflow@example.com", "password": PASSWORD})
    assert response.status_code == 429


# --- routes -----------------------------------------------------------------

def test_register_validates_its_input(client):
    bad = [
        {"username": "ab", "email": "a@example.com", "password": PASSWORD},
        {"username": "has space", "email": "a@example.com", "password": PASSWORD},
        {"username": "x" * 33, "email": "a@example.com", "password": PASSWORD},
        {"username": "valid", "email": "not-an-email", "password": PASSWORD},
        {"username": "valid", "email": "a@example.com", "password": "short"},
        {"username": "valid", "email": "a@example.com",
         "password": "x" * (passwords.MAX_PASSWORD_LENGTH + 1)},
    ]
    for payload in bad:
        # the limiter deliberately counts rejected attempts too, so clear it here
        register_rate_limit.reset()
        assert client.post("/api/register", json=payload).status_code == 422


def test_login_sets_a_usable_cookie_and_me_hides_credentials(client):
    assert client.post("/api/register", json={
        "username": "routed", "email": "routed@example.com", "password": PASSWORD}).status_code == 200

    response = client.post("/api/login", json={"username": "routed", "password": PASSWORD})
    assert response.status_code == 200
    cookie = response.headers["set-cookie"]
    assert f"Max-Age={jwt_utils.SESSION_COOKIE_MAX_AGE_SECONDS}" in cookie
    assert "HttpOnly" in cookie and "Path=/" in cookie and "samesite=lax" in cookie.lower()
    assert "Secure" not in cookie  # ENVIRONMENT is not production in tests

    me = client.get("/api/users/me")
    assert me.status_code == 200
    assert me.json()["username"] == "routed"
    assert "password_hash" not in me.json() and "password_salt" not in me.json()

    logout = client.post("/api/users/logout")
    assert "Path=/" in logout.headers["set-cookie"]
    assert client.get("/api/users/me").status_code == 401


# --- signing in with an email address ----------------------------------------

def test_login_accepts_the_accounts_email_address(tmp_db):
    assert register("mailer", "Mailer@Example.com")

    user = login("mailer@example.com")
    assert user is not None and user.username == "mailer"
    assert login("MAILER@EXAMPLE.COM").username == "mailer"
    assert login("mailer@example.com", "not the password") is None


def test_login_by_an_unknown_email_costs_a_verify(tmp_db, monkeypatch):
    spent = []
    monkeypatch.setattr(database, "spend_a_verify", lambda password: spent.append(password))
    assert login("nobody@example.com") is None
    assert spent == [PASSWORD]


def test_login_by_email_refuses_an_address_shared_by_several_accounts(tmp_db, caplog):
    """Where historical duplicates blocked the unique index, guessing which account
    the address meant could sign someone into the wrong one."""
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP INDEX idx_users_email_unique")
    conn.commit()
    conn.close()
    assert register("first", "shared@example.com")
    assert register("second", "Shared@Example.com")

    assert login("shared@example.com") is None
    assert "2 accounts share" in caplog.text
    assert login("first").username == "first"


def test_a_username_is_matched_before_an_email(tmp_db):
    """Rows older than the username pattern may hold an "@"; that name must still
    reach its own account, not the one registered with it as an address."""
    insert_legacy_user(tmp_db, "odd@example.com", PASSWORD, email="odd-owner@example.com")
    assert register("other", "odd@example.com")

    assert login("odd@example.com").username == "odd@example.com"


def test_login_accepts_an_email_longer_than_any_username(tmp_db):
    address = "a-rather-long-mailbox-name-for-a-person@" + "sub." * 8 + "example.com"
    assert len(address) > 64
    assert register("longmail", address)
    assert login(address).username == "longmail"


def test_login_route_accepts_an_email_and_the_session_names_the_username(client):
    assert client.post("/api/register", json={
        "username": "byemail", "email": "byemail@example.com", "password": PASSWORD}).status_code == 200

    response = client.post("/api/login", json={"username": " ByEmail@example.com ", "password": PASSWORD})
    assert response.status_code == 200
    assert client.get("/api/users/me").json()["username"] == "byemail"


def test_me_requires_a_valid_cookie(client):
    assert client.get("/api/users/me").status_code == 401
    client.cookies.set("token", "Bearer nonsense")
    assert client.get("/api/users/me").status_code == 401


def test_init_survives_duplicate_emails_already_in_the_database(monkeypatch, tmp_path, caplog):
    """The live table predates any email constraint, so this must not be fatal."""
    db_path = tmp_path / "dupe-emails.db"
    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path)
    database.init()

    conn = sqlite3.connect(db_path)
    conn.execute("DROP INDEX idx_users_email_unique")
    for name in ("one", "two"):
        conn.execute(
            "INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, '', '')",
            (name, "Shared@Example.com" if name == "one" else "shared@example.com"),
        )
    conn.commit()
    conn.close()

    database.init()  # must not raise

    indexes = {row[0] for row in sqlite3.connect(db_path).execute(
        "SELECT name FROM sqlite_master WHERE type = 'index'")}
    assert "idx_users_email_unique" not in indexes, "unique index built over duplicate data"
    assert "idx_users_email" in indexes, "lookups must stay indexed regardless"
    assert database._email_index_enforced is False
    assert any("shared@example.com" in record.getMessage() for record in caplog.records)


def test_email_uniqueness_is_enforced_once_the_index_exists(tmp_db):
    """A clean database gets the constraint, case-folded."""
    assert database._email_index_enforced is True
    assert database.register_user(database.RegisterRequest(
        username="first", email="Taken@Example.com", password="a-good-password"))
    result = database.register_user(database.RegisterRequest(
        username="second", email="taken@example.com", password="a-good-password"))
    assert not result
    # "second" is a fresh username, so only the case-folded email index can have
    # rejected this - the generic result no longer names the field.
    assert result is database.RegisterResult.TAKEN


def a_user(username="bearer-user", customer_id="cus_test"):
    return jwt_utils.UserInDB(
        username=username, email=f"{username}@example.com",
        password_hash="x", password_salt="", customer_id=customer_id,
    )


def test_authorization_header_is_accepted():
    token = jwt_utils.create_access_token(a_user(), client=None)
    data = jwt_utils.get_token_data(header_request(f"Bearer {token}"))
    assert data.username == "bearer-user"


def test_authorization_header_is_case_insensitive():
    token = jwt_utils.create_access_token(a_user(), client=None)
    data = jwt_utils.get_token_data(header_request(f"bearer {token}"))
    assert data.username == "bearer-user"


def test_cookie_still_works_and_takes_precedence():
    """The web client must be unaffected, and a stale header must not override it."""
    cookie_token = jwt_utils.create_access_token(a_user("from-cookie"), client=None)
    request = Request({
        "type": "http", "http_version": "1.1", "method": "GET", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "root_path": "",
        "headers": [(b"cookie", f"token=Bearer {cookie_token}".encode()),
                    (b"authorization", b"Bearer garbage")],
        "client": ("203.0.113.7", 40000), "server": ("testserver", 80),
    })
    assert jwt_utils.get_token_data(request).username == "from-cookie"


def test_app_token_in_the_header_is_still_rejected():
    """A token lifted out of an APK must not become a session."""
    token_data = jwt_utils.TokenData(
        username="victim", email="v@example.com", customer_id="cus_1",
        exp=datetime.now(timezone.utc) + timedelta(days=1),
    )
    app_token = jwt_utils.create_app_jwt(token_data, "com.whatsapp")
    with pytest.raises(HTTPException):
        jwt_utils.get_token_data(header_request(f"Bearer {app_token}"))


def test_missing_credentials_still_raise():
    with pytest.raises(HTTPException):
        jwt_utils.get_token_data(header_request(""))


def test_create_access_token_honours_an_explicit_ttl():
    long = jwt_utils.create_access_token(a_user(), expires_in=timedelta(days=30), client=None)
    short = jwt_utils.create_access_token(a_user(), client=None)
    long_exp = jwt.decode(long, jwt_utils.SECRET_KEY, algorithms=[jwt_utils.ALGORITHM])["exp"]
    short_exp = jwt.decode(short, jwt_utils.SECRET_KEY, algorithms=[jwt_utils.ALGORITHM])["exp"]
    assert long_exp - short_exp > timedelta(days=29).total_seconds()


# --- CORS origin defaults ---------------------------------------------------

def test_localhost_is_not_a_default_cors_origin_in_production():
    """A credentialed allowlist must not silently trust the dev origin in prod."""
    prod = config._cors_origins(is_production=True, configured=None)
    assert "http://localhost:5173" not in prod
    assert "https://androidmod.site" in prod


def test_localhost_is_a_default_cors_origin_off_production():
    dev = config._cors_origins(is_production=False, configured=None)
    assert "http://localhost:5173" in dev


def test_explicit_cors_origins_win_over_the_default_in_production():
    origins = config._cors_origins(is_production=True, configured="https://a.example,https://b.example")
    assert origins == ("https://a.example", "https://b.example")
    assert "*" not in origins  # allow_credentials=True forbids a wildcard


# --- manage.py column allowlist ---------------------------------------------

def test_manage_duplicates_rejects_columns_outside_the_allowlist():
    """The interpolated column is whitelisted so the pattern is safe if reused."""
    import manage

    with pytest.raises(ValueError):
        manage._duplicates(None, "username; DROP TABLE users")
