"""Only this site's Stripe product counts.

The Stripe account also sells another product, and its customers carry the
same kind of addresses ours do. A subscription to that product must never
unlock anything here, must never make one of its customers ours, and our
username tag must never be written onto a customer of that product. Stripe is
always the in-memory FakeStripe from conftest.
"""
import argparse
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from starlette.testclient import TestClient

import database
import manage
from api import apps
from utils import jwt_utils, stripe_utils

PASSWORD = "correct horse battery"


def register(username="alice", email=None, verified=True, customer_id=None):
    assert database.register_user(database.RegisterRequest(
        username=username, email=email or f"{username}@example.com", password=PASSWORD))
    if verified:
        user = database.get_user(username)
        assert database.mark_email_verified(username, user.email, datetime.now(timezone.utc))
    if customer_id:
        assert database.update_user_customer_id("username", username, customer_id)


def linked(username="alice"):
    return database.get_user(username).customer_id


def resolve(username="alice", **kwargs):
    return stripe_utils.resolve_customer_id(username, **kwargs)


def their_customer(fake_stripe, customer_id="cus_theirs", email="alice@example.com", status="active"):
    """A customer of the other product: untagged, with the account's address."""
    fake_stripe.add_customer(customer_id, email)
    fake_stripe.add_subscription(customer_id, status, product=fake_stripe.other_product_id)


@pytest.fixture
def client(tmp_db, fake_stripe):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


def sign_in(client, username="alice"):
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200


@pytest.fixture
def enqueued(monkeypatch, patched_apps_dir):
    version_dir = patched_apps_dir / "com.whatsapp" / "1.0"
    version_dir.mkdir(parents=True)
    (version_dir / "patched_com.whatsapp-1.0.apk").write_bytes(b"PK")
    jobs = []

    def _enqueue(**kwargs):
        jobs.append(kwargs)
        return SimpleNamespace(job_id="job-1", cached=False, state="queued")

    monkeypatch.setattr(apps.downloads, "enqueue", _enqueue)
    return jobs


def download(client):
    return client.post("/api/downloads", json={"package": "com.whatsapp", "version": "1.0"})


# --- (1) only this product's subscriptions are read -----------------------------------

@pytest.mark.parametrize("status", ["active", "trialing"])
def test_another_products_subscription_is_no_subscription_here(tmp_db, fake_stripe, status):
    fake_stripe.add_customer("cus_x", "x@example.com")
    fake_stripe.add_subscription("cus_x", status, product=fake_stripe.other_product_id)

    assert stripe_utils.find_subscription("cus_x") is None
    assert stripe_utils.customer_subscription("cus_x") == (True, None)
    assert stripe_utils.get_subscription_status("cus_x") == "free"


def test_a_customer_with_both_products_is_judged_on_this_products_subscription(tmp_db, fake_stripe):
    fake_stripe.add_customer("cus_x", "x@example.com")
    ours = fake_stripe.add_subscription("cus_x", "canceled")
    fake_stripe.add_subscription("cus_x", "active", product=fake_stripe.other_product_id)

    assert stripe_utils.find_subscription("cus_x")["id"] == ours["id"]
    assert stripe_utils.get_subscription_status("cus_x") == "canceled"


def test_this_products_trial_counts_beside_another_products_lapsed_one(tmp_db, fake_stripe):
    fake_stripe.add_customer("cus_x", "x@example.com")
    ours = fake_stripe.add_subscription("cus_x", "trialing")
    fake_stripe.add_subscription("cus_x", "canceled", product=fake_stripe.other_product_id)

    assert stripe_utils.find_subscription("cus_x")["id"] == ours["id"]


def test_an_expanded_price_product_is_recognised(tmp_db, fake_stripe):
    fake_stripe.add_customer("cus_x", "x@example.com")
    ours = fake_stripe.add_subscription("cus_x", "active", expanded_product=True)
    fake_stripe.add_subscription("cus_x", "active", product=fake_stripe.other_product_id,
                                 expanded_product=True)

    assert stripe_utils.find_subscription("cus_x")["id"] == ours["id"]


def test_this_products_subscription_is_found_past_the_first_page(tmp_db, fake_stripe):
    """The other product's customers can hold dozens of subscriptions
    (incomplete_expired ones pile up); ours may be older than all of them."""
    fake_stripe.add_customer("cus_x", "x@example.com")
    ours = fake_stripe.add_subscription("cus_x", "active")
    for _ in range(150):
        fake_stripe.add_subscription("cus_x", "incomplete_expired", product=fake_stripe.other_product_id)

    assert stripe_utils.find_subscription("cus_x")["id"] == ours["id"]


def test_another_products_subscription_gives_no_access_to_a_linked_account(client, fake_stripe, enqueued):
    register(customer_id="cus_theirs")
    their_customer(fake_stripe)
    sign_in(client)

    assert client.get("/api/subscription").json() == {"subscription": None}
    assert download(client).status_code == 402
    assert enqueued == []


def test_another_products_subscription_does_not_unlock_a_patched_app(client, fake_stripe):
    register(customer_id="cus_theirs")
    their_customer(fake_stripe)
    user = database.get_user("alice")
    app_token = jwt_utils.create_app_jwt(
        jwt_utils.TokenData(username="alice", exp=datetime.now(timezone.utc) + timedelta(days=1)),
        "com.whatsapp", customer_id=user.customer_id)

    response = client.post("/api/subscription/is_device_allowed",
                           data={"token": app_token, "device": "phone1", "sku": "sku"})

    assert response.status_code == 402


@pytest.mark.parametrize("path", ["/api/subscription/cancel", "/api/subscription/reactivate"])
def test_another_products_subscription_cannot_be_cancelled_or_reactivated_here(client, fake_stripe, path):
    register(customer_id="cus_theirs")
    their_customer(fake_stripe)
    sign_in(client)

    assert client.post(path).status_code == 404
    assert fake_stripe.called("subscriptions.update") == []


def test_the_subscription_page_shows_this_products_plan(client, fake_stripe):
    register(customer_id="cus_both")
    fake_stripe.add_customer("cus_both", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_both", "trialing")
    fake_stripe.add_subscription("cus_both", "active", product=fake_stripe.other_product_id)
    sign_in(client)

    assert client.get("/api/subscription").json()["subscription"]["status"] == "trialing"


# --- (2) email linking needs this product's live subscription ------------------------------

@pytest.mark.parametrize("status", ["active", "trialing", "past_due"])
def test_another_products_customer_is_never_linked_or_tagged_by_email(tmp_db, fake_stripe, status):
    register()
    their_customer(fake_stripe, status=status)

    assert resolve() is None
    assert resolve(strict=True) is None
    assert linked() is None
    assert fake_stripe.customers["cus_theirs"]["metadata"] == {}
    assert fake_stripe.called("customers.update") == []


@pytest.mark.parametrize("status", [None, "canceled", "past_due", "incomplete_expired"])
def test_a_dashboard_customer_without_a_live_subscription_here_is_not_linked(tmp_db, fake_stripe, status):
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    if status:
        fake_stripe.add_subscription("cus_dash", status)

    assert resolve(strict=True) is None
    assert linked() is None
    assert fake_stripe.called("customers.update") == []


def test_a_dashboard_trial_on_this_product_still_links(tmp_db, fake_stripe):
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")

    assert resolve() == "cus_dash"
    assert linked() == "cus_dash"
    assert fake_stripe.customers["cus_dash"]["metadata"] == {"username": "alice"}


def test_this_products_customer_wins_over_a_newer_customer_of_the_other_product(tmp_db, fake_stripe):
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")
    their_customer(fake_stripe)

    assert resolve() == "cus_dash"
    assert fake_stripe.customers["cus_theirs"]["metadata"] == {}


def test_a_customer_shared_with_the_other_product_is_linked_but_never_tagged(tmp_db, fake_stripe):
    """Our trial on a customer that also pays for the other product: the account
    may use it, but the other product's backend may read metadata.username, so
    our tag is not written there. The link lives in users.customer_id alone."""
    register()
    fake_stripe.add_customer("cus_shared", "alice@example.com")
    fake_stripe.add_subscription("cus_shared", "active", product=fake_stripe.other_product_id)
    fake_stripe.add_subscription("cus_shared", "trialing")

    assert resolve() == "cus_shared"
    assert linked() == "cus_shared"
    assert fake_stripe.customers["cus_shared"]["metadata"] == {}
    assert fake_stripe.called("customers.update") == []


def test_a_tagged_customer_without_this_products_subscription_is_not_linked(tmp_db, fake_stripe):
    """The tag path keeps its rules, but nothing is linked that has never held a
    subscription to this product: that tag may be the other product's."""
    register()
    fake_stripe.add_customer("cus_tagged", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_tagged", "active", product=fake_stripe.other_product_id)

    assert resolve(strict=True) is None
    assert linked() is None


def test_a_tag_naming_someone_else_is_never_overwritten(tmp_db, fake_stripe):
    register(customer_id="cus_bobs_elsewhere")
    fake_stripe.add_customer("cus_bobs_elsewhere", "alice@example.com", username="bob")

    assert stripe_utils.link_customer(database.get_user("alice"),
                                      fake_stripe.customers["cus_bobs_elsewhere"])

    assert fake_stripe.customers["cus_bobs_elsewhere"]["metadata"] == {"username": "bob"}
    assert fake_stripe.called("customers.update") == []


# --- checkout ------------------------------------------------------------------------------

def test_another_products_subscription_does_not_trigger_the_checkout_409(client, fake_stripe):
    register()
    their_customer(fake_stripe)
    sign_in(client)

    response = client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 200
    [session] = fake_stripe.called("checkout.sessions.create")
    # A customer of our own, never theirs, and theirs is left untouched.
    assert session["customer"] != "cus_theirs"
    assert fake_stripe.called("customers.create")[0]["metadata"] == {"username": "alice"}
    assert fake_stripe.customers["cus_theirs"]["metadata"] == {}
    assert linked() is None


def test_a_linked_customers_other_product_does_not_trigger_the_checkout_409(client, fake_stripe):
    register(customer_id="cus_shared")
    fake_stripe.add_customer("cus_shared", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_shared", "active", product=fake_stripe.other_product_id)
    sign_in(client)

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 200


# --- (3) confirm_success ------------------------------------------------------------------

def test_confirm_success_does_not_link_another_products_customer(client, fake_stripe):
    register()
    their_customer(fake_stripe)
    sign_in(client)

    assert client.post("/api/subscription/confirm_success").status_code == 404
    assert linked() is None
    assert fake_stripe.customers["cus_theirs"]["metadata"] == {}


@pytest.mark.parametrize("username", [None, "alice"])
def test_confirm_success_does_not_link_a_customer_without_this_products_subscription(client, fake_stripe,
                                                                                    username):
    register()
    fake_stripe.add_customer("cus_empty", "alice@example.com", username=username)
    sign_in(client)

    assert client.post("/api/subscription/confirm_success").status_code == 404
    assert linked() is None


def test_confirm_success_links_a_trial_on_this_product(client, fake_stripe):
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")
    sign_in(client)

    assert client.post("/api/subscription/confirm_success").json() == {"message": "Subscription confirmed"}
    assert linked() == "cus_dash"


# --- (4) grant-trial ----------------------------------------------------------------------

def grant(username="alice"):
    until = (datetime.now(timezone.utc).date() + timedelta(days=30)).isoformat()
    return manage.cmd_grant_trial(argparse.Namespace(username=username, until=until, days=None, yes=True))


def test_grant_trial_never_reuses_another_products_customer(tmp_db, fake_stripe):
    register()
    their_customer(fake_stripe)

    assert grant() == 0

    [created] = fake_stripe.called("customers.create")
    assert created["metadata"] == {"username": "alice"}
    new_customer = linked()
    assert new_customer not in (None, "cus_theirs")
    assert [s["customer"] for s in fake_stripe.called("subscriptions.create")] == [new_customer]
    assert fake_stripe.customers["cus_theirs"]["metadata"] == {}


def test_another_products_subscription_does_not_block_grant_trial(tmp_db, fake_stripe):
    """The account's own linked customer, which also pays for the other product:
    the trial goes on it, and our tag still stays off it."""
    register(customer_id="cus_shared")
    fake_stripe.add_customer("cus_shared", "alice@example.com")
    fake_stripe.add_subscription("cus_shared", "active", product=fake_stripe.other_product_id)

    assert grant() == 0

    assert [s["customer"] for s in fake_stripe.called("subscriptions.create")] == ["cus_shared"]
    assert fake_stripe.customers["cus_shared"]["metadata"] == {}
