"""Changing the password while signed in: POST /api/users/me/password."""
import sqlite3

import pytest
from starlette.testclient import TestClient

import database
from api import users
from utils import jwt_utils, passwords, ratelimit

PASSWORD = "correct horse battery"
NEW_PASSWORD = "a brand new passphrase"


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


def change(client, current=PASSWORD, new=NEW_PASSWORD, **kwargs):
    return client.post("/api/users/me/password",
                       json={"current_password": current, "new_password": new}, **kwargs)


def login(username, password):
    return database.login_user(database.LoginRequest(username=username, password=password))


def insert_legacy_user(db_path, username, password):
    """A row written the way the pre-Argon2 code wrote it (see test_auth.py)."""
    salt = passwords.new_salt()
    conn = sqlite3.connect(db_path)
    conn.execute(
        "INSERT INTO users (username, email, password_hash, password_salt, password_scheme) "
        "VALUES (?, ?, ?, ?, ?)",
        (username, f"{username}@example.com", passwords.legacy_hash(password, salt), salt, passwords.SCHEME_LEGACY),
    )
    conn.commit()
    conn.close()


def test_changing_the_password_needs_a_session(client):
    assert change(client).status_code == 401


def test_a_change_replaces_the_password(client):
    signed_in(client)

    response = change(client)

    assert response.status_code == 200
    assert response.json() == {"message": "Your password has been changed."}
    assert login("alice", NEW_PASSWORD) is not None
    assert login("alice", PASSWORD) is None
    assert database.get_user("alice").password_scheme == passwords.SCHEME_ARGON2


def test_a_wrong_current_password_is_refused_and_changes_nothing(client):
    signed_in(client)

    response = change(client, current="not my password")

    # 400, not 401: the web client reads any 401 as an expired session and
    # sends the user to the login page.
    assert response.status_code == 400
    assert response.json() == {"detail": "Your current password is incorrect."}
    assert login("alice", PASSWORD) is not None
    assert login("alice", NEW_PASSWORD) is None


def test_the_new_password_is_validated_like_registration(client):
    signed_in(client)

    assert change(client, new="short").status_code == 422
    assert change(client, new="x" * (passwords.MAX_PASSWORD_LENGTH + 1)).status_code == 422
    assert login("alice", PASSWORD) is not None


def test_the_new_password_must_differ_from_the_current_one(client):
    signed_in(client)

    response = change(client, new=PASSWORD)

    assert response.status_code == 400
    assert response.json() == {"detail": "Choose a new password that is different from your current one."}


def test_a_legacy_account_can_change_its_password(client, tmp_db):
    # Signing in through /login would upgrade the hash first, so the session is
    # minted directly: a token issued before the Argon2 migration looks like this.
    insert_legacy_user(tmp_db, "olduser", PASSWORD)
    token = jwt_utils.create_access_token(database.get_user("olduser"), client=None)

    response = change(client, headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    assert login("olduser", NEW_PASSWORD) is not None
    assert database.get_user("olduser").password_scheme == passwords.SCHEME_ARGON2


def test_a_change_does_not_verify_the_email_address(client):
    signed_in(client)
    assert database.get_user("alice").email_verified_at is None

    assert change(client).status_code == 200

    assert database.get_user("alice").email_verified_at is None


def test_a_change_kills_a_reset_link_sent_before_it(client):
    signed_in(client)
    link_token = jwt_utils.create_password_reset_token(database.get_user("alice"))

    assert change(client).status_code == 200
    reset = client.post("/api/auth/reset-password", json={"token": link_token, "password": "a third passphrase"})

    assert reset.status_code == 400
    assert login("alice", NEW_PASSWORD) is not None


def test_the_session_that_changed_it_stays_signed_in(client):
    signed_in(client)

    assert change(client).status_code == 200

    assert client.get("/api/users/me").status_code == 200


def test_a_change_that_loses_a_race_is_a_conflict(client, monkeypatch):
    """Another change landing between the row being read and the write wins."""
    signed_in(client)
    real_verify = passwords.verify_password

    def verify_then_lose_the_race(password, stored_hash, salt, scheme):
        assert database.set_password(database.get_user("alice"), "the other change")
        return real_verify(password, stored_hash, salt, scheme)

    monkeypatch.setattr(users, "verify_password", verify_then_lose_the_race)

    response = change(client)

    assert response.status_code == 409
    assert login("alice", "the other change") is not None
    assert login("alice", NEW_PASSWORD) is None


def test_changing_the_password_is_rate_limited_per_ip(client):
    signed_in(client)

    for _ in range(ratelimit.change_password_rate_limit.limit):
        assert change(client, current="a wrong guess").status_code == 400
    response = change(client)

    assert response.status_code == 429
    assert login("alice", PASSWORD) is not None
