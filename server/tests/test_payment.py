"""Billing layer tests. Stripe is always a fake - nothing here touches the network."""
import os
import subprocess
import sys
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
import stripe
from fastapi import FastAPI
from starlette.testclient import TestClient

import config
from database import DeviceResult
from api import payment
from utils import jwt_utils, stripe_utils

PRICE_ID = "price_1Sw7fiIVyiVj53XrmnQKBjUb"
CUSTOMER_ID = "cus_live"
ACTIVE_PERIOD_END = 1800000000
CANCELLED_PERIOD_END = 1700000000


class StripeLike(dict):
    """Stand-in for stripe.StripeObject: a dict that also answers attribute access.

    Keeping the dict base matters - it is why `sub.items` is dict.items rather
    than the subscription's line items.
    """

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name)


class Boom:
    """Any access is a test failure: this service should never have been called."""

    def __init__(self, name):
        self._name = name

    def __getattr__(self, item):
        raise AssertionError(f"unexpected Stripe call: {self._name}.{item}")


def make_client(subscriptions=None, products=None, customers=None, checkout=None, portal=None, prices=None):
    return SimpleNamespace(v1=SimpleNamespace(
        subscriptions=subscriptions or Boom("subscriptions"),
        products=products or Boom("products"),
        prices=prices or Boom("prices"),
        customers=customers or Boom("customers"),
        checkout=SimpleNamespace(sessions=checkout or Boom("checkout.sessions")),
        billing_portal=SimpleNamespace(sessions=portal or Boom("billing_portal.sessions")),
    ))


def subscription(sub_id, status, item_period_end=None, sub_period_end=None, cancel_at_period_end=False):
    # Only subscriptions to this site's product count (see stripe_utils.is_this_product).
    item = StripeLike({"id": f"si_{sub_id}", "price": StripeLike({
        "id": PRICE_ID, "nickname": "Pro", "product": config.STRIPE_PRODUCT_ID})})
    if item_period_end is not None:
        item["current_period_end"] = item_period_end
    data = {
        "id": sub_id,
        "status": status,
        "customer": CUSTOMER_ID,
        "cancel_at_period_end": cancel_at_period_end,
        "items": StripeLike({"data": [item]}),
    }
    if sub_period_end is not None:
        data["current_period_end"] = sub_period_end
    return StripeLike(data)


def subscription_service(*subs, error=None):
    def _list(params=None, options=None):
        if error is not None:
            raise error
        return StripeLike({"data": list(subs)})

    return SimpleNamespace(list=_list, update=lambda sid, params=None, options=None: StripeLike({"id": sid}))


@pytest.fixture(autouse=True)
def reset_product_cache():
    stripe_utils._product_cache["value"] = None
    stripe_utils._product_cache["fetched_at"] = 0.0
    stripe_utils._price_cache["value"] = None
    stripe_utils._price_cache["fetched_at"] = 0.0


@pytest.fixture
def token():
    return SimpleNamespace(username="alice", email="alice@example.com", customer_id=CUSTOMER_ID, client=None)


@pytest.fixture
def alice(tmp_db, token):
    """The account behind `token`, linked to the token's customer.

    Every endpoint reads the customer from this row, never from the token, so
    the faked token alone no longer says which customer a test is about.
    """
    import database

    assert database.register_user(database.RegisterRequest(
        username="alice", email="alice@example.com", password="correct horse battery"))
    if token.customer_id:
        assert database.update_user_customer_id("username", "alice", token.customer_id)


@pytest.fixture
def client(alice, token):
    app = FastAPI()
    app.include_router(payment.router, prefix="/api")
    app.dependency_overrides[jwt_utils.get_token_data] = lambda: token
    return TestClient(app)


@pytest.fixture
def verified_alice(alice):
    """`alice`, with a verified address.

    create-checkout reads verification from the row, not from the token, so the
    faked token alone no longer gets a checkout test past its first guard.
    """
    import database

    assert database.mark_email_verified("alice", "alice@example.com", datetime.now(timezone.utc))


def use_client(monkeypatch, fake):
    """payment.py holds its own reference to stripe_client, so both modules are patched."""
    monkeypatch.setattr(stripe_utils, "stripe_client", fake)
    monkeypatch.setattr(payment, "stripe_client", fake)


def iso(timestamp):
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat()


def test_active_subscription_beats_a_more_recent_cancelled_one(monkeypatch, client):
    # Stripe lists newest first, so the cancelled one arrives first.
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_cancelled", "canceled", item_period_end=CANCELLED_PERIOD_END),
        subscription("sub_active", "active", item_period_end=ACTIVE_PERIOD_END),
    )))

    body = client.get("/api/subscription").json()["subscription"]

    assert body["status"] == "active"
    assert body["planId"] == "pro"
    assert body["planName"] == "Pro"
    assert body["currentPeriodEnd"] == iso(ACTIVE_PERIOD_END)


def test_current_period_end_is_iso_from_the_subscription_item(monkeypatch, client):
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "trialing", item_period_end=ACTIVE_PERIOD_END, cancel_at_period_end=True),
    )))

    body = client.get("/api/subscription").json()["subscription"]

    parsed = datetime.fromisoformat(body["currentPeriodEnd"])
    assert parsed.tzinfo is not None
    assert parsed == datetime.fromtimestamp(ACTIVE_PERIOD_END, tz=timezone.utc)
    assert body["cancelAtPeriodEnd"] is True


def test_current_period_end_falls_back_to_the_legacy_subscription_field(monkeypatch, client):
    # Accounts pinned to an API version before 2025-03-31 still send it here.
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "active", sub_period_end=ACTIVE_PERIOD_END),
    )))

    body = client.get("/api/subscription").json()["subscription"]

    assert body["currentPeriodEnd"] == iso(ACTIVE_PERIOD_END)


def test_no_subscription_returns_null(monkeypatch, client):
    use_client(monkeypatch, make_client(subscriptions=subscription_service()))
    assert client.get("/api/subscription").json() == {"subscription": None}


def test_subscription_status_is_free_without_a_customer_id(monkeypatch):
    use_client(monkeypatch, make_client())
    assert stripe_utils.get_subscription_status('') == 'free'
    assert stripe_utils.get_subscription_status(None) == 'free'


@pytest.mark.parametrize("token_customer_id", [None, CUSTOMER_ID])
def test_the_subscription_comes_from_the_rows_customer_not_the_tokens(monkeypatch, token, client, tmp_db,
                                                                     token_customer_id):
    """An unlinked account has no subscription, whatever its token claims, and
    finding no customer in Stripe either costs no subscription lookup."""
    import sqlite3

    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET customer_id = NULL WHERE username = 'alice'")
    conn.commit()
    conn.close()
    token.customer_id = token_customer_id
    use_client(monkeypatch, make_client(customers=SimpleNamespace(
        list=lambda params=None, options=None: StripeLike({"data": []}))))

    assert client.get("/api/subscription").json() == {"subscription": None}


def test_stripe_unreachable_is_a_clean_503(monkeypatch, client):
    error = stripe.APIConnectionError("secret-internal-host unreachable")
    use_client(monkeypatch, make_client(subscriptions=subscription_service(error=error)))

    response = client.get("/api/subscription")

    assert response.status_code == 503
    assert "secret-internal-host" not in response.text


def _no_such_customer():
    return stripe.InvalidRequestError("No such customer: 'cus_gone'", "customer",
                                      code="resource_missing", http_status=400)


def test_a_customer_stripe_no_longer_has_reads_as_unsubscribed(monkeypatch):
    """Deleted in the dashboard, or a test-mode id left over from before going
    live: a permanent 503 for that account would be wrong, it has no subscription."""
    use_client(monkeypatch, make_client(subscriptions=subscription_service(error=_no_such_customer())))

    assert stripe_utils.find_subscription("cus_gone") is None
    assert stripe_utils.customer_subscription("cus_gone") == (False, None)
    assert stripe_utils.get_subscription_status("cus_gone") == "free"


def test_other_invalid_requests_are_still_a_503(monkeypatch):
    error = stripe.InvalidRequestError("Invalid limit", "limit", code="parameter_invalid_integer")
    use_client(monkeypatch, make_client(subscriptions=subscription_service(error=error)))

    with pytest.raises(stripe_utils.HTTPException) as excinfo:
        stripe_utils.find_subscription(CUSTOMER_ID)
    assert excinfo.value.status_code == 503


def test_the_product_is_not_fetched_until_something_needs_it(monkeypatch):
    calls = []

    def retrieve(product_id, params=None, options=None):
        calls.append(product_id)
        return StripeLike({"id": product_id, "default_price": PRICE_ID})

    use_client(monkeypatch, make_client(products=SimpleNamespace(retrieve=retrieve)))

    assert calls == []
    assert stripe_utils.get_default_price_id() == PRICE_ID
    assert stripe_utils.get_default_price_id() == PRICE_ID
    assert calls == [config.STRIPE_PRODUCT_ID]  # second call served from cache


def test_product_lookup_failure_is_a_503_at_request_time(monkeypatch):
    def retrieve(product_id, params=None, options=None):
        raise stripe.APIConnectionError("stripe is down")

    use_client(monkeypatch, make_client(products=SimpleNamespace(retrieve=retrieve)))

    with pytest.raises(stripe_utils.HTTPException) as excinfo:
        stripe_utils.get_default_price_id()
    assert excinfo.value.status_code == 503
    assert "stripe is down" not in excinfo.value.detail


# --- is_device_allowed -------------------------------------------------------

class FakeUser:
    def __init__(self, fingerprints):
        self.username = "alice"
        self.device_fingerprints = list(fingerprints)


@pytest.fixture
def device_request(monkeypatch):
    """Wire up an app-token + user lookup that does not depend on the live DB."""
    monkeypatch.setattr(payment.jwt_utils, "get_app_token_data", lambda raw: SimpleNamespace(
        username="alice", customer_id=CUSTOMER_ID, package_name="com.whatsapp",
    ))

    def _build(user, recorded=None):
        monkeypatch.setattr(payment.database, "get_user_by_customer_id", lambda cid: user)

        def _record(username, fingerprint):
            # Mirrors the real database function, which applies the limit inside
            # its own transaction. A stub that always said "recorded" hid the
            # fact that the handler was reading LIMIT_REACHED as success.
            if recorded is not None:
                return recorded
            if fingerprint in user.device_fingerprints:
                return DeviceResult.ALREADY_REGISTERED
            if len(user.device_fingerprints) >= config.MAX_DEVICES_ALLOWED:
                return DeviceResult.LIMIT_REACHED
            user.device_fingerprints.append(fingerprint)
            return DeviceResult.ADDED

        monkeypatch.setattr(payment.database, "update_user_device_fingerprints", _record)
        app = FastAPI()
        app.include_router(payment.router, prefix="/api")
        return TestClient(app)

    return _build


def test_device_limit_returns_403(monkeypatch, device_request):
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "active", item_period_end=ACTIVE_PERIOD_END),
    )))
    used = [f"device{i}_sku" for i in range(config.MAX_DEVICES_ALLOWED)]
    client = device_request(FakeUser(used))

    response = client.post("/api/subscription/is_device_allowed",
                           data={"token": "app-token", "device": "new-device", "sku": "sku"})

    assert response.status_code == 403
    assert response.json()["detail"]


def test_a_known_device_is_allowed(monkeypatch, device_request):
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "trialing", item_period_end=ACTIVE_PERIOD_END),
    )))
    client = device_request(FakeUser(["known_sku"]))

    response = client.post("/api/subscription/is_device_allowed",
                           data={"token": "app-token", "device": "known", "sku": "sku"})

    assert response.status_code == 200
    assert response.json() == {"allowed": True}


def test_a_refused_registration_is_treated_as_the_device_limit(monkeypatch, device_request):
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "active", item_period_end=ACTIVE_PERIOD_END),
    )))
    client = device_request(FakeUser([]), recorded=False)

    response = client.post("/api/subscription/is_device_allowed",
                           data={"token": "app-token", "device": "new", "sku": "sku"})

    assert response.status_code == 403


def test_a_cancelled_subscription_cannot_unlock_a_device(monkeypatch, device_request):
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_cancelled", "canceled", item_period_end=CANCELLED_PERIOD_END),
    )))
    client = device_request(FakeUser([]))

    response = client.post("/api/subscription/is_device_allowed",
                           data={"token": "app-token", "device": "new", "sku": "sku"})

    assert response.status_code == 402


def test_is_device_allowed_is_rate_limited_per_ip_but_generously(monkeypatch, device_request):
    """Patched apps ask on every launch, and a carrier NAT puts many phones on
    one address, so the limit only stops a flood - never a busy NAT."""
    from utils.ratelimit import device_check_rate_limit

    assert device_check_rate_limit.limit >= 60
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", "active", item_period_end=ACTIVE_PERIOD_END),
    )))
    client = device_request(FakeUser(["known_sku"]))
    form = {"token": "app-token", "device": "known", "sku": "sku"}

    for _ in range(device_check_rate_limit.limit):
        assert client.post("/api/subscription/is_device_allowed", data=form).status_code == 200
    response = client.post("/api/subscription/is_device_allowed", data=form)

    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) > 0


def test_is_device_allowed_keeps_its_401_for_a_bodyless_or_json_request():
    """Phones in the field post a form; anything else must still be a 401, never a 422."""
    app = FastAPI()
    app.include_router(payment.router, prefix="/api")
    client = TestClient(app)

    assert client.post("/api/subscription/is_device_allowed").status_code == 401
    assert client.post("/api/subscription/is_device_allowed", json={"token": "x"}).status_code == 401


def test_health_endpoint_responds():
    import main

    response = TestClient(main.app).get("/api/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def _checkout_stripe(monkeypatch, checkout_create, subscriptions=None):
    """Fake exactly the four services create_checkout_session touches.

    billing_portal stays Boom - the endpoint must never reach it - and
    customers.create stays absent so this proves the "own customer already
    exists" path (customers.list finds alice, so nothing tries to create a second
    one). subscriptions is consulted for the already-subscribed guard; by default
    alice's only subscription is a cancelled one, so checkout goes ahead.
    """
    use_client(monkeypatch, make_client(
        products=SimpleNamespace(
            retrieve=lambda product_id, params=None, options=None: StripeLike(
                {"id": product_id, "default_price": PRICE_ID}
            )
        ),
        customers=SimpleNamespace(
            list=lambda params=None, options=None: StripeLike(
                {"data": [StripeLike({"id": CUSTOMER_ID, "metadata": StripeLike({"username": "alice"})})]}
            )
        ),
        checkout=SimpleNamespace(create=checkout_create),
        subscriptions=subscriptions or subscription_service(
            subscription("sub_cancelled", "canceled", item_period_end=CANCELLED_PERIOD_END),
        ),
    ))


def test_checkout_works_without_a_price_id(monkeypatch, client, verified_alice):
    """Mobile clients send no price: the server has always used the product's own."""
    _checkout_stripe(monkeypatch, lambda params=None, options=None: StripeLike(
        {"url": "https://checkout.stripe.com/pay/cs_test_123"}
    ))

    response = client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 200
    assert response.json()["url"] == "https://checkout.stripe.com/pay/cs_test_123"


def test_checkout_still_ignores_a_submitted_price_id(monkeypatch, client, verified_alice):
    """A client cannot pick its own price by sending one."""
    captured = {}

    def _create(params=None, options=None):
        captured["params"] = params
        return StripeLike({"url": "https://checkout.stripe.com/pay/cs_test_456"})

    _checkout_stripe(monkeypatch, _create)

    response = client.post(
        "/api/subscription/create-checkout", json={"priceId": "price_attacker_chosen"}
    )

    assert response.status_code == 200
    line_item = captured["params"]["line_items"][0]
    assert line_item["price"] == PRICE_ID
    assert line_item["price"] != "price_attacker_chosen"


def test_checkout_is_refused_while_a_subscription_is_active(monkeypatch, client, verified_alice):
    """A second checkout would bill the customer twice for one account."""
    def _never(params=None, options=None):
        raise AssertionError("a checkout session was created for an active subscriber")

    _checkout_stripe(
        monkeypatch,
        checkout_create=_never,
        subscriptions=subscription_service(subscription("sub_active", "active")),
    )

    response = client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 409
    assert response.json() == {"detail": "This account already has an active subscription."}


def test_checkout_invalidates_the_status_cache(monkeypatch, client, verified_alice):
    _checkout_stripe(monkeypatch, lambda params=None, options=None: StripeLike({"url": "https://c.test"}))
    _prime_status_cache()

    assert client.post("/api/subscription/create-checkout", json={}).status_code == 200
    assert stripe_utils._status_cache.get(CUSTOMER_ID) is None


def test_stripe_utils_imports_without_calling_stripe():
    """The old module fetched the product at import, so a bad key killed the boot.

    Any accidental call is pointed at a dead local proxy, never at Stripe.
    """
    env = dict(os.environ)
    env["STRIPE_API_KEY"] = "sk_test_not_a_real_key"
    env["HTTPS_PROXY"] = env["HTTP_PROXY"] = "http://127.0.0.1:1"
    result = subprocess.run(
        [sys.executable, "-c", "import utils.stripe_utils"],
        cwd=str(config.SERVER_DIR), env=env, capture_output=True, timeout=120,
    )
    assert result.returncode == 0, result.stderr.decode()


# --- Stripe client resilience -------------------------------------------------

def test_stripe_calls_are_bounded_by_a_short_timeout_and_one_retry():
    """The library default is an 80 s timeout with retries on top.

    Every Stripe call runs on a request thread, and patched apps call
    is_device_allowed on every launch, so a Stripe brown-out used to pin
    threadpool workers for minutes at a time.
    """
    requestor = stripe_utils.stripe_client._requestor
    assert requestor._client._timeout == stripe_utils.STRIPE_TIMEOUT_SECONDS
    assert stripe_utils.STRIPE_TIMEOUT_SECONDS <= 10
    assert requestor._options.max_network_retries == 1


def test_the_stripe_library_does_not_log_customer_ids_at_info(caplog):
    """stripe logs every request line (customer ids included) at INFO."""
    import logging

    from stripe import _util as stripe_util

    caplog.set_level(logging.INFO)
    stripe_util.log_info("Request to Stripe api", path="/v1/customers/cus_secretive")

    assert logging.getLogger("stripe").getEffectiveLevel() >= logging.WARNING
    assert not any("cus_secretive" in record.getMessage() for record in caplog.records)


# --- subscription status cache ----------------------------------------------------

def counting_subscription_service(*subs, error=None):
    """subscription_service, plus a count of how often Stripe was asked."""
    service = subscription_service(*subs, error=error)
    calls = []
    inner = service.list

    def _list(params=None, options=None):
        calls.append(params)
        return inner(params, options)

    service.list = _list
    service.calls = calls
    return service


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def test_subscription_status_is_cached_per_customer(monkeypatch):
    service = counting_subscription_service(subscription("sub_active", "active"))
    use_client(monkeypatch, make_client(subscriptions=service))

    assert stripe_utils.get_subscription_status(CUSTOMER_ID) == "active"
    assert stripe_utils.get_subscription_status(CUSTOMER_ID) == "active"

    assert len(service.calls) == 1


def test_subscription_status_cache_expires(monkeypatch):
    clock = FakeClock()
    monkeypatch.setattr(stripe_utils._status_cache, "_clock", clock)
    service = counting_subscription_service(subscription("sub_active", "active"))
    use_client(monkeypatch, make_client(subscriptions=service))

    stripe_utils.get_subscription_status(CUSTOMER_ID)
    clock.now += stripe_utils.SUBSCRIPTION_STATUS_TTL_SECONDS - 1
    stripe_utils.get_subscription_status(CUSTOMER_ID)
    assert len(service.calls) == 1

    clock.now += 2
    stripe_utils.get_subscription_status(CUSTOMER_ID)
    assert len(service.calls) == 2


def test_a_stripe_failure_is_not_cached(monkeypatch):
    failing = counting_subscription_service(error=stripe.APIConnectionError("down"))
    use_client(monkeypatch, make_client(subscriptions=failing))
    for _ in range(2):
        with pytest.raises(stripe_utils.HTTPException):
            stripe_utils.get_subscription_status(CUSTOMER_ID)
    assert len(failing.calls) == 2

    use_client(monkeypatch, make_client(subscriptions=subscription_service(subscription("s", "active"))))
    assert stripe_utils.get_subscription_status(CUSTOMER_ID) == "active"


def test_invalidation_forces_a_fresh_lookup(monkeypatch):
    service = counting_subscription_service(subscription("sub_active", "active"))
    use_client(monkeypatch, make_client(subscriptions=service))

    stripe_utils.get_subscription_status(CUSTOMER_ID)
    stripe_utils.invalidate_subscription_status(CUSTOMER_ID)
    stripe_utils.get_subscription_status(CUSTOMER_ID)

    assert len(service.calls) == 2


def test_a_lookup_racing_an_invalidation_does_not_cache_the_stale_answer(monkeypatch):
    """A cancel landing while an older lookup is in flight must win."""
    def _list(params=None, options=None):
        # The invalidation arrives after Stripe answered but before the answer
        # is stored - the window a concurrent /cancel request can hit.
        stripe_utils.invalidate_subscription_status(CUSTOMER_ID)
        return StripeLike({"data": [subscription("sub_active", "active")]})

    use_client(monkeypatch, make_client(subscriptions=SimpleNamespace(list=_list)))
    assert stripe_utils.get_subscription_status(CUSTOMER_ID) == "active"
    assert stripe_utils._status_cache.get(CUSTOMER_ID) is None


def test_the_status_cache_is_bounded():
    cache = stripe_utils.TTLCache(ttl_seconds=60, max_entries=3)
    for index in range(5):
        cache.put(f"cus_{index}", "active", cache.generation())
    assert len(cache) == 3
    assert cache.get("cus_0") is None
    assert cache.get("cus_4") == "active"


def _prime_status_cache():
    stripe_utils._status_cache.put(CUSTOMER_ID, "active", stripe_utils._status_cache.generation())
    assert stripe_utils._status_cache.get(CUSTOMER_ID) == "active"


@pytest.mark.parametrize("path", ["/api/subscription/cancel", "/api/subscription/reactivate"])
def test_cancel_and_reactivate_invalidate_the_status_cache(monkeypatch, client, path):
    status = "active"
    use_client(monkeypatch, make_client(subscriptions=subscription_service(
        subscription("sub_active", status, item_period_end=ACTIVE_PERIOD_END),
    )))
    _prime_status_cache()

    assert client.post(path).status_code == 200
    assert stripe_utils._status_cache.get(CUSTOMER_ID) is None


def test_billing_portal_invalidates_the_status_cache(monkeypatch, client):
    use_client(monkeypatch, make_client(portal=SimpleNamespace(
        create=lambda params=None, options=None: StripeLike({"url": "https://billing.stripe.test/p"}))))
    _prime_status_cache()

    assert client.post("/api/subscription/billing-portal").status_code == 200
    assert stripe_utils._status_cache.get(CUSTOMER_ID) is None


def test_confirm_success_invalidates_the_status_cache_even_when_already_confirmed(monkeypatch, client):
    """A lapsed subscriber who pays again already has a customer_id stamped."""
    use_client(monkeypatch, make_client())
    _prime_status_cache()

    assert client.post("/api/subscription/confirm_success").status_code == 200
    assert stripe_utils._status_cache.get(CUSTOMER_ID) is None


# --- public price -------------------------------------------------------------------

def price_stripe(monkeypatch, unit_amount=400, recurring=None, price_error=None, product_error=None):
    """Product -> default price -> price, the two lookups /price makes. Counts both."""
    calls = []

    def retrieve_product(product_id, params=None, options=None):
        calls.append(("product", product_id))
        if product_error is not None:
            raise product_error
        return StripeLike({"id": product_id, "default_price": PRICE_ID})

    def retrieve_price(price_id, params=None, options=None):
        calls.append(("price", price_id))
        if price_error is not None:
            raise price_error
        return StripeLike({
            "id": price_id, "unit_amount": unit_amount, "currency": "usd",
            "recurring": StripeLike({"interval": "month", "interval_count": 1})
            if recurring is None else recurring,
        })

    use_client(monkeypatch, make_client(
        products=SimpleNamespace(retrieve=retrieve_product),
        prices=SimpleNamespace(retrieve=retrieve_price),
    ))
    return calls


@pytest.fixture
def public_client():
    """No token override: the price is shown to signed-out visitors too."""
    app = FastAPI()
    app.include_router(payment.router, prefix="/api")
    return TestClient(app)


def test_price_is_public_and_in_minor_units(monkeypatch, public_client):
    price_stripe(monkeypatch)

    response = public_client.get("/api/subscription/price")

    assert response.status_code == 200
    assert response.json() == {"amount": 400, "currency": "usd", "interval": "month", "interval_count": 1}


def test_price_is_cached(monkeypatch, public_client):
    calls = price_stripe(monkeypatch)

    public_client.get("/api/subscription/price")
    public_client.get("/api/subscription/price")

    assert [kind for kind, _ in calls].count("price") == 1


def test_price_is_503_when_stripe_is_down_and_nothing_is_cached(monkeypatch, public_client):
    price_stripe(monkeypatch, price_error=stripe.APIConnectionError("internal-host down"))

    response = public_client.get("/api/subscription/price")

    assert response.status_code == 503
    assert "internal-host" not in response.text


def test_price_is_503_when_the_product_lookup_fails(monkeypatch, public_client):
    price_stripe(monkeypatch, product_error=stripe.APIConnectionError("down"))
    assert public_client.get("/api/subscription/price").status_code == 503


def test_a_stale_price_beats_a_503(monkeypatch, public_client):
    price_stripe(monkeypatch)
    assert public_client.get("/api/subscription/price").status_code == 200
    stripe_utils._price_cache["fetched_at"] = -10 ** 9  # long expired

    price_stripe(monkeypatch, price_error=stripe.APIConnectionError("down"))
    response = public_client.get("/api/subscription/price")

    assert response.status_code == 200
    assert response.json()["amount"] == 400


@pytest.mark.parametrize("unit_amount, recurring", [
    (None, None),                 # tiered or custom pricing has no single amount
    (400, False),                 # a one-time price cannot back a subscription
])
def test_an_unusable_price_is_503_never_a_guess(monkeypatch, public_client, unit_amount, recurring):
    price_stripe(monkeypatch, unit_amount=unit_amount,
                 recurring=StripeLike({}) if recurring is False else None)
    assert public_client.get("/api/subscription/price").status_code == 503
