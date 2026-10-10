import os
from typing import Annotated

import stripe
from fastapi import APIRouter, Depends, Form, Request
from pydantic import BaseModel
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse, Response
from stripe.params import (
    CustomerCreateParams,
    CustomerRetrieveParams,
    SubscriptionUpdateParams,
)
from stripe.params.billing_portal import SessionCreateParams as PortalSessionCreateParams
from stripe.params.checkout import SessionCreateParams, SessionCreateParamsLineItem

import config
import database
from database import DeviceResult, update_user_customer_id
from utils import jwt_utils
from utils.jwt_utils import RequiredToken
from utils.logging_config import logger
from utils.ratelimit import device_check_rate_limit
from utils.stripe_utils import (
    ACTIVE_STATUSES,
    billing_unavailable,
    customer_subscription,
    field,
    find_linkable_customer,
    find_subscription,
    forget_customer_lookup,
    get_default_price_id,
    get_public_price,
    get_subscription_status,
    invalidate_subscription_status,
    link_customer,
    resolve_customer_id,
    stripe_client,
    subscription_period_end,
    this_product_item,
)

try:
    from utils.jwt_utils import SESSION_COOKIE_MAX_AGE_SECONDS
except ImportError:  # jwt_utils is being reworked in parallel
    SESSION_COOKIE_MAX_AGE_SECONDS = config.SESSION_TOKEN_TTL_MINUTES * 60

router = APIRouter(prefix="/subscription")

# Pricing lives in the environment so changing a plan is not a code change.
DEFAULT_PLAN_ID = os.getenv("STRIPE_DEFAULT_PLAN_ID", "pro")
PRICE_PLAN_MAP = {
    price.strip(): plan.strip()
    for price, _, plan in (
        entry.partition("=")
        for entry in os.getenv("STRIPE_PRICE_PLAN_MAP", "price_1Sw7fiIVyiVj53XrmnQKBjUb=pro").split(",")
    )
    if price.strip() and plan.strip()
}

ALREADY_SUBSCRIBED_MESSAGE = "This account already has an active subscription."
VERIFY_EMAIL_FIRST_MESSAGE = (
    "Verify your email address before subscribing. Check your inbox for the link, "
    "or send a new one from the Subscription page."
)

# Names the page where the user can fix it, since the self-service reset lives
# there. Note that the patched apps' runtime (Paywall.java) does NOT show this
# today: it only checks for a 200 and kills the app on anything else, body
# unread. The text is for API clients, and for a future runtime that reads it;
# APKs already in the field will keep closing silently at the limit.
DEVICE_LIMIT_MESSAGE = (
    f"This account is already active on {config.MAX_DEVICES_ALLOWED} devices. "
    f"To use it on this one, go to {config.SITE_HOST}/subscription. "
    f"Reset your devices there, once every {config.DEVICE_RESET_INTERVAL_DAYS} days."
)


class CheckoutRequest(BaseModel):
    priceId: str | None = None


def _client_ip(request: Request) -> str:
    """request.client is None behind some ASGI servers and in tests."""
    return request.client.host if request.client else "unknown"


def _user_agent(request: Request) -> str:
    return request.headers.get("user-agent", "unknown")


def _plan_id_for_price(price_id: str) -> str:
    return PRICE_PLAN_MAP.get(price_id, DEFAULT_PLAN_ID)


@router.get("/price")
def get_price() -> dict:
    """The plan's price, for display. Public: signed-out visitors see it too.

    503 when Stripe cannot say. The clients then hide the amount; neither ships
    a hardcoded fallback, which would go wrong the day the price changes.
    """
    return get_public_price()


@router.get("")
def get_subscription(token: RequiredToken) -> dict:
    """Current user's subscription, in the shape the frontend renders.

    This is also where a customer made by hand in the Stripe dashboard gets
    linked: the web Subscription page and the store app's Plan tab both load it,
    so opening either is all an account has to do (see resolve_customer_id).
    """
    customer_id = resolve_customer_id(token.username)
    if not customer_id:
        return {"subscription": None}

    sub = find_subscription(customer_id)
    if sub is None:
        return {"subscription": None}

    # find_subscription only returns subscriptions to this product; the plan is
    # read off that product's item, in case one subscription mixes products.
    price = field(this_product_item(sub), "price")
    price_id = field(price, "id")

    return {
        "subscription": {
            "planId": _plan_id_for_price(price_id) if price_id else "free",
            "planName": (field(price, "nickname") or "Pro") if price else "Free",
            "status": field(sub, "status"),
            "currentPeriodEnd": subscription_period_end(sub),
            "cancelAtPeriodEnd": bool(field(sub, "cancel_at_period_end", False)),
        }
    }


def _reconcile_user(customer_id: str):
    """Attach a Stripe customer we have never seen to a local account.

    The APK's token carries a customer_id whose row has gone missing. Matching
    on the username we stamped into the customer's metadata at checkout is the
    only link we trust; there is deliberately no email fallback.
    """
    try:
        customer = stripe_client.v1.customers.retrieve(customer_id, CustomerRetrieveParams())
    except stripe.StripeError:
        logger.exception('Could not retrieve Stripe customer %s', customer_id)
        raise billing_unavailable()

    username = field(field(customer, "metadata"), "username")
    if username:
        logger.info('Reconciling customer %s onto user %s from Stripe metadata', customer_id, username)
        update_user_customer_id('username', username, customer_id)
        return database.get_user_by_customer_id(customer_id)

    # No email fallback. This endpoint is unauthenticated (phones post their
    # own token), and registration never verifies an address, so matching on
    # email here would let one account absorb another's subscription.
    logger.warning('Stripe customer %s carries no username metadata; refusing to reconcile', customer_id)
    return None


@router.post("/is_device_allowed", dependencies=[Depends(device_check_rate_limit)])
def is_device_allowed(
        request: Request,
        token: Annotated[str, Form()] = '',
        device: Annotated[str, Form()] = '',
        sku: Annotated[str, Form()] = 'unknown',
) -> dict:
    """Called by patched APKs with a form-encoded app token. Do not change the request shape."""
    token_data = jwt_utils.get_app_token_data(token)
    if not device:
        logger.warning('is_device_allowed without a device id for customer %s from %s',
                       token_data.customer_id, _client_ip(request))
        raise HTTPException(status_code=400, detail="Device id is required")

    combined_fingerprint = f"{device}_{sku}"
    user = database.get_user_by_customer_id(token_data.customer_id)
    if not user:
        logger.warning('No user for customer_id %s, attempting reconciliation', token_data.customer_id)
        user = _reconcile_user(token_data.customer_id)
        if not user:
            logger.error('No user for customer_id %s after reconciliation, ip: %s, user_agent: %s',
                         token_data.customer_id, _client_ip(request), _user_agent(request))
            raise HTTPException(status_code=404, detail="User not found")

    if get_subscription_status(token_data.customer_id) not in ACTIVE_STATUSES:
        logger.info('Subscription inactive for customer %s, ip: %s', token_data.customer_id, _client_ip(request))
        raise HTTPException(status_code=402, detail="Subscription required")

    if combined_fingerprint in user.device_fingerprints:
        return {"allowed": True}

    # The DB applies the limit inside one transaction, so there is no
    # check-then-write window here. `is not False` would have read every
    # DeviceResult member as success, including LIMIT_REACHED.
    result = database.update_user_device_fingerprints(user.username, combined_fingerprint)
    if result:
        return {"allowed": True}
    if result is DeviceResult.ERROR:
        logger.error('Could not record device for user %s, customer %s', user.username, token_data.customer_id)
        raise HTTPException(status_code=500, detail="Could not register this device, please try again")
    if result is DeviceResult.USER_NOT_FOUND:
        logger.error('User %s vanished while registering a device', user.username)
        raise HTTPException(status_code=404, detail="User not found")

    logger.info('Device limit reached for user %s, customer %s, ip: %s, user_agent: %s',
                user.username, token_data.customer_id, _client_ip(request), _user_agent(request))
    raise HTTPException(status_code=403, detail=DEVICE_LIMIT_MESSAGE)


def _set_session_cookie(response: Response, user, client: str | None) -> None:
    """Re-mint the cookie, stamped with the CALLER's client.

    Not always "web": a phone's token reaches here too, and stamping its cookie
    "web" would launder an unrevocable mobile token into one the admin panel
    accepts. A legacy cookie stays None, a web one stays "web".
    """
    response.set_cookie(
        key="token",
        value=f'Bearer {jwt_utils.create_access_token(user, client=client)}',
        httponly=True,
        samesite="lax",
        secure=config.IS_PRODUCTION,
        max_age=SESSION_COOKIE_MAX_AGE_SECONDS,
    )


def _refresh_session_cookie(response: Response, username: str, client: str | None) -> None:
    """Re-mint the cookie from the row after a link.

    Nothing on the server reads the token's customer_id claim any more (see
    resolve_customer_id), so this is not what grants access. It keeps the claim
    in step with the row for anything that still looks at it.
    """
    user = database.get_user(username=username)
    if user is None:
        logger.error('User %s vanished while linking a customer', username)
        raise HTTPException(status_code=500, detail="Could not confirm subscription")
    _set_session_cookie(response, user, client)


def _link_customer(response: Response, user, customer, client: str | None) -> None:
    """Link a Stripe customer this account owns (see link_customer), and re-mint the cookie.

    Used by the checkout guard, which can discover a paid customer the row does
    not know about yet. Raises a 500 when the row cannot be written, and
    link_customer's 503 when the customer cannot be tagged.
    """
    if not link_customer(user, customer):
        raise HTTPException(status_code=500, detail="Could not confirm subscription")
    _refresh_session_cookie(response, user.username, client)


def _scan_customers(*customer_ids):
    """(the first of these customers with an active subscription or None,
    the set of them Stripe no longer has).

    Each distinct id costs one Stripe call. In the usual case the stamped and the
    looked-up customer are the same one, so that is a single call.
    """
    seen, missing = set(), set()
    for customer_id in customer_ids:
        if not customer_id or customer_id in seen:
            continue
        seen.add(customer_id)
        exists, sub = customer_subscription(customer_id)
        if not exists:
            missing.add(customer_id)
        elif sub is not None and field(sub, "status") in ACTIVE_STATUSES:
            return customer_id, missing
    return None, missing


def _require_verified_email(token):
    """The account's row; 403 unless its address is verified, 401 if it is gone.

    Read from the row, never from the token: a session token is minted at sign-in
    and lives for hours in a cookie and up to 90 days on a phone, so a flag
    carried in it would keep a user who has just opened the link locked out
    until they signed in again. The Stripe customer is created with the
    account's address, and until that address is proven the account could be
    using someone else's.
    """
    user = database.get_user(token.username)
    if user is None:
        logger.warning('Refused checkout for user %s: the account no longer exists', token.username)
        raise jwt_utils.CREDENTIALS_EXCEPTION
    if user.email_verified_at is None:
        logger.info('Refused checkout for user %s: email address not verified', token.username)
        raise HTTPException(status_code=403, detail=VERIFY_EMAIL_FIRST_MESSAGE)
    return user


def _already_subscribed(user, unlinked_customer, client: str | None) -> JSONResponse:
    """The 409 for a second checkout, linking the paying customer on the way out.

    `unlinked_customer` is the paying customer when the row does not name it
    yet, else None. That means the account paid but confirm_success never ran -
    the tab was closed on Stripe's redirect, say - or that its subscription was
    made by hand in the Stripe dashboard. The user is told they are already
    subscribed, so the account must actually be subscribed on our side by the
    time they read it. A failed link is logged and still answers 409: refusing
    a duplicate charge matters more than the link.
    """
    response = JSONResponse(status_code=409, content={"detail": ALREADY_SUBSCRIBED_MESSAGE})
    if unlinked_customer is not None:
        try:
            _link_customer(response, user, unlinked_customer, client)
            logger.info('Linked customer %s to user %s from the checkout guard',
                        field(unlinked_customer, "id"), user.username)
        except HTTPException:
            pass
    return response


@router.post("/create-checkout")
def create_checkout_session(request: Request, body: CheckoutRequest, token: RequiredToken):
    """Create a Stripe Checkout session for subscription.

    Refused with a 403 until the account's email address is verified, and with
    a 409 while the account already has an active subscription: Checkout would
    happily start a second one on the same customer and bill the user twice for
    one account.
    """
    # First, before any Stripe call: an account that may not subscribe should
    # cost nothing, not even the lookups the duplicate-subscription guard makes.
    # Everything below reads this row, never the token, whose customer_id was
    # stamped at sign-in and may predate the account's link.
    user = _require_verified_email(token)
    # The price actually charged is the product's, never the one the browser sent.
    price_id = get_default_price_id()
    # Whatever a page load remembered, this decision gets a fresh answer.
    forget_customer_lookup(user.username)
    # Only reuse a customer this account owns: one tagged with its username, or
    # one made in the dashboard with its verified address (find_linkable_customer
    # has the rules). A shared email must not attach someone else's saved
    # payment methods to this checkout.
    owned = find_linkable_customer(user)
    owned_customer_id = field(owned, "id")
    # The stamped customer is checked too, and first: it is ours by construction
    # (only a verified link writes it), and the email lookup misses it once the
    # address was edited in the portal.
    active, missing = _scan_customers(user.customer_id, owned_customer_id)
    invalidate_subscription_status(user.customer_id, owned_customer_id)
    if active is not None:
        logger.info('Refused a second checkout for user %s (customer %s)', user.username, active)
        # `active` is one of the two scanned, so only the owned one can be unlinked.
        return _already_subscribed(user, owned if active != user.customer_id else None, token.client)

    # The row is the source of truth, so its customer is the one to pay on: a
    # subscription started on any other would leave the account pointing at a
    # customer that does not have it. Reusing a stamped customer Stripe no
    # longer has would fail every checkout with "No such customer", so then the
    # owned one, or a fresh one, is used instead.
    stamped = user.customer_id if user.customer_id not in missing else None
    customer_id = stamped or owned_customer_id
    try:
        if not customer_id:
            customer = stripe_client.v1.customers.create(CustomerCreateParams(
                email=user.email,
                metadata={"username": user.username},
            ))
            customer_id = field(customer, "id")

        session = stripe_client.v1.checkout.sessions.create(SessionCreateParams(
            mode="subscription",
            customer=customer_id,
            line_items=[SessionCreateParamsLineItem(price=price_id, quantity=1)],
            success_url=f"{config.FRONTEND_URL}/subscription?success=true",
            cancel_url=f"{config.FRONTEND_URL}/subscription?canceled=true",
        ))
    except stripe.StripeError:
        logger.exception('Checkout session failed for user %s, ip: %s', token.username, _client_ip(request))
        raise billing_unavailable()

    return {"url": field(session, "url")}


@router.post('/confirm_success')
def confirm_success(request: Request, response: Response, token: RequiredToken) -> dict:
    """Poll-side completion of checkout: link the account's Stripe customer to it.

    The web Subscription page also calls this, quietly, whenever it finds no
    subscription, so it is one more way a customer made in the dashboard gets linked.
    """
    user = database.get_user(token.username)
    if user is None:
        raise jwt_utils.CREDENTIALS_EXCEPTION
    # Before the early return: a lapsed subscriber who pays again already has a
    # customer stamped, and this call is the only signal that their status changed.
    invalidate_subscription_status(user.customer_id)
    if user.customer_id:
        return {"message": "Subscription already confirmed"}

    # Strict: the user was just sent back from paying, so a remembered miss is
    # exactly the wrong answer, and an outage is a 503 they can retry.
    customer_id = resolve_customer_id(user.username, strict=True)
    if customer_id is None:
        logger.error('No Stripe customer owned by user: %s, ip: %s, user_agent: %s', token.username,
                     _client_ip(request), _user_agent(request))
        raise HTTPException(status_code=404, detail="Customer not found")
    # resolve_customer_id only links a customer that holds a subscription to this
    # product, so this is a belt-and-braces check on what it linked.
    if find_subscription(customer_id) is None:
        logger.error('No subscription found for user: %s, ip: %s, user_agent: %s', token.username,
                     _client_ip(request), _user_agent(request))
        raise HTTPException(status_code=404, detail="No active subscription found")
    _refresh_session_cookie(response, user.username, token.client)
    return {"message": "Subscription confirmed"}


@router.post("/billing-portal")
def create_billing_portal(token: RequiredToken) -> dict:
    """Create a Stripe Billing Portal session."""
    customer_id = resolve_customer_id(token.username)
    if not customer_id:
        logger.error('No customer found: %s', token.username)
        raise HTTPException(status_code=404, detail="No customer found")
    # The portal is where plans are cancelled or changed. Nothing tells us when
    # the user is done there, so dropping the entry now at least makes the next
    # check after they come back a fresh one.
    invalidate_subscription_status(customer_id)
    try:
        session = stripe_client.v1.billing_portal.sessions.create(PortalSessionCreateParams(
            customer=customer_id,
            return_url=f"{config.FRONTEND_URL}/subscription",
        ))
    except stripe.StripeError:
        logger.exception('Billing portal session failed for user %s', token.username)
        raise billing_unavailable()

    return {"url": field(session, "url")}


@router.post("/cancel")
def cancel_subscription(token: RequiredToken) -> dict:
    """Cancel the user's subscription at period end."""
    logger.debug('Cancel subscription request for user %s', token.username)
    customer_id = resolve_customer_id(token.username)
    if not customer_id:
        raise HTTPException(status_code=404, detail="No customer found")

    sub = find_subscription(customer_id)
    if sub is None or field(sub, "status") not in ACTIVE_STATUSES:
        raise HTTPException(status_code=404, detail="No active subscription")

    try:
        stripe_client.v1.subscriptions.update(
            field(sub, "id"), SubscriptionUpdateParams(cancel_at_period_end=True)
        )
    except stripe.StripeError:
        logger.exception('Cancel failed for user %s', token.username)
        raise billing_unavailable()
    finally:
        invalidate_subscription_status(customer_id)

    return {"success": True, "message": "Subscription will be cancelled at period end"}


@router.post("/reactivate")
def reactivate_subscription(token: RequiredToken) -> dict:
    """Reactivate a subscription that is set to cancel at period end."""
    logger.debug('Reactivate subscription request for user %s', token.username)
    customer_id = resolve_customer_id(token.username)
    if not customer_id:
        raise HTTPException(status_code=404, detail="No customer found")

    sub = find_subscription(customer_id)
    if sub is None or field(sub, "status") not in ACTIVE_STATUSES:
        raise HTTPException(status_code=404, detail="No subscription found")

    try:
        stripe_client.v1.subscriptions.update(
            field(sub, "id"), SubscriptionUpdateParams(cancel_at_period_end=False)
        )
    except stripe.StripeError:
        logger.exception('Reactivate failed for user %s', token.username)
        raise billing_unavailable()
    finally:
        invalidate_subscription_status(customer_id)

    return {"success": True, "message": "Subscription reactivated"}
