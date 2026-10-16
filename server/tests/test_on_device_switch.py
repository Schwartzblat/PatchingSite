"""A package listed in ON_DEVICE_PACKAGES is patched on the phone, not built here."""
from datetime import datetime, timezone

import pytest
from starlette.testclient import TestClient

import config
import database

PASSWORD = "correct horse battery"


@pytest.fixture
def client(tmp_db, fake_stripe):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def build(patched_apps_dir):
    version_dir = patched_apps_dir / "com.tranzmate" / "1.0"
    version_dir.mkdir(parents=True)
    (version_dir / "patched_com.tranzmate-1.0.apk").write_bytes(b"PK")


def on_device(monkeypatch, *packages):
    monkeypatch.setattr(config, "ON_DEVICE_PACKAGES", frozenset(packages))


def by_package(client):
    return {item["package"]: item for item in client.get("/api/apps").json()}


def test_apps_marks_on_device(client, monkeypatch):
    on_device(monkeypatch, "com.whatsapp")
    items = by_package(client)
    assert items["com.whatsapp"]["on_device"] is True
    assert items["com.tranzmate"]["on_device"] is False


def test_list_is_empty_for_on_device_package(client, monkeypatch, build):
    on_device(monkeypatch, "com.whatsapp")
    # No build directory for WhatsApp at all: still an empty list, not a 404.
    response = client.get("/api/list/com.whatsapp")
    assert response.status_code == 200
    assert response.json() == []
    other = client.get("/api/list/com.tranzmate")
    assert [item["version"] for item in other.json()] == ["1.0"]


def test_download_refused_with_410(client, monkeypatch, fake_stripe):
    on_device(monkeypatch, "com.whatsapp")
    assert database.register_user(database.RegisterRequest(
        username="alice", email="alice@example.com", password=PASSWORD))
    assert database.mark_email_verified("alice", "alice@example.com", datetime.now(timezone.utc))
    assert database.update_user_customer_id("username", "alice", "cus_ours")
    fake_stripe.add_customer("cus_ours", "alice@example.com")
    fake_stripe.add_subscription("cus_ours", "active")
    assert client.post("/api/login", json={"username": "alice", "password": PASSWORD}).status_code == 200
    response = client.post("/api/downloads", json={"package": "com.whatsapp", "version": "1.0"})
    assert response.status_code == 410
    assert response.json() == {
        "detail": "This app is now patched on your phone. Open the androidmod store app to install it."}


def test_default_changes_nothing(client, build):
    assert config.ON_DEVICE_PACKAGES == frozenset()
    assert by_package(client)["com.whatsapp"]["on_device"] is False
    assert client.get("/api/list/com.tranzmate").json() != []
    assert client.get("/api/list/com.whatsapp").status_code == 404
