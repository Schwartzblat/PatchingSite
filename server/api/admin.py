"""The admin panel's API: every account, one account in detail, and the support
actions that used to need SSH and manage.py.

Who may use it is configuration (config.ADMIN_USERNAMES), never data: nothing
here can make an account an admin. Every write logs the admin who made it.
"""
import threading
from datetime import datetime, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, EmailStr
from starlette import status
from starlette.exceptions import HTTPException

import config
import database
from api.users import COOKIE_NAME
from utils import stripe_utils, trials
from utils.jwt_utils import CREDENTIALS_EXCEPTION, TOKEN_CLIENT_WEB, UserInDB, get_token_data
from utils.logging_config import logger

# One page of the list. The site has far fewer accounts; `q` narrows past it.
LIST_LIMIT = 200
NOT_AN_ADMIN = "This page is for administrators."
NO_SUCH_USER = "No such user."


def get_admin(request: Request) -> UserInDB:
    """The signed-in admin, else 401 (no usable session) or 403 (not an admin).

    The session cookie only. A phone's bearer token cannot be revoked and lives
    up to 90 days, and the panel is a web page, so a token lifted from a phone
    must not open it. get_token_data reads the cookie first whenever one is
    sent, so once the cookie is known to be there it is what gets validated,
    whatever Authorization header comes with it.

    The cookie check alone is not enough: /login and /auth/token mint the same
    kind of token under the same key, so a phone token can be sent as a cookie.
    The token's `client` claim says where it was minted and must be "web".
    Cookies minted before the claim existed carry none and are refused, so the
    admin signs in once after the deploy.
    """
    if not request.cookies.get(COOKIE_NAME, "").strip():
        raise CREDENTIALS_EXCEPTION
    token = get_token_data(request)
    if token.client != TOKEN_CLIENT_WEB:
        raise CREDENTIALS_EXCEPTION
    # The row, not the token: an account deleted since sign-in is refused.
    user = database.get_user(token.username)
    if user is None:
        raise CREDENTIALS_EXCEPTION
    if not config.is_admin(user.username):
        logger.warning('Refused the admin panel to %s', user.username)
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_ADMIN)
    return user


RequiredAdmin = Annotated[UserInDB, Depends(get_admin)]

# The guard is on the router, not only on each route's `admin` parameter: a
# route added later that forgets the parameter, or one hidden from the OpenAPI
# schema, is still refused. FastAPI caches a dependency per request, so routes
# that also take RequiredAdmin (for the admin's name) don't run it twice.
router = APIRouter(prefix="/admin", dependencies=[Depends(get_admin)])


def _utc(moment: datetime | None) -> str | None:
    """A moment as this API writes them: UTC, second precision, a Z.

    users.created_at comes from sqlite's CURRENT_TIMESTAMP, which is UTC but
    carries no zone, and astimezone() would read that as the server's local time.
    """
    if moment is None:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc).strftime(database.TIMESTAMP_FORMAT)


def _from_timestamp(value) -> str | None:
    try:
        return _utc(datetime.fromtimestamp(int(value), tz=timezone.utc)) if value else None
    except (TypeError, ValueError, OSError, OverflowError):
        return None


def _summary(user: UserInDB) -> dict:
    """One row of the list."""
    return {
        "username": user.username,
        "email": user.email,
        "email_verified": user.email_verified_at is not None,
        "created_at": _utc(user.created_at),
        "customer_id": user.customer_id or None,
        "devices_used": len(user.device_fingerprints),
        "is_admin": config.is_admin(user.username),
    }


def _detail(user: UserInDB) -> dict:
    return {**_summary(user), "devices_limit": config.MAX_DEVICES_ALLOWED,
            "devices_reset_at": _utc(user.devices_reset_at)}


def _target(username: str) -> UserInDB:
    user = database.get_user(username)
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NO_SUCH_USER)
    return user


def _describe_subscription(sub) -> dict:
    period_end = stripe_utils.subscription_period_end(sub)
    return {
        "id": stripe_utils.field(sub, "id"),
        "status": stripe_utils.field(sub, "status"),
        "trial_end": _from_timestamp(stripe_utils.field(sub, "trial_end")),
        "current_period_end": _utc(datetime.fromisoformat(period_end)) if period_end else None,
        "cancel_at_period_end": bool(stripe_utils.field(sub, "cancel_at_period_end", False)),
        # manage.py grant-trial and the panel both write it; a dashboard trial has none.
        "granted_by": stripe_utils.field(stripe_utils.field(sub, "metadata"), "granted_by"),
    }


def _subscription(user: UserInDB) -> dict:
    """{subscription, stripe} for the account, from its LINKED customer only.

    Never resolve_customer_id: that links a customer it finds, and looking at an
    account must not change it. An unlinked account shows no subscription; a
    trial granted from here finds and links the right customer itself. A Stripe
    outage is reported rather than raised, so the account still shows.
    """
    customer_id = user.customer_id or None
    stripe_info = {
        "customer_id": customer_id,
        "dashboard_url": config.stripe_dashboard_url(customer_id) if customer_id else None,
        "customer_missing": False,
        "error": None,
        "test_mode": config.STRIPE_TEST_MODE,
    }
    if customer_id is None:
        return {"subscription": None, "stripe": stripe_info}
    try:
        exists, sub = stripe_utils.customer_subscription(customer_id)
    except HTTPException as error:
        stripe_info["error"] = error.detail
        return {"subscription": None, "stripe": stripe_info}
    stripe_info["customer_missing"] = not exists
    return {"subscription": _describe_subscription(sub) if sub is not None else None, "stripe": stripe_info}


@router.get("/users")
def list_users(admin: RequiredAdmin, q: Annotated[str, Query(max_length=254)] = "") -> dict:
    """The accounts, from the database alone: fast, and still there in a Stripe outage."""
    users, total = database.list_users(q.strip(), LIST_LIMIT)
    return {"users": [_summary(user) for user in users], "total": total, "limit": LIST_LIMIT,
            "devices_limit": config.MAX_DEVICES_ALLOWED}


@router.get("/users/{username}")
def read_user(username: str, admin: RequiredAdmin) -> dict:
    user = _target(username)
    return {"user": _detail(user), **_subscription(user)}


# --- account changes ------------------------------------------------------------------

class EmailChange(BaseModel):
    email: EmailStr
    # An address typed in here is not proven unless the admin says so.
    verified: bool = False


@router.post("/users/{username}/email")
def change_email(username: str, body: EmailChange, admin: RequiredAdmin) -> dict:
    """A new address for the account. The Stripe customer's own address is not
    changed: the customer may hold the other product's subscriptions, whose
    billing mail goes there."""
    user = _target(username)
    if body.email == user.email:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="That is already the account's email.")
    now = datetime.now(timezone.utc).replace(microsecond=0)
    if body.email.lower() == user.email.lower():
        # EmailStr lowercases the domain, so an older row with a capital in its
        # domain differs from the prefilled address in case alone. It is the same
        # mailbox: saving it must not throw away a verification that was proven.
        verified_at = user.email_verified_at or (now if body.verified else None)
    else:
        verified_at = now if body.verified else None
    result = database.set_email(user.username, body.email, verified_at)
    if result is database.EmailChangeResult.USER_NOT_FOUND:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NO_SUCH_USER)
    if result is database.EmailChangeResult.TAKEN:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That email is already in use.")
    # A remembered "no customer" was worked out from the old address.
    stripe_utils.forget_customer_lookup(user.username)
    logger.info('Admin %s changed the email of %s (%s)', admin.username, user.username,
                "verified" if body.verified else "unverified")
    return {"user": _detail(_target(user.username))}


@router.post("/users/{username}/verify-email")
def verify_email(username: str, admin: RequiredAdmin) -> dict:
    """Mark the current address verified. An earlier stamp is kept."""
    user = _target(username)
    if user.email_verified_at is None:
        database.mark_email_verified(user.username, user.email, datetime.now(timezone.utc).replace(microsecond=0))
        stripe_utils.forget_customer_lookup(user.username)
        logger.info('Admin %s marked the email of %s verified', admin.username, user.username)
    return {"user": _detail(_target(user.username))}


@router.post("/users/{username}/devices/reset")
def clear_devices(username: str, admin: RequiredAdmin) -> dict:
    """Free every device slot without spending the user's own reset."""
    user = _target(username)
    database.clear_devices(user.username)
    # As after the user's own reset: their phones are about to ask
    # is_device_allowed again, and should get a fresh answer from Stripe.
    stripe_utils.invalidate_subscription_status(user.customer_id)
    stripe_utils.forget_customer_lookup(user.username)
    logger.info('Admin %s cleared the device slots of %s', admin.username, user.username)
    return {"user": _detail(_target(user.username))}


@router.delete("/users/{username}")
def delete_user(username: str, admin: RequiredAdmin) -> dict:
    """Delete the account, as manage.py delete-user does. Stripe is left to the owner."""
    user = _target(username)
    if user.username.lower() == admin.username.lower():
        # The panel is the owner's way back in; deleting it from here would lock them out.
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can't delete your own account here.")
    deleted = database.delete_user(user.username)
    if deleted is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NO_SUCH_USER)
    stripe_utils.invalidate_subscription_status(deleted.customer_id)
    stripe_utils.forget_customer_lookup(deleted.username)
    logger.info('Admin %s deleted user %s', admin.username, deleted.username)
    customer_id = deleted.customer_id or None
    return {"deleted": deleted.username, "customer_id": customer_id,
            "dashboard_url": config.stripe_dashboard_url(customer_id) if customer_id else None}


# --- free trial -----------------------------------------------------------------------

class TrialRequest(BaseModel):
    # Exactly one: days from today, or the day it ends (YYYY-MM-DD). Either way
    # the trial ends at 00:00 UTC, as with manage.py grant-trial.
    days: int | None = None
    until: str | None = None


_REFUSAL_STATUS = {
    trials.TrialRefused.BAD_END: status.HTTP_400_BAD_REQUEST,
    trials.TrialRefused.UNVERIFIED: status.HTTP_409_CONFLICT,
    trials.TrialRefused.LIVE_SUBSCRIPTION: status.HTTP_409_CONFLICT,
    # The customer is linked to another account (users.customer_id is unique).
    trials.TrialRefused.NOT_STORED: status.HTTP_409_CONFLICT,
    trials.TrialRefused.STRIPE_REFUSED: status.HTTP_502_BAD_GATEWAY,
    trials.TrialRefused.STRIPE_UNAVAILABLE: status.HTTP_503_SERVICE_UNAVAILABLE,
}

# One grant at a time. A grant checks for a live subscription and then makes
# one, so two at once (a double click) could both pass the check. The service
# runs a single worker, so a process lock is enough.
_trial_lock = threading.Lock()


def _refused(refused: trials.TrialRefused) -> HTTPException:
    """The HTTP answer to a refusal. Stripe's own message is passed on: unlike
    billing_unavailable's callers, the reader here is the owner, who needs it
    (a restricted key missing a permission, say)."""
    detail = refused.message
    if refused.reason == trials.TrialRefused.UNVERIFIED:
        detail += " Mark the email verified first."
    if refused.partway:
        detail += " Nothing is charged; try again, it reuses the customer."
    return HTTPException(status_code=_REFUSAL_STATUS[refused.reason], detail=detail)


def _trial_end(body: TrialRequest, now: datetime) -> datetime:
    if (body.days is None) == (body.until is None):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST,
                            detail="Give either a number of days or an end date.")
    try:
        return trials.trial_end(body.days, body.until, now)
    except trials.TrialRefused as refused:
        raise _refused(refused)


@router.post("/users/{username}/trial/preview")
def preview_trial(username: str, body: TrialRequest, admin: RequiredAdmin) -> dict:
    """What a grant would do, the summary manage.py grant-trial prints before
    its prompt: above all whether a saved card will be charged at the end.
    Writes nothing."""
    user = _target(username)
    now = datetime.now(timezone.utc)
    end = _trial_end(body, now)
    try:
        plan = trials.plan(user, end, now)
    except trials.TrialRefused as refused:
        raise _refused(refused)
    return {
        "email": user.email,
        "customer_id": plan.customer_id,
        "customer_how": plan.how,
        "note": plan.note,
        "trial_end": _utc(plan.trial_end),
        "days": plan.days,
        "price_id": plan.price_id,
        "price": plan.price,
        "saved_card": plan.saved_card,
        "test_mode": config.STRIPE_TEST_MODE,
    }


@router.post("/users/{username}/trial")
def grant_trial(username: str, body: TrialRequest, admin: RequiredAdmin) -> dict:
    """Grant it. Every check runs again rather than trusting the preview, so a
    subscription made since then is still refused."""
    now = datetime.now(timezone.utc)
    with _trial_lock:
        user = _target(username)
        end = _trial_end(body, now)
        try:
            plan = trials.plan(user, end, now)
            subscription = trials.grant(plan, f"admin panel ({admin.username})")
        except trials.TrialRefused as refused:
            # Stripe's own text otherwise reaches only the admin's browser.
            logger.warning('Admin %s: free trial for %s refused (%s): %s',
                           admin.username, user.username, refused.reason, refused.message)
            raise _refused(refused)
    customer_id = stripe_utils.field(subscription, "customer")
    logger.info('Admin %s granted %s a free trial until %s on customer %s',
                admin.username, user.username, f"{end:%Y-%m-%d}", customer_id)
    return {
        "subscription_id": stripe_utils.field(subscription, "id"),
        "status": stripe_utils.field(subscription, "status"),
        "customer_id": customer_id,
        "trial_end": _utc(end),
    }
