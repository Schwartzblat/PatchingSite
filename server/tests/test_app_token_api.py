"""POST /api/app-token: the paywall token an on-device build carries."""
import pytest
from starlette.testclient import TestClient

import config
from utils import jwt_utils, ratelimit
from tests.test_patch_read_api import bearer, make_user

PKG = "com.whatsapp"


@pytest.fixture
def client(tmp_db, fake_stripe):
    import main

    fake_stripe.add_customer("cus_paid", "paid@example.com")
    fake_stripe.add_subscription("cus_paid", "active")
    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def paid(client):
    return bearer(make_user("alice", "cus_paid"))


def mint(client, headers, package=PKG):
    return client.post("/api/app-token", json={"package": package}, headers=headers)


def test_app_token_needs_subscription(client):
    assert mint(client, bearer(make_user("bob"))).status_code == 402
    assert client.post("/api/app-token", json={"package": PKG}).status_code == 401


def test_app_token_round_trips(client, paid):
    r = mint(client, paid)
    assert r.status_code == 200
    data = jwt_utils.get_app_token_data(r.json()["token"])
    assert data.username == "alice"
    assert data.package_name == PKG
    assert data.customer_id == "cus_paid"
    assert data.typ == "app"


def test_app_token_unknown_package(client, paid):
    assert mint(client, paid, "com.not.real").status_code == 404


def test_app_token_rejects_extra_fields(client, paid):
    r = client.post("/api/app-token", json={"package": PKG, "x": 1}, headers=paid)
    assert r.status_code == 422


def test_app_token_accepted_by_is_device_allowed(client, paid):
    token = mint(client, paid).json()["token"]
    r = client.post("/api/subscription/is_device_allowed",
                    data={"token": token, "device": "dev1", "sku": "phone"})
    assert r.status_code == 200
    assert r.json() == {"allowed": True}


def test_app_token_rate_limited_per_account(client, paid, monkeypatch):
    monkeypatch.setattr(ratelimit.app_token_rate_limit, "limit", 2)
    assert mint(client, paid).status_code == 200
    assert mint(client, paid).status_code == 200
    r = mint(client, paid)
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) >= 1
