import threading
import time
from datetime import datetime, timezone
from typing import Annotated

import stripe
from fastapi import Depends
from pydantic import BaseModel
from starlette.exceptions import HTTPException
from stripe import StripeClient
from stripe.params import (
    CustomerListParams,
    CustomerSearchParams,
    CustomerUpdateParams,
    SubscriptionListParams,
)

import config
import database
from utils.jwt_utils import CREDENTIALS_EXCEPTION, RequiredToken, UserInDB
from utils.logging_config import logger

# Every Stripe call runs on a request thread. The library's default is an 80 s
# timeout with retries on top, so during a Stripe brown-out each call could pin a
# threadpool worker for minutes - and patched apps call is_device_allowed on
# every launch, so a few dozen phones were enough to exhaust the pool and take
# the whole API down with Stripe. Ten seconds is far above Stripe's normal
# latency; one retry absorbs a dropped connection without doubling a real outage.
STRIPE_TIMEOUT_SECONDS = 10
STRIPE_MAX_NETWORK_RETRIES = 1

# Constructing the client performs no I/O; nothing in this module talks to
# Stripe until a request asks it to.
stripe.api_key = config.STRIPE_API_KEY
PRODUCT_ID = config.STRIPE_PRODUCT_ID
stripe_client = StripeClient(
    config.STRIPE_API_KEY,
    # RequestsClient is what the library would pick on its own (requests is one
    # of its dependencies); it is named here only because it is the one that
    # takes a timeout.
    http_client=stripe.RequestsClient(timeout=STRIPE_TIMEOUT_SECONDS),
    max_network_retries=STRIPE_MAX_NETWORK_RETRIES,
)

ACTIVE_STATUSES = ("active", "trialing")

# Stripe returns subscriptions newest-first, so a subscription cancelled after
# an active one was created would win a naive `limit=1` lookup.
_STATUS_RANK = {
    "active": 0,
    "trialing": 1,
    "past_due": 2,
    "unpaid": 3,
    "incomplete": 4,
    "paused": 5,
}
# A customer's subscriptions are read in full, page by page: the Stripe account
# also sells another product, whose customers pile up dozens of subscriptions
# (incomplete_expired ones especially), and a subscription to this product
# must be found however many of those are newer. The page cap only bounds a
# pathological customer.
_SUBSCRIPTION_PAGE_SIZE = 100
_SUBSCRIPTION_MAX_PAGES = 10

PRODUCT_CACHE_TTL_SECONDS = 300
_product_cache = {"value": None, "fetched_at": 0.0}

# The public price is shown on every visit to the landing and subscription
# pages, by visitors who are not signed in, so it must not cost a Stripe call
# each. Ten minutes is how long a price change takes to show up on the site.
PRICE_CACHE_TTL_SECONDS = 600
_price_cache = {"value": None, "fetched_at": 0.0}

# Patched apps ask is_device_allowed on every launch, and each ask used to be a
# Stripe round trip. A minute of staleness is invisible to a paying user, and
# every endpoint that can change the answer (checkout, confirm_success, the
# billing portal, cancel, reactivate, a device reset) drops the entry itself, so
# the minute only ever applies to changes made on Stripe's side.
SUBSCRIPTION_STATUS_TTL_SECONDS = 60
# One entry is a customer id and a short status string. The bound is only there
# so a flood of forged-but-valid customer ids cannot grow the dict forever.
SUBSCRIPTION_STATUS_CACHE_SIZE = 10_000

# An account with no linked customer costs a Stripe lookup (or two) each time it
# asks for its subscription, which the Subscription page and the store app's
# Plan tab do on every visit, and most accounts never subscribe. So a miss is
# remembered for a minute: a customer made in the dashboard is linked at most a
# minute after it exists. Checkout, confirm_success and a device reset forget
# the miss, and a newly verified address skips it (see _lookup_fingerprint).
CUSTOMER_LOOKUP_TTL_SECONDS = 60
CUSTOMER_LOOKUP_CACHE_SIZE = 10_000
# Stripe's page size ceiling. Far more than one address ever has customers.
_CUSTOMER_PAGE_SIZE = 100

BILLING_UNAVAILABLE = "Billing is temporarily unavailable, please try again shortly"


class TTLCache:
    """A small thread-safe TTL cache, bounded by evicting the oldest insert.

    Lookups run on FastAPI's threadpool, hence the lock. Writes carry the
    generation read BEFORE the slow lookup started; any invalidation in between
    bumps the generation and the write is dropped. Without that, a status fetched
    just before a /cancel and stored just after it would be served for a minute
    as if the cancel had never happened. The counter is global rather than per
    key, so an unrelated invalidation occasionally costs one extra lookup - a
    fair price for not having to bound a second dict.
    """

    def __init__(self, ttl_seconds: float, max_entries: int, clock=time.monotonic):
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._clock = clock
        self._entries = {}  # insertion-ordered, which makes the oldest easy to find
        self._generation = 0
        self._lock = threading.Lock()

    def __len__(self) -> int:
        with self._lock:
            return len(self._entries)

    def generation(self) -> int:
        with self._lock:
            return self._generation

    def get(self, key):
        """The cached value, or None when absent or expired."""
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return None
            value, stored_at = entry
            if self._clock() - stored_at >= self.ttl_seconds:
                del self._entries[key]
                return None
            return value

    def put(self, key, value, generation: int) -> bool:
        with self._lock:
            if generation != self._generation:
                return False
            self._entries.pop(key, None)
            self._entries[key] = (value, self._clock())
            while len(self._entries) > self.max_entries:
                del self._entries[next(iter(self._entries))]
            return True

    def invalidate(self, key) -> None:
        with self._lock:
            self._generation += 1
            self._entries.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._generation += 1
            self._entries.clear()


_status_cache = TTLCache(SUBSCRIPTION_STATUS_TTL_SECONDS, SUBSCRIPTION_STATUS_CACHE_SIZE)
# Keyed by lowercased username; see resolve_customer_id.
_no_customer_cache = TTLCache(CUSTOMER_LOOKUP_TTL_SECONDS, CUSTOMER_LOOKUP_CACHE_SIZE)


class SubscriptionInfo(BaseModel):
    client_id: str
    email: str
    status: str


def billing_unavailable() -> HTTPException:
    """Generic 503. Stripe error text can carry internal ids, so it never reaches a client."""
    return HTTPException(status_code=503, detail=BILLING_UNAVAILABLE)


def field(obj, name, default=None):
    """Read a field off a Stripe object.

    StripeObject subclasses dict, so `sub.items` is dict.items and not the
    subscription's line items - always go through the mapping.
    """
    if obj is None:
        return default
    if isinstance(obj, dict):
        value = obj.get(name)
    else:
        value = getattr(obj, name, None)
    return default if value is None else value


def get_product(force=False):
    """The Stripe product, fetched on first use and re-read every TTL.

    A stale copy is preferred over failing a request; only a cold cache turns a
    Stripe outage into a 503.
    """
    cached = _product_cache["value"]
    if cached is not None and not force and time.monotonic() - _product_cache["fetched_at"] < PRODUCT_CACHE_TTL_SECONDS:
        return cached
    try:
        product = stripe_client.v1.products.retrieve(PRODUCT_ID)
    except Exception:
        logger.exception('Could not retrieve Stripe product %s', PRODUCT_ID)
        if cached is not None:
            logger.warning('Serving stale Stripe product %s', PRODUCT_ID)
            return cached
        raise billing_unavailable()
    _product_cache["value"] = product
    _product_cache["fetched_at"] = time.monotonic()
    return product


def get_default_price_id() -> str:
    price = field(get_product(), "default_price")
    if isinstance(price, dict):
        price = field(price, "id")
    if not price:
        logger.error('Stripe product %s has no default price', PRODUCT_ID)
        raise billing_unavailable()
    return price


def get_public_price() -> dict:
    """What a subscription costs, for display: {amount, currency, interval, interval_count}.

    `amount` is Stripe's unit_amount, in the currency's minor units. The clients
    hide the amount when this fails rather than falling back to a hardcoded one,
    so it must never guess: a price with no single fixed amount (tiered or
    customer-chosen) or no billing interval is a 503, not a made-up number.
    Like the product, a stale copy is preferred over failing when Stripe is down.
    """
    cached = _price_cache["value"]
    if cached is not None and time.monotonic() - _price_cache["fetched_at"] < PRICE_CACHE_TTL_SECONDS:
        return cached
    try:
        price = stripe_client.v1.prices.retrieve(get_default_price_id())
    except Exception as error:
        # get_default_price_id raises (and has already logged) an HTTPException.
        if not isinstance(error, HTTPException):
            logger.exception('Could not retrieve the default price of %s', PRODUCT_ID)
        if cached is not None:
            logger.warning('Serving a stale price for %s', PRODUCT_ID)
            return cached
        raise billing_unavailable()

    recurring = field(price, "recurring")
    amount = field(price, "unit_amount")
    currency = field(price, "currency")
    interval = field(recurring, "interval")
    if amount is None or not currency or not interval:
        logger.error('Stripe price %s has no fixed recurring amount; not publishing one', field(price, "id"))
        raise billing_unavailable()

    public = {
        "amount": int(amount),
        "currency": str(currency).lower(),
        "interval": interval,
        "interval_count": int(field(recurring, "interval_count", 1)),
    }
    _price_cache["value"] = public
    _price_cache["fetched_at"] = time.monotonic()
    return public


def _item_product(item):
    """The product id of a subscription item's price. Stripe sends the id as a
    string unless the request expanded it, and then an object."""
    product = field(field(item, "price"), "product")
    if product is not None and not isinstance(product, str):
        product = field(product, "id")
    return product


def is_this_product(sub) -> bool:
    """Whether a subscription is to this site's product (config.STRIPE_PRODUCT_ID).

    The Stripe account also sells another product, to customers with the same
    kind of addresses. Its subscriptions must never count here: not for access,
    not for linking a customer, not for refusing a second checkout.
    """
    return any(_item_product(item) == PRODUCT_ID for item in field(field(sub, "items"), "data", []))


def this_product_item(sub):
    """The subscription's item for this site's product, or None."""
    return next((item for item in field(field(sub, "items"), "data", []) if _item_product(item) == PRODUCT_ID),
                None)


def _all_subscriptions(customer_id) -> tuple:
    """(customer exists, every subscription it has, to any product, in any status).

    A customer Stripe no longer has - deleted in the dashboard, or a test-mode id
    stamped before the site went live - is (False, []): it has no subscription,
    and treating its "No such customer" as an outage made every request for that
    account a 503 forever. Anything else still is one.
    """
    if not customer_id:
        return False, []
    subscriptions, starting_after = [], None
    try:
        for _ in range(_SUBSCRIPTION_MAX_PAGES):
            params = SubscriptionListParams(customer=customer_id, status="all", limit=_SUBSCRIPTION_PAGE_SIZE)
            if starting_after:
                params["starting_after"] = starting_after
            page = stripe_client.v1.subscriptions.list(params)
            data = list(field(page, "data", []))
            subscriptions.extend(data)
            if not data or not field(page, "has_more", False):
                break
            starting_after = field(data[-1], "id")
        else:
            logger.warning('Customer %s has more than %s subscriptions; read only the newest',
                           customer_id, len(subscriptions))
    except Exception as error:
        if isinstance(error, stripe.InvalidRequestError) and getattr(error, "code", None) == "resource_missing":
            logger.warning('Stripe has no customer %s; treating it as unsubscribed', customer_id)
            return False, []
        logger.exception('Stripe subscription lookup failed for customer %s', customer_id)
        raise billing_unavailable()
    return True, subscriptions


def customer_subscription(customer_id) -> tuple:
    """(customer exists, the subscription to this product that governs access, or None).

    Only subscriptions to this site's product are considered (is_this_product),
    so everything that reads a status - access, the device check, the billing
    actions, checkout's second-subscription guard, grant-trial - ignores the
    other product's. Every status is listed and ranked rather than trusting
    Stripe's newest-first ordering, so a cancelled subscription cannot mask an
    active one. See _all_subscriptions for a customer Stripe no longer has.
    """
    exists, subscriptions = _all_subscriptions(customer_id)
    ours = [sub for sub in subscriptions if is_this_product(sub)]
    if not ours:
        return exists, None
    # stable sort keeps Stripe's newest-first order inside a status group
    ours.sort(key=lambda sub: _STATUS_RANK.get(field(sub, "status"), 9))
    return True, ours[0]


def _touches_other_products(customer_id) -> bool:
    """Whether any subscription of this customer, in any status, involves another product."""
    _, subscriptions = _all_subscriptions(customer_id)
    return any(_item_product(item) != PRODUCT_ID
               for sub in subscriptions for item in field(field(sub, "items"), "data", []))


def find_subscription(customer_id):
    """The subscription that actually governs access, or None. See customer_subscription."""
    return customer_subscription(customer_id)[1]


def subscription_period_end(sub):
    """Current period end as an ISO-8601 string, or None.

    Stripe moved `current_period_end` from the subscription onto its items in
    the 2025-03-31 API version; accounts pinned to an older version still send
    the old shape, so both are accepted.
    """
    timestamp = field(sub, "current_period_end")
    if timestamp is None:
        for item in field(field(sub, "items"), "data", []):
            timestamp = field(item, "current_period_end")
            if timestamp:
                break
    if not timestamp:
        timestamp = field(sub, "cancel_at") or field(sub, "trial_end")
    if not timestamp:
        logger.warning('Subscription %s carries no current_period_end', field(sub, "id"))
        return None
    try:
        return datetime.fromtimestamp(int(timestamp), tz=timezone.utc).isoformat()
    except (TypeError, ValueError, OSError, OverflowError):
        logger.warning('Subscription %s has an unusable period end %r', field(sub, "id"), timestamp)
        return None


def get_subscription_status(customer_id: str) -> str:
    """'free' when the customer has no subscription at all; the Stripe status otherwise.

    A Stripe outage raises 503 rather than degrading to 'free', which would tell
    a paying customer their subscription had lapsed. Failures are never cached;
    answers are, for SUBSCRIPTION_STATUS_TTL_SECONDS (see _status_cache).
    """
    if not customer_id:
        return 'free'
    cached = _status_cache.get(customer_id)
    if cached is not None:
        return cached
    generation = _status_cache.generation()
    sub = find_subscription(customer_id)
    status = 'free' if sub is None else field(sub, "status", 'free')
    _status_cache.put(customer_id, status, generation)
    return status


def invalidate_subscription_status(*customer_ids) -> None:
    """Forget the cached status of each customer, so the next check asks Stripe."""
    for customer_id in customer_ids:
        if customer_id:
            _status_cache.invalidate(customer_id)


# --- which Stripe customer is an account's --------------------------------------------
#
# users.customer_id is the only answer the site acts on. A session token carries
# a customer_id claim too, but it is stamped when the token is minted: an account
# linked after signing in (by checkout on another device, by grant-trial, by the
# owner fixing the row) kept being refused until it signed in again, and every
# APK it downloaded meanwhile carried no customer at all. Nothing reads the claim
# any more; everything asks resolve_customer_id.

def _customer_tag(customer):
    """The username checkout stamps into a customer's metadata, or None."""
    return field(field(customer, "metadata"), "username") or None


def _linked_to_another_account(customer, user: UserInDB) -> bool:
    owner = database.get_user_by_customer_id(field(customer, "id"))
    return owner is not None and owner.username.lower() != user.username.lower()


def _may_link_by_email(user: UserInDB) -> bool:
    """Whether a customer that merely carries this account's address may become its own.

    Only once the address is proven: registration verifies nothing, so anyone
    could sign up with the address the owner made a trial for and walk off with
    it. And only while no other verified account uses the same address - the
    live table predates the unique index on email and holds duplicates, and
    handing the customer to whichever of them asked first would be a guess.
    """
    if not user.email or user.email_verified_at is None:
        return False
    verified = [other for other in database.get_users_by_email(user.email) if other.email_verified_at is not None]
    if len(verified) > 1:
        logger.warning('Not linking a Stripe customer to %s by email: %s verified accounts share the address',
                       user.username, len(verified))
        return False
    return True


def _search_by_email(email: str) -> list:
    """Customers whose address matches `email` in any case. Best effort.

    customers.list filters on the exact, case-sensitive string, and an address
    typed into the dashboard need not be cased as the user typed it at signup.
    Search matches case-insensitively, but it is only a supplement: its index
    lags writes by up to a minute, and a key without access to it must not break
    the lookup (or checkout) that the exact-case list already answers.
    """
    escaped = email.replace("\\", "\\\\").replace('"', '\\"')
    try:
        found = stripe_client.v1.customers.search(
            CustomerSearchParams(query=f'email:"{escaped}"', limit=_CUSTOMER_PAGE_SIZE))
    except stripe.StripeError:
        logger.exception('Stripe customer search failed; matching the address case-sensitively only')
        return []
    return list(field(found, "data", []))


def _is_live(sub) -> bool:
    return sub is not None and field(sub, "status") in ACTIVE_STATUSES


def _pick(pairs: list):
    """The (customer, subscription) whose subscription to this product is live, else the newest."""
    if not pairs:
        return None
    return min(pairs, key=lambda pair: (0 if _is_live(pair[1]) else 1, -int(field(pair[0], "created", 0))))


def _find(user: UserInDB, *, to_link: bool):
    """(customer, its subscription to this product or None) for this account, or None.

    Candidates, in order:
      1. customers tagged with this username - the metadata checkout stamps;
      2. only when _may_link_by_email allows it, untagged customers with this
         exact address (case aside) that have an ACTIVE or TRIALING
         subscription to this product. A customer of the other product carries
         an address just like ours, and its subscriptions count for nothing
         here, so the only proof a customer made by hand is this account's is
         a live subscription to this product on it.
    A customer tagged with another username, or linked to another account, is
    never a candidate. `to_link` also drops tagged customers that have never
    had a subscription to this product: a link is only ever made to a customer
    that holds one, so a tag the other product's backend may have written
    links nothing. Checkout leaves it off, to reuse the customer it made itself
    for a checkout that was never paid.

    Always asks Stripe; a failure is a 503, logged.
    """
    if not user.email:
        return None
    try:
        listed = list(field(stripe_client.v1.customers.list(
            CustomerListParams(email=user.email, limit=_CUSTOMER_PAGE_SIZE)), "data", []))
    except stripe.StripeError:
        logger.exception('Customer lookup failed for user %s', user.username)
        raise billing_unavailable()

    subscriptions = {}

    def with_subscriptions(customers):
        pairs = []
        for customer in customers:
            customer_id = field(customer, "id")
            if customer_id not in subscriptions:
                subscriptions[customer_id] = customer_subscription(customer_id)
            exists, sub = subscriptions[customer_id]
            if exists:
                pairs.append((customer, sub))
        return pairs

    def tagged(customers):
        pairs = with_subscriptions([c for c in customers if _customer_tag(c) == user.username
                                    and not _linked_to_another_account(c, user)])
        return [pair for pair in pairs if pair[1] is not None] if to_link else pairs

    own = tagged(listed)
    if own:
        return _pick(own)
    if not _may_link_by_email(user):
        return None

    seen = {field(c, "id") for c in listed}
    found = listed + [c for c in _search_by_email(user.email) if field(c, "id") not in seen]
    own = tagged(found)
    if own:
        return _pick(own)
    address = user.email.lower()
    untagged = [c for c in found
                if _customer_tag(c) is None
                and (field(c, "email") or '').lower() == address
                and not _linked_to_another_account(c, user)]
    return _pick([pair for pair in with_subscriptions(untagged) if _is_live(pair[1])])


def find_linkable_customer(user: UserInDB):
    """The customer checkout may reuse for this account, or None. See _find."""
    found = _find(user, to_link=False)
    return found[0] if found else None


def find_customer_to_link(user: UserInDB):
    """(customer, its subscription to this product) the account should be linked to, or None.

    What resolve_customer_id links and grant-trial reuses. See _find.
    """
    return _find(user, to_link=True)


def forget_customer_lookup(*usernames) -> None:
    """Drop a remembered "no customer" so the next lookup asks Stripe again."""
    for username in usernames:
        if username:
            _no_customer_cache.invalidate(username.lower())


def link_customer(user: UserInDB, customer) -> bool:
    """Make `customer` this account's. False when the row could not be written.

    An untagged customer is tagged with the username before the row is written,
    so _reconcile_user and checkout's metadata lookup find it from then on,
    whatever happens to the row. If that tag cannot be written it is a 503 and
    nothing is linked; the next lookup tries again. Stripe merges metadata
    keys, so anything else the owner put there is kept.

    The tag is never written where it could mislead the other product sold from
    the same Stripe account, whose backend may read metadata.username too: not
    over a tag naming someone else, and not onto a customer with any
    subscription to another product. Such a customer is linked by the row alone.
    """
    customer_id = field(customer, "id")
    tag = _customer_tag(customer)
    if tag is not None and tag != user.username:
        logger.warning('Stripe customer %s is tagged with user %s; linking it to %s without retagging it',
                       customer_id, tag, user.username)
    elif tag is None and _touches_other_products(customer_id):
        logger.info('Stripe customer %s also holds another product; linking it to %s without a tag',
                    customer_id, user.username)
    elif tag is None:
        try:
            stripe_client.v1.customers.update(
                customer_id, CustomerUpdateParams(metadata={"username": user.username}))
        except stripe.StripeError:
            logger.exception('Could not tag Stripe customer %s with user %s', customer_id, user.username)
            raise billing_unavailable()
    if not database.update_user_customer_id('username', user.username, customer_id):
        logger.error('Could not store customer %s on user %s', customer_id, user.username)
        return False
    invalidate_subscription_status(customer_id)
    forget_customer_lookup(user.username)
    logger.info('Linked Stripe customer %s to user %s', customer_id, user.username)
    return True


def _lookup_fingerprint(user: UserInDB) -> tuple:
    """What a remembered miss was computed from.

    A miss only stands while these are unchanged: the moment the address is
    verified (or changed), the next lookup asks Stripe again rather than serving
    a minute-old "unverified, so nothing" to someone who just opened the link.
    """
    return (user.email or '').lower(), user.email_verified_at is not None


def resolve_customer_id(username: str, *, strict: bool = False) -> str | None:
    """The Stripe customer id of this account, linking one if Stripe has it; else None.

    The row wins whenever it names a customer, and then this costs no Stripe
    call. Otherwise find_customer_to_link is asked and a find is linked (see
    link_customer); it only ever finds a customer holding a subscription to this
    product. A deleted account is a 401.

    By default this is for page loads: a miss is remembered for
    CUSTOMER_LOOKUP_TTL_SECONDS, and a Stripe failure reads as "no customer"
    (fail closed) and is remembered too. The linked accounts' own reads still
    503 in an outage as before; this only keeps the lookup that never existed
    for unlinked accounts from turning their page loads into 503s, or from
    pinning a worker for Stripe's timeout on every one of them. `strict` is for
    the callers that act on the answer (checkout's guards, confirm_success,
    grant-trial): it always asks Stripe and lets a failure raise its 503.
    """
    user = database.get_user(username)
    if user is None:
        raise CREDENTIALS_EXCEPTION
    if user.customer_id:
        return user.customer_id

    key, fingerprint = user.username.lower(), _lookup_fingerprint(user)
    if strict:
        forget_customer_lookup(user.username)
    elif _no_customer_cache.get(key) == fingerprint:
        return None
    generation = _no_customer_cache.generation()
    try:
        found = find_customer_to_link(user)
        if found is not None and link_customer(user, found[0]):
            return field(found[0], "id")
    except HTTPException as error:
        if strict or error.status_code != 503:
            raise
        logger.warning('Stripe customer lookup failed for user %s; answering "no customer" for now', username)
    _no_customer_cache.put(key, fingerprint, generation)
    return None


def get_subscription_info(token: RequiredToken) -> SubscriptionInfo:
    customer_id = resolve_customer_id(token.username)
    if not customer_id:
        logger.debug('User %s does not have a customer_id', token.username)
        raise HTTPException(status_code=402, detail="Subscription required")
    sub = find_subscription(customer_id)
    if sub is None or field(sub, "status") not in ACTIVE_STATUSES:
        logger.debug('User %s has no active subscription', token.username)
        raise HTTPException(status_code=402, detail="Subscription required")
    return SubscriptionInfo(
        client_id=customer_id,
        email=token.email or '',
        status=field(sub, "status"),
    )


RequiredSubscription = Annotated[SubscriptionInfo, Depends(get_subscription_info)]
