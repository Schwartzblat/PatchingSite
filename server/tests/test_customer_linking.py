"""Which Stripe customer is an account's: stripe_utils.resolve_customer_id, and
every endpoint that asks it instead of the session token.

A customer made by hand in the Stripe dashboard (a free trial, say) carries the
account's address but none of the metadata checkout stamps, so nothing used to
link it. These tests pin how such a customer is linked now, and above all when
it must not be. Stripe is always the in-memory FakeStripe from conftest.
"""
import sqlite3
from datetime import datetime, timezone
from types import SimpleNamespace

import jwt
import pytest
import stripe
from starlette.exceptions import HTTPException
from starlette.testclient import TestClient

import database
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
    return database.get_user(username)


def linked(username="alice"):
    return database.get_user(username).customer_id


def resolve(username="alice", **kwargs):
    return stripe_utils.resolve_customer_id(username, **kwargs)


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


# --- the resolver -------------------------------------------------------------------

def test_the_row_answers_without_asking_stripe(tmp_db, fake_stripe):
    register(customer_id="cus_linked")

    assert resolve() == "cus_linked"
    assert fake_stripe.calls == []


def dashboard_trial(fake_stripe, customer_id="cus_dash", email="alice@example.com", username=None,
                    status="trialing"):
    """A customer holding a subscription to this site's product, as the owner makes one."""
    fake_stripe.add_customer(customer_id, email, username=username)
    return fake_stripe.add_subscription(customer_id, status)


def test_a_customer_tagged_with_the_username_is_linked(tmp_db, fake_stripe):
    """The metadata checkout stamps is trusted as before, verified address or
    not, once the customer holds a subscription to this product (any status)."""
    register(verified=False)
    dashboard_trial(fake_stripe, "cus_tagged", username="alice", status="canceled")

    assert resolve() == "cus_tagged"
    assert linked() == "cus_tagged"
    assert fake_stripe.called("customers.update") == []


def test_a_dashboard_customer_is_linked_by_the_verified_address_and_tagged(tmp_db, fake_stripe):
    register()
    dashboard_trial(fake_stripe)

    assert resolve() == "cus_dash"
    assert linked() == "cus_dash"
    # Tagged, so the link is Stripe's too: _reconcile_user and checkout's
    # metadata lookup both find it from now on.
    assert fake_stripe.customers["cus_dash"]["metadata"] == {"username": "alice"}


def test_the_address_matches_whatever_its_case(tmp_db, fake_stripe):
    register(email="Alice@Example.com")
    dashboard_trial(fake_stripe, email="alice@EXAMPLE.COM")

    assert resolve() == "cus_dash"


def test_an_unverified_account_is_never_linked_by_email(tmp_db, fake_stripe):
    """Registration proves nothing about the address. Anyone could sign up with
    someone else's and inherit the trial the owner made for them."""
    register(verified=False)
    fake_stripe.add_customer("cus_victim", "alice@example.com")
    fake_stripe.add_subscription("cus_victim", "trialing")

    assert resolve() is None
    assert resolve(strict=True) is None
    assert linked() is None
    assert fake_stripe.customers["cus_victim"]["metadata"] == {}
    assert fake_stripe.called("customers.update") == []


def test_a_customer_linked_to_another_account_is_never_taken(tmp_db, fake_stripe):
    register("bob", customer_id="cus_bobs")
    register()
    # Bob's customer, untagged, now carrying alice's address (edited in the dashboard, say).
    fake_stripe.add_customer("cus_bobs", "alice@example.com")
    fake_stripe.add_subscription("cus_bobs", "active")

    assert resolve() is None
    assert resolve(strict=True) is None
    assert linked() is None
    assert linked("bob") == "cus_bobs"
    assert fake_stripe.customers["cus_bobs"]["metadata"] == {}


def test_a_customer_tagged_with_another_username_is_never_taken(tmp_db, fake_stripe):
    register()
    fake_stripe.add_customer("cus_bobs", "alice@example.com", username="bob")
    fake_stripe.add_subscription("cus_bobs", "active")

    assert resolve() is None
    assert resolve(strict=True) is None
    assert linked() is None
    assert fake_stripe.customers["cus_bobs"]["metadata"] == {"username": "bob"}


def test_an_address_two_verified_accounts_share_links_neither_by_email(tmp_db, fake_stripe):
    """The live table holds historical duplicate addresses. Handing the customer
    to whichever of them asks first would be a guess."""
    conn = sqlite3.connect(tmp_db)
    conn.execute("DROP INDEX idx_users_email_unique")
    conn.commit()
    conn.close()
    register("alice", email="shared@example.com")
    register("alice2", email="shared@example.com")
    fake_stripe.add_customer("cus_dash", "shared@example.com")

    assert resolve("alice") is None
    assert resolve("alice2") is None
    assert fake_stripe.customers["cus_dash"]["metadata"] == {}


def test_a_live_subscription_beats_a_newer_customer_without_one(tmp_db, fake_stripe):
    register()
    fake_stripe.add_customer("cus_trial", "alice@example.com")
    fake_stripe.add_subscription("cus_trial", "trialing")
    fake_stripe.add_customer("cus_newer", "alice@example.com")

    assert resolve() == "cus_trial"


def test_among_several_live_customers_the_newest_wins(tmp_db, fake_stripe):
    register()
    dashboard_trial(fake_stripe, "cus_old", status="active")
    dashboard_trial(fake_stripe, "cus_new")

    assert resolve() == "cus_new"


def test_the_username_tag_beats_an_address_match(tmp_db, fake_stripe):
    register()
    dashboard_trial(fake_stripe, "cus_dash")
    dashboard_trial(fake_stripe, "cus_tagged", username="alice", status="canceled")

    assert resolve() == "cus_tagged"


def test_an_account_that_no_longer_exists_is_a_401(tmp_db, fake_stripe):
    with pytest.raises(HTTPException) as excinfo:
        resolve("ghost")
    assert excinfo.value.status_code == 401


# --- remembering a miss -------------------------------------------------------------

def test_finding_nothing_is_remembered_for_a_minute(tmp_db, fake_stripe, monkeypatch):
    clock = FakeClock()
    monkeypatch.setattr(stripe_utils._no_customer_cache, "_clock", clock)
    register()

    assert resolve() is None
    asked = len(fake_stripe.calls)
    assert asked > 0
    assert resolve() is None
    assert len(fake_stripe.calls) == asked

    dashboard_trial(fake_stripe)
    clock.now += stripe_utils.CUSTOMER_LOOKUP_TTL_SECONDS + 1
    assert resolve() == "cus_dash"


def test_a_strict_lookup_ignores_a_remembered_miss(tmp_db, fake_stripe):
    register()
    assert resolve() is None
    dashboard_trial(fake_stripe)

    assert resolve(strict=True) == "cus_dash"


def test_verifying_the_address_ignores_a_remembered_miss(tmp_db, fake_stripe):
    register(verified=False)
    dashboard_trial(fake_stripe)
    assert resolve() is None

    assert database.mark_email_verified("alice", "alice@example.com", datetime.now(timezone.utc))

    assert resolve() == "cus_dash"


def test_the_remembered_misses_are_bounded():
    assert 0 < stripe_utils._no_customer_cache.max_entries <= 100_000
    assert stripe_utils.CUSTOMER_LOOKUP_TTL_SECONDS <= 120


# --- Stripe failing -----------------------------------------------------------------

def test_an_outage_reads_as_no_customer_and_is_not_asked_again_at_once(tmp_db, fake_stripe):
    """A page load must not become a 503, nor cost every request Stripe's timeout."""
    register()
    fake_stripe.fail("customers.list", stripe.APIConnectionError("down"))

    assert resolve() is None
    asked = len(fake_stripe.calls)
    assert resolve() is None
    assert len(fake_stripe.calls) == asked


def test_a_strict_lookup_turns_an_outage_into_a_clean_503(tmp_db, fake_stripe):
    register()
    fake_stripe.fail("customers.list", stripe.APIConnectionError("secret-internal-host down"))

    with pytest.raises(HTTPException) as excinfo:
        resolve(strict=True)
    assert excinfo.value.status_code == 503
    assert "secret-internal-host" not in excinfo.value.detail


def test_a_customer_that_cannot_be_tagged_is_not_linked(tmp_db, fake_stripe):
    """The tag is what makes the link permanent on Stripe's side, so no tag, no link.
    The next lookup simply tries again."""
    register()
    dashboard_trial(fake_stripe)
    fake_stripe.fail("customers.update", stripe.APIConnectionError("down"))

    assert resolve() is None
    assert linked() is None
    with pytest.raises(HTTPException) as excinfo:
        resolve(strict=True)
    assert excinfo.value.status_code == 503


def test_the_case_insensitive_search_is_best_effort(tmp_db, fake_stripe):
    """Search only adds a differently-cased address. If it fails (an outage, or
    a key without access to it), the exact-case list still links, strictly too."""
    register()
    dashboard_trial(fake_stripe)
    fake_stripe.fail("customers.search", stripe.PermissionError("this key cannot search"))

    assert resolve(strict=True) == "cus_dash"


# --- the endpoints ------------------------------------------------------------------

@pytest.fixture
def client(tmp_db, fake_stripe):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


def sign_in(client, username="alice"):
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200


def a_dashboard_trial(fake_stripe, customer_id="cus_dash", email="alice@example.com", username=None):
    fake_stripe.add_customer(customer_id, email, username=username)
    return fake_stripe.add_subscription(customer_id, "trialing")


def test_opening_the_subscription_page_links_a_dashboard_trial(client, fake_stripe):
    """The point of the change: no client update, and no signing in again."""
    register()
    sign_in(client)
    a_dashboard_trial(fake_stripe)

    body = client.get("/api/subscription").json()

    assert body["subscription"]["status"] == "trialing"
    assert linked() == "cus_dash"


def test_an_unverified_account_sees_no_dashboard_trial_and_cannot_download(client, fake_stripe):
    register(verified=False)
    sign_in(client)
    a_dashboard_trial(fake_stripe)

    assert client.get("/api/subscription").json() == {"subscription": None}
    assert client.post("/api/downloads", json={"package": "com.whatsapp", "version": "1.0"}).status_code == 402
    assert linked() is None


def test_a_failed_lookup_is_no_subscription_rather_than_a_503(client, fake_stripe):
    register()
    sign_in(client)
    fake_stripe.fail("customers.list", stripe.APIConnectionError("down"))

    response = client.get("/api/subscription")

    assert response.status_code == 200
    assert response.json() == {"subscription": None}


@pytest.fixture
def enqueued(monkeypatch, patched_apps_dir):
    """POST /downloads, stopped at the queue: records what it would sign into the APK."""
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


def app_token_claims(job):
    return jwt.decode(job["app_token"], jwt_utils.APP_SECRET_KEY, algorithms=[jwt_utils.ALGORITHM])


def test_an_account_linked_after_signing_in_downloads_without_signing_in_again(client, fake_stripe, enqueued):
    register()
    sign_in(client)  # the cookie is minted with no customer in it
    a_dashboard_trial(fake_stripe, "cus_granted", username="alice")
    assert database.update_user_customer_id("username", "alice", "cus_granted")  # grant-trial, or the owner

    assert download(client).status_code == 202
    assert app_token_claims(enqueued[0])["customer_id"] == "cus_granted"


def test_an_apk_minted_after_linking_carries_the_customer_id(client, fake_stripe, enqueued):
    """Linked on the way through the download itself; the APK must carry the
    customer, or the phone's device check could never find the account."""
    register()
    sign_in(client)
    a_dashboard_trial(fake_stripe)

    assert download(client).status_code == 202
    assert linked() == "cus_dash"
    app_token = enqueued[0]["app_token"]
    assert app_token_claims(enqueued[0])["customer_id"] == "cus_dash"

    allowed = client.post("/api/subscription/is_device_allowed",
                          data={"token": app_token, "device": "phone1", "sku": "sku"})
    assert allowed.status_code == 200
    assert allowed.json() == {"allowed": True}


@pytest.mark.parametrize("path", [
    "/api/subscription/billing-portal", "/api/subscription/cancel", "/api/subscription/reactivate",
])
def test_billing_actions_use_the_accounts_customer_not_the_cookies(client, fake_stripe, path):
    register()
    sign_in(client)
    subscription = a_dashboard_trial(fake_stripe, "cus_granted", username="alice")
    assert database.update_user_customer_id("username", "alice", "cus_granted")

    assert client.post(path).status_code == 200
    if path.endswith("billing-portal"):
        assert fake_stripe.called("billing_portal.sessions.create")[0]["customer"] == "cus_granted"
    else:
        assert fake_stripe.called("subscriptions.update")[0][0] == subscription["id"]


def test_checkout_refuses_a_second_subscription_over_a_dashboard_trial(client, fake_stripe):
    register()
    sign_in(client)
    a_dashboard_trial(fake_stripe)

    response = client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 409
    assert fake_stripe.called("checkout.sessions.create") == []
    assert linked() == "cus_dash"
    assert fake_stripe.customers["cus_dash"]["metadata"] == {"username": "alice"}


def test_checkout_reuses_the_customer_an_unpaid_checkout_made(client, fake_stripe):
    """Tagged by our own checkout, never paid: reused rather than duplicated,
    though with no subscription it was never linked."""
    register()
    sign_in(client)
    fake_stripe.add_customer("cus_unpaid", "alice@example.com", username="alice")

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 200
    assert [params["customer"] for params in fake_stripe.called("checkout.sessions.create")] == ["cus_unpaid"]
    assert fake_stripe.called("customers.create") == []
    assert linked() is None


def test_checkout_does_not_reuse_an_untagged_customer_with_no_live_subscription(client, fake_stripe):
    """An address alone proves nothing without a live subscription to this
    product on the customer: it may be the other product's customer."""
    register()
    sign_in(client)
    fake_stripe.add_customer("cus_dash", "alice@example.com")

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 200
    [session] = fake_stripe.called("checkout.sessions.create")
    assert session["customer"] != "cus_dash"
    assert fake_stripe.customers["cus_dash"]["metadata"] == {}


def test_checkout_prefers_the_linked_customer_to_another_one_it_finds(client, fake_stripe):
    """The row is the source of truth: paying on some other customer would leave
    the account pointing at one without the subscription."""
    register(customer_id="cus_linked")
    sign_in(client)
    fake_stripe.add_customer("cus_linked", "old@example.com", username="alice")
    fake_stripe.add_customer("cus_other", "alice@example.com", username="alice")

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 200
    assert [params["customer"] for params in fake_stripe.called("checkout.sessions.create")] == ["cus_linked"]


def test_confirm_success_links_a_dashboard_customer(client, fake_stripe):
    """What the Subscription page's quiet restore calls when it finds nothing."""
    register()
    sign_in(client)
    a_dashboard_trial(fake_stripe)

    assert client.post("/api/subscription/confirm_success").json() == {"message": "Subscription confirmed"}
    assert linked() == "cus_dash"


def remember_a_miss(fake_stripe, username="alice"):
    assert resolve(username) is None
    assert stripe_utils._no_customer_cache.get(username) is not None


def test_checkout_forgets_a_remembered_miss(client, fake_stripe):
    register()
    sign_in(client)
    remember_a_miss(fake_stripe)
    a_dashboard_trial(fake_stripe)

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 409


def test_confirm_success_forgets_a_remembered_miss(client, fake_stripe):
    register()
    sign_in(client)
    remember_a_miss(fake_stripe)
    a_dashboard_trial(fake_stripe)

    assert client.post("/api/subscription/confirm_success").status_code == 200
    assert linked() == "cus_dash"


def test_a_device_reset_forgets_a_remembered_miss(client, fake_stripe):
    register()
    sign_in(client)
    remember_a_miss(fake_stripe)

    assert client.post("/api/users/me/devices/reset").status_code == 200
    assert stripe_utils._no_customer_cache.get("alice") is None
