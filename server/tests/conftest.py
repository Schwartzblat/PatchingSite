"""Test environment. Must run before `config` is imported anywhere.

The production DB lives at /opt/server_data/users.db and the site is live, so
every test is pointed at a throwaway tmp dir.
"""
import os
import re
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_TMP = Path(tempfile.mkdtemp(prefix="patchingsite-tests-"))
os.environ.setdefault("JWT_SECRET_KEY", "test-jwt-secret-do-not-use-in-production")
os.environ.setdefault("STRIPE_API_KEY", "sk_test_dummy")
os.environ.setdefault("STRIPE_PRODUCT_ID", "prod_dummy")
os.environ["SERVER_DATA_DIR"] = str(_TMP / "data")
# Set, not just defaulted: config also loads ../.env, and a v2 checkout's .env
# points DB_PATH at the live accounts database.
os.environ["DB_PATH"] = str(_TMP / "data" / "users.db")
os.environ["PATCHED_APPS_PATH"] = str(_TMP / "patched")
os.environ["LOG_PATH"] = str(_TMP / "app.log")
os.environ["ENVIRONMENT"] = "test"
# 32 bytes of 0x01, base64: a fixed test value, never a real key.
os.environ["SIGNING_KEYS_MASTER_KEY"] = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
os.environ["CRAWLER_SECRET"] = "test-crawler"
os.environ["BUNDLE_UPLOAD_SECRET"] = "test-bundles"
os.environ["BUNDLES_DIR"] = str(_TMP / "data" / "bundles")
os.environ.pop("ON_DEVICE_PACKAGES", None)
# Same for the generic switch and its Works-with path: empty means the default for
# both, so a developer's .env cannot turn the feature on under test.
os.environ["GENERIC_PATCHING"] = ""
os.environ["GENERIC_WORKS_WITH_PATH"] = ""
# A developer's .env may hold real SMTP credentials. Set (even empty) here, they
# win over the file, so mail is unconfigured unless a test configures it itself.
os.environ["SMTP_USERNAME"] = ""
os.environ["SMTP_PASSWORD"] = ""
os.environ["MAIL_FROM"] = ""

(_TMP / "data").mkdir(parents=True, exist_ok=True)
(_TMP / "patched").mkdir(parents=True, exist_ok=True)

import pytest  # noqa: E402
import stripe  # noqa: E402

import config  # noqa: E402


@pytest.fixture
def tmp_db(monkeypatch, tmp_path):
    """Fresh sqlite file per test, with the schema initialised."""
    import database

    db_path = tmp_path / "users.db"
    monkeypatch.setattr(config, "DB_PATH", db_path)
    monkeypatch.setattr(database, "DB_PATH", db_path, raising=False)
    database.init()
    return db_path


@pytest.fixture
def patched_apps_dir(monkeypatch, tmp_path):
    """Stand-in for /opt/patched."""
    root = tmp_path / "patched"
    root.mkdir(parents=True, exist_ok=True)
    monkeypatch.setattr(config, "PATCHED_APPS_PATH", root)
    return root

@pytest.fixture(autouse=True)
def reset_rate_limits():
    """The limiters are module-level singletons, so hits leak between tests.

    Without this a test that registers a few users silently starves every later
    test of its quota, and the failure surfaces as an unrelated 429.
    """
    from utils.ratelimit import reset_all

    reset_all()
    yield
    reset_all()


@pytest.fixture(autouse=True)
def no_real_smtp(monkeypatch):
    """No test may reach a mail server, whatever it configures.

    Tests that exercise the mailer install their own fake over this one.
    """
    import smtplib

    class _NoSMTP:
        def __init__(self, *args, **kwargs):
            raise AssertionError("a test tried to open a real SMTP connection")

    monkeypatch.setattr(smtplib, "SMTP_SSL", _NoSMTP)
    monkeypatch.setattr(smtplib, "SMTP", _NoSMTP)


@pytest.fixture(autouse=True)
def reset_subscription_status_cache():
    """Module singletons: a status or a customer lookup cached by one test would answer the next."""
    from utils import stripe_utils

    stripe_utils._status_cache.clear()
    stripe_utils._no_customer_cache.clear()
    yield
    stripe_utils._status_cache.clear()
    stripe_utils._no_customer_cache.clear()


class _RealStripeGuard:
    """Stands in for the module-level StripeClient in every test.

    The key in tests is a dummy, so a call nobody faked used to go out to
    api.stripe.com and come back as an authentication error, which the code
    under test then handled as an outage: the test passed or failed for the
    wrong reason, and needed the network to do it. Any service access now fails
    the test on the spot. Everything else (the timeout and retry settings a
    resilience test reads) is the real client's.
    """

    def __init__(self, real):
        self._real = real

    @property
    def v1(self):
        raise AssertionError("a test reached the real Stripe client; fake the Stripe service it uses")

    def __getattr__(self, name):
        return getattr(self._real, name)


@pytest.fixture(autouse=True)
def no_real_stripe(monkeypatch):
    from api import payment
    from utils import stripe_utils

    guard = _RealStripeGuard(stripe_utils.stripe_client)
    monkeypatch.setattr(stripe_utils, "stripe_client", guard)
    monkeypatch.setattr(payment, "stripe_client", guard)


class StripeLike(dict):
    """A dict that also answers attribute access, like stripe.StripeObject."""

    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name)


FAKE_PRICE_ID = "price_default"
# The Stripe account also holds subscriptions to another product, and only this
# site's product (config.STRIPE_PRODUCT_ID) may count for anything here.
FAKE_PRODUCT_ID = config.STRIPE_PRODUCT_ID
OTHER_PRODUCT_ID = "prod_someone_elses"


class FakeStripe:
    """An in-memory Stripe holding customers and subscriptions.

    It keeps the behaviour the site depends on rather than canned answers:
    customers.list filters on the exact, case-sensitive address as Stripe's
    does, customers.search matches it case-insensitively, lists come back
    newest first and page by `limit`/`starting_after` with `has_more`, a
    subscription item's price names its product, and an unknown id is
    Stripe's `resource_missing`. `calls` records every request as
    (service.method, argument), and `fail(name, error)` makes one method raise.
    """

    price_id = FAKE_PRICE_ID
    product_id = FAKE_PRODUCT_ID
    other_product_id = OTHER_PRODUCT_ID

    def __init__(self):
        self.customers = {}
        self.subscriptions = []
        self.calls = []
        self._failures = {}
        self._clock = 1_700_000_000
        self.v1 = SimpleNamespace(
            customers=SimpleNamespace(
                list=self._service("customers.list", self._list_customers),
                search=self._service("customers.search", self._search_customers),
                retrieve=self._service("customers.retrieve", self._retrieve_customer),
                update=self._service("customers.update", self._update_customer),
                create=self._service("customers.create", self._create_customer),
            ),
            subscriptions=SimpleNamespace(
                list=self._service("subscriptions.list", self._list_subscriptions),
                create=self._service("subscriptions.create", self._create_subscription),
                update=self._service("subscriptions.update", self._update_subscription),
            ),
            products=SimpleNamespace(retrieve=self._service("products.retrieve", lambda product_id, params=None: (
                StripeLike({"id": product_id, "default_price": FAKE_PRICE_ID})))),
            prices=SimpleNamespace(retrieve=self._service("prices.retrieve", lambda price_id, params=None: (
                StripeLike({"id": price_id, "unit_amount": 400, "currency": "usd",
                            "recurring": StripeLike({"interval": "month", "interval_count": 1})})))),
            checkout=SimpleNamespace(sessions=SimpleNamespace(create=self._service(
                "checkout.sessions.create", lambda params=None: StripeLike({"url": "https://checkout.test/s"})))),
            billing_portal=SimpleNamespace(sessions=SimpleNamespace(create=self._service(
                "billing_portal.sessions.create", lambda params=None: StripeLike({"url": "https://portal.test/p"})))),
        )

    # --- test setup -------------------------------------------------------------

    def fail(self, name, error):
        self._failures[name] = error

    def called(self, name):
        return [argument for called, argument in self.calls if called == name]

    def add_customer(self, customer_id, email, username=None, payment_method=None):
        self._clock += 1
        customer = StripeLike({
            "id": customer_id, "object": "customer", "email": email, "created": self._clock,
            "metadata": StripeLike({"username": username} if username else {}),
            "invoice_settings": StripeLike({"default_payment_method": payment_method}),
            "default_source": None,
        })
        self.customers[customer_id] = customer
        return customer

    def add_subscription(self, customer_id, status, subscription_id=None, product=FAKE_PRODUCT_ID,
                         expanded_product=False):
        """A subscription to `product`, this site's by default. Stripe names a
        price's product by id unless the request expanded it into an object."""
        self._clock += 1
        price_id = FAKE_PRICE_ID if product == FAKE_PRODUCT_ID else f"price_of_{product}"
        subscription = StripeLike({
            "id": subscription_id or f"sub_{len(self.subscriptions) + 1}", "customer": customer_id,
            "status": status, "created": self._clock, "cancel_at_period_end": False,
            "items": StripeLike({"data": [StripeLike({
                "id": "si_1", "current_period_end": 1_800_000_000,
                "price": StripeLike({"id": price_id, "nickname": "Pro",
                                     "product": StripeLike({"id": product}) if expanded_product else product}),
            })]}),
        })
        self.subscriptions.append(subscription)
        return subscription

    # --- the services -----------------------------------------------------------

    def _service(self, name, implementation):
        def call(*args, options=None, **kwargs):
            # The params alone for most calls; (id, params) for update and retrieve.
            self.calls.append((name, args[0] if len(args) == 1 else args))
            if name in self._failures:
                raise self._failures[name]
            return implementation(*args, **kwargs)
        return call

    @staticmethod
    def _missing(kind, object_id):
        return stripe.InvalidRequestError(f"No such {kind}: '{object_id}'", "id",
                                          code="resource_missing", http_status=404)

    def _newest_first(self, objects):
        return sorted(objects, key=lambda obj: obj["created"], reverse=True)

    def _list_customers(self, params=None):
        email = (params or {}).get("email")
        found = [c for c in self.customers.values() if email is None or c["email"] == email]
        return StripeLike({"data": self._newest_first(found)})

    def _search_customers(self, params):
        match = re.fullmatch(r'email:"((?:[^"\\]|\\.)*)"', params["query"])
        assert match, f"unexpected search query {params['query']!r}"
        email = re.sub(r"\\(.)", r"\1", match.group(1)).lower()
        found = [c for c in self.customers.values() if (c["email"] or "").lower() == email]
        return StripeLike({"data": self._newest_first(found)})

    def _retrieve_customer(self, customer_id, params=None):
        if customer_id not in self.customers:
            raise self._missing("customer", customer_id)
        return self.customers[customer_id]

    def _update_customer(self, customer_id, params=None):
        customer = self._retrieve_customer(customer_id)
        # Stripe merges metadata keys rather than replacing the whole map.
        customer["metadata"].update((params or {}).get("metadata", {}))
        return customer

    def _create_customer(self, params=None):
        params = params or {}
        return self.add_customer(f"cus_new{len(self.customers) + 1}", params.get("email"),
                                 (params.get("metadata") or {}).get("username"))

    def _list_subscriptions(self, params=None):
        customer_id = params["customer"]
        if customer_id not in self.customers:
            raise self._missing("customer", customer_id)
        found = self._newest_first([s for s in self.subscriptions if s["customer"] == customer_id])
        after = params.get("starting_after")
        if after:
            found = found[[s["id"] for s in found].index(after) + 1:]
        limit = params.get("limit", 10)
        return StripeLike({"data": found[:limit], "has_more": len(found) > limit})

    def _create_subscription(self, params=None):
        self._retrieve_customer(params["customer"])
        subscription = self.add_subscription(params["customer"], "trialing" if params.get("trial_end") else "active")
        subscription.update({"trial_end": params.get("trial_end"), "metadata": StripeLike(params.get("metadata", {}))})
        return subscription

    def _update_subscription(self, subscription_id, params=None):
        subscription = next(s for s in self.subscriptions if s["id"] == subscription_id)
        subscription.update(params or {})
        return subscription


@pytest.fixture
def fake_stripe(monkeypatch):
    """A FakeStripe installed wherever the server holds a Stripe client."""
    from api import payment
    from utils import stripe_utils

    fake = FakeStripe()
    monkeypatch.setattr(stripe_utils, "stripe_client", fake)
    monkeypatch.setattr(payment, "stripe_client", fake)
    stripe_utils._product_cache.update(value=None, fetched_at=0.0)
    stripe_utils._price_cache.update(value=None, fetched_at=0.0)
    yield fake
    stripe_utils._product_cache.update(value=None, fetched_at=0.0)
    stripe_utils._price_cache.update(value=None, fetched_at=0.0)
