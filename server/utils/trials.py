"""Free trials: a Stripe subscription to this site's product, trialing until a date.

Shared by `manage.py grant-trial` and the admin panel (api/admin.py), so a trial
granted either way follows the same rules:
- the account's address must be verified;
- the account's own customer is reused, and never another product's customer
  that merely has the same address;
- a customer that already has an active or trialing subscription to this
  product is refused;
- a trial that ends with no card on file ends cancelled, not past_due.
The callers differ only in how they ask and what they print. Both branch on
TrialRefused.reason rather than parsing its message.
"""
import math
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone

import stripe
from starlette.exceptions import HTTPException
from stripe.params import CustomerCreateParams, SubscriptionCreateParams

from utils import stripe_utils
from utils.jwt_utils import UserInDB

# Stripe refuses a trial_end more than two years (730 days) out.
MAX_DAYS = 730


class TrialRefused(Exception):
    """Why a trial was not granted.

    `reason` is one of the constants below: the API maps it to a status, the
    CLI adds advice for it. `message` is a sentence either can show.
    `partway` is True when the refusal came after a customer may have been
    created or linked. That is harmless, since the customer is the account's
    either way and a retry reuses it, but it is worth saying.
    """

    BAD_END = "bad_end"
    UNVERIFIED = "unverified"
    LIVE_SUBSCRIPTION = "live_subscription"
    NOT_STORED = "not_stored"
    STRIPE_REFUSED = "stripe_refused"
    STRIPE_UNAVAILABLE = "stripe_unavailable"

    def __init__(self, reason: str, message: str, *, partway: bool = False, unlinked: bool = False):
        super().__init__(message)
        self.reason = reason
        self.message = message
        self.partway = partway
        # LIVE_SUBSCRIPTION only: the customer holding it is not linked to the account yet.
        self.unlinked = unlinked


@dataclass
class TrialPlan:
    """What a grant would do, worked out without writing anything."""
    user: UserInDB
    trial_end: datetime
    # Rounded up, so 30 days reads as 30 although midnight is sooner.
    days: int
    # None: a new customer will be created with the account's address.
    customer: object
    customer_id: str | None
    how: str
    # Something to know first, such as a linked customer Stripe no longer has.
    note: str | None
    price_id: str
    # "4.00 USD per month", or "" when Stripe cannot say.
    price: str
    # The customer has a payment method, so it WILL be charged when the trial ends.
    saved_card: bool


def trial_end(days: int | None, until: str | None, now: datetime) -> datetime:
    """00:00 UTC on `until` (YYYY-MM-DD), or on the day `days` from today (UTC).

    So 1 day is tonight's midnight UTC rather than exactly 24 hours. `days`
    wins when both are given; callers that must have exactly one check that
    themselves.
    """
    if days is not None:
        if days <= 0:
            raise TrialRefused(TrialRefused.BAD_END, f"The number of days must be positive, not {days}.")
        if days > MAX_DAYS:
            raise TrialRefused(TrialRefused.BAD_END, f"A trial can run for at most {MAX_DAYS} days (Stripe's limit).")
        end = now.date() + timedelta(days=days)
    else:
        # fromisoformat alone also takes 20270101 and 2027-W01-1; one spelling
        # is harder to get wrong.
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", until or ""):
            raise TrialRefused(TrialRefused.BAD_END, f"The end date must be written YYYY-MM-DD, not {until!r}.")
        try:
            end = date.fromisoformat(until)
        except ValueError:
            raise TrialRefused(TrialRefused.BAD_END, f"The end date is not a real date: {until!r}.")
    end_at = datetime(end.year, end.month, end.day, tzinfo=timezone.utc)
    if end_at <= now:
        raise TrialRefused(TrialRefused.BAD_END,
                           f"The trial must end in the future; {end_at:%Y-%m-%d} 00:00 UTC has passed.")
    if end_at > now + timedelta(days=MAX_DAYS):
        latest = (now + timedelta(days=MAX_DAYS)).date()
        raise TrialRefused(TrialRefused.BAD_END,
                           f"A trial can run for at most {MAX_DAYS} days (Stripe's limit): until {latest} at the latest.")
    return end_at


def _customer(user: UserInDB):
    """(the customer to put the trial on, or None to create one; how it was found; a note or None).

    The account's own customer if it has one Stripe still knows, else whatever
    the site itself would link (stripe_utils.find_customer_to_link: a customer
    holding a subscription to this product, found by its username tag or its
    verified address), else a new one. A customer of the other product sold
    from the same Stripe account is therefore never picked up by its address.
    Stripe failures propagate: stripe.StripeError from the retrieve,
    HTTPException(503) from the lookup, which has already logged the cause.
    """
    note = None
    if user.customer_id:
        try:
            customer = stripe_utils.stripe_client.v1.customers.retrieve(user.customer_id)
        except stripe.InvalidRequestError as error:
            if getattr(error, "code", None) != "resource_missing":
                raise
            customer = None
        if customer is not None and not stripe_utils.field(customer, "deleted", False):
            return customer, "already linked to this account", None
        note = f"the linked customer {user.customer_id} no longer exists on Stripe; replacing it"

    found = stripe_utils.find_customer_to_link(user)
    if found is None:
        return None, "new", note
    customer = found[0]
    how = ("found by its username tag" if stripe_utils.field(stripe_utils.field(customer, "metadata"), "username")
           else "found by the account's verified email")
    return customer, how, note


def describe_price() -> str:
    """'4.00 USD per month', or '' when Stripe cannot say. For summaries only."""
    try:
        price = stripe_utils.get_public_price()
    except Exception:  # informational only: a grant never depends on it
        return ""
    every = price["interval"] if price["interval_count"] == 1 else f"{price['interval_count']} {price['interval']}s"
    return f"{price['amount'] / 100:.2f} {price['currency'].upper()} per {every}"


def plan(user: UserInDB, end: datetime, now: datetime) -> TrialPlan:
    """Every check a grant makes, and every Stripe lookup, without writing anything.

    Raises TrialRefused: UNVERIFIED (before any Stripe call), LIVE_SUBSCRIPTION,
    STRIPE_REFUSED or STRIPE_UNAVAILABLE.
    """
    # The same rule as checkout's: the customer carries this address, and until
    # it is proven the account may be using someone else's.
    if user.email_verified_at is None:
        raise TrialRefused(TrialRefused.UNVERIFIED, f"{user.username} has not verified {user.email}.")
    try:
        customer, how, note = _customer(user)
        customer_id = stripe_utils.field(customer, "id")
        if customer_id:
            _, current = stripe_utils.customer_subscription(customer_id)
            status = stripe_utils.field(current, "status")
            if status in stripe_utils.ACTIVE_STATUSES:
                raise TrialRefused(
                    TrialRefused.LIVE_SUBSCRIPTION,
                    f"Customer {customer_id} already has a {status} subscription "
                    f"({stripe_utils.field(current, 'id')}).",
                    unlinked=customer_id != user.customer_id,
                )
        price_id = stripe_utils.get_default_price_id()
    except stripe.APIConnectionError:
        # A subclass of StripeError, so it must be caught first: a network
        # failure is "unavailable" (503), not a refusal Stripe worded.
        raise TrialRefused(TrialRefused.STRIPE_UNAVAILABLE,
                           "Stripe could not be reached or refused the lookup (see the server log); "
                           "nothing was changed.")
    except stripe.StripeError as error:
        raise TrialRefused(TrialRefused.STRIPE_REFUSED, f"Stripe said: {error.user_message or error}")
    except HTTPException:
        raise TrialRefused(TrialRefused.STRIPE_UNAVAILABLE,
                           "Stripe could not be reached or refused the lookup (see the server log); "
                           "nothing was changed.")

    invoice_settings = stripe_utils.field(customer, "invoice_settings")
    saved_card = bool(stripe_utils.field(invoice_settings, "default_payment_method")
                      or stripe_utils.field(customer, "default_source"))
    return TrialPlan(
        user=user,
        trial_end=end,
        days=math.ceil((end - now).total_seconds() / 86400),
        customer=customer,
        customer_id=customer_id,
        how=how,
        note=note,
        price_id=price_id,
        price=describe_price(),
        saved_card=saved_card,
    )


def grant(plan: TrialPlan, granted_by: str):
    """Create the customer if there is none, link it, then subscribe it. Returns the subscription.

    The customer is tagged with the username and stored on the account before
    the subscription is created, so a retry after a failure reuses it, and the
    running site sees the link at once (it reads users.customer_id before
    anything it has cached). `granted_by` goes into the subscription's
    metadata, so the dashboard and the admin panel can say who gave it.

    Raises TrialRefused: NOT_STORED, or STRIPE_REFUSED / STRIPE_UNAVAILABLE with
    partway=True.
    """
    user, customer, customer_id = plan.user, plan.customer, plan.customer_id
    try:
        if customer is None:
            customer = stripe_utils.stripe_client.v1.customers.create(CustomerCreateParams(
                email=user.email,
                metadata={"username": user.username},
            ))
            customer_id = stripe_utils.field(customer, "id")
        # Tags the customer, then writes the row, before the subscription exists.
        if not stripe_utils.link_customer(user, customer):
            raise TrialRefused(TrialRefused.NOT_STORED,
                               f"Could not store customer {customer_id} on {user.username}; see the server log.")
        subscription = stripe_utils.stripe_client.v1.subscriptions.create(SubscriptionCreateParams(
            customer=customer_id,
            items=[{"price": plan.price_id}],
            trial_end=int(plan.trial_end.timestamp()),
            # Without a card the trial ends cancelled: nothing is charged and
            # nothing is left past_due with an unpaid invoice.
            trial_settings={"end_behavior": {"missing_payment_method": "cancel"}},
            metadata={"granted_by": granted_by},
        ))
    except stripe.APIConnectionError:
        # Before StripeError, which it subclasses; see plan().
        raise TrialRefused(TrialRefused.STRIPE_UNAVAILABLE, "Stripe could not be reached (see the server log).",
                           partway=True)
    except stripe.StripeError as error:
        raise TrialRefused(TrialRefused.STRIPE_REFUSED, f"Stripe said: {error.user_message or error}", partway=True)
    except HTTPException:
        raise TrialRefused(TrialRefused.STRIPE_UNAVAILABLE, "Stripe could not be reached (see the server log).",
                           partway=True)
    # Only this process's caches. Called from the admin panel these are the
    # site's own; called from manage.py they are just the CLI's, and the running
    # site still sees the link at once because it reads users.customer_id first,
    # while its status cache for this customer lapses within a minute.
    stripe_utils.invalidate_subscription_status(customer_id)
    stripe_utils.forget_customer_lookup(user.username)
    return subscription
