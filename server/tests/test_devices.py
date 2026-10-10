"""Device slots: GET /api/users/me/devices and the once-a-month reset."""
import sqlite3
from datetime import datetime, timedelta, timezone

import pytest
from starlette.testclient import TestClient

import config
import database
from api import payment
from utils import stripe_utils

PASSWORD = "correct horse battery"
ISO = "%Y-%m-%dT%H:%M:%SZ"


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


def signed_in(client, username="alice"):
    assert client.post("/api/register", json={
        "username": username, "email": f"{username}@example.com", "password": PASSWORD}).status_code == 200
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200
    return client


def add_devices(username, count):
    for index in range(count):
        assert database.update_user_device_fingerprints(username, f"device{index}_sku")


def stamp_reset(db_path, username, when):
    conn = sqlite3.connect(db_path)
    conn.execute("UPDATE users SET devices_reset_at = ? WHERE username = ?", (when.strftime(ISO), username))
    conn.commit()
    conn.close()


def parse(value):
    return datetime.strptime(value, ISO).replace(tzinfo=timezone.utc)


def test_devices_need_a_session(client):
    assert client.get("/api/users/me/devices").status_code == 401
    assert client.post("/api/users/me/devices/reset").status_code == 401


def test_a_fresh_account_can_reset(client):
    signed_in(client)
    add_devices("alice", 2)

    body = client.get("/api/users/me/devices").json()

    assert body == {"used": 2, "limit": config.MAX_DEVICES_ALLOWED, "last_reset_at": None,
                    "next_reset_at": None, "can_reset": True}


def test_devices_accept_a_bearer_token(client):
    signed_in(client)
    token = client.post("/api/auth/token", json={"username": "alice", "password": PASSWORD}).json()["access_token"]
    client.cookies.clear()

    response = client.get("/api/users/me/devices", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    assert response.json()["used"] == 0


def test_reset_clears_every_slot_and_starts_the_clock(client):
    signed_in(client)
    add_devices("alice", config.MAX_DEVICES_ALLOWED)
    before = datetime.now(timezone.utc).replace(microsecond=0)

    response = client.post("/api/users/me/devices/reset")

    assert response.status_code == 200
    body = response.json()
    assert body["used"] == 0
    assert body["limit"] == config.MAX_DEVICES_ALLOWED
    assert body["can_reset"] is False
    last = parse(body["last_reset_at"])
    assert before <= last <= datetime.now(timezone.utc)
    assert parse(body["next_reset_at"]) == last + timedelta(days=30)
    assert database.get_user("alice").device_fingerprints == []
    assert client.get("/api/users/me/devices").json() == body


def test_a_second_reset_inside_30_days_is_refused_with_the_date(client):
    signed_in(client)
    first = client.post("/api/users/me/devices/reset").json()
    add_devices("alice", 1)

    response = client.post("/api/users/me/devices/reset")

    assert response.status_code == 429
    next_at = parse(first["next_reset_at"])
    assert response.json() == {
        "detail": f"You can reset your devices again on {next_at:%B} {next_at.day}, {next_at.year}."}
    assert int(response.headers["Retry-After"]) > 29 * 24 * 3600
    assert len(database.get_user("alice").device_fingerprints) == 1, "a refused reset must not clear anything"


def test_the_refusal_message_matches_the_contract_format(client, tmp_db):
    signed_in(client)
    stamp_reset(tmp_db, "alice", datetime.now(timezone.utc) - timedelta(days=1))
    next_at = datetime.now(timezone.utc) - timedelta(days=1) + timedelta(days=30)

    detail = client.post("/api/users/me/devices/reset").json()["detail"]

    assert detail.startswith("You can reset your devices again on ")
    assert detail.endswith(f"{next_at.day}, {next_at.year}.")


def test_a_reset_is_allowed_again_after_30_days(client, tmp_db):
    signed_in(client)
    stamp_reset(tmp_db, "alice", datetime.now(timezone.utc) - timedelta(days=31))
    add_devices("alice", 2)

    state = client.get("/api/users/me/devices").json()
    assert state["can_reset"] is True
    assert state["next_reset_at"] is None
    assert state["last_reset_at"] is not None

    assert client.post("/api/users/me/devices/reset").status_code == 200
    assert database.get_user("alice").device_fingerprints == []


def test_a_reset_drops_the_cached_subscription_status(client):
    signed_in(client)
    database.update_user_customer_id("username", "alice", "cus_alice")
    stripe_utils._status_cache.put("cus_alice", "active", stripe_utils._status_cache.generation())

    assert client.post("/api/users/me/devices/reset").status_code == 200
    assert stripe_utils._status_cache.get("cus_alice") is None


def test_the_device_limit_message_points_at_the_reset(monkeypatch):
    assert f"{config.SITE_HOST}/subscription" in payment.DEVICE_LIMIT_MESSAGE
    assert "once every 30 days" in payment.DEVICE_LIMIT_MESSAGE
    assert str(config.MAX_DEVICES_ALLOWED) in payment.DEVICE_LIMIT_MESSAGE


# --- migration ------------------------------------------------------------------

def _pre_devices_db(path):
    """The users table as it stands on the live host before this change."""
    conn = sqlite3.connect(path)
    conn.execute("""CREATE TABLE users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL, email TEXT NOT NULL,
                        password_hash TEXT NOT NULL, password_salt TEXT NOT NULL,
                        password_scheme TEXT NOT NULL DEFAULT 'legacy',
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        customer_id TEXT DEFAULT NULL,
                        device_fingerprints TEXT DEFAULT "[]")""")
    conn.execute("INSERT INTO users (username, email, password_hash, password_salt, device_fingerprints) "
                 "VALUES ('live', 'live@example.com', 'x', 'y', '[\"a_sku\"]')")
    conn.commit()
    conn.close()


def _columns(path):
    return {row[1] for row in sqlite3.connect(path).execute("PRAGMA table_info(users)")}


def test_migrate_adds_the_reset_column_idempotently(monkeypatch, tmp_path):
    db_path = tmp_path / "live.db"
    _pre_devices_db(db_path)
    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path)

    database.init()
    database.init()

    assert "devices_reset_at" in _columns(db_path)
    user = database.get_user("live")
    assert user.devices_reset_at is None
    assert user.device_fingerprints == ["a_sku"]


def test_check_reports_the_column_without_adding_it(monkeypatch, tmp_path, capsys):
    import manage

    db_path = tmp_path / "live.db"
    _pre_devices_db(db_path)
    monkeypatch.setattr(config, "DB_PATH", db_path)

    manage.cmd_check(None)

    assert "devices_reset_at" in capsys.readouterr().out
    assert "devices_reset_at" not in _columns(db_path), "check must stay read-only"
