"""utils/trials.py directly: what test_grant_trial.py can't reach through the
CLI, which always passes the same `granted_by` and never stops between planning
and granting. Stripe is the in-memory FakeStripe from conftest.
"""
from datetime import datetime, timezone

import pytest

import database
from utils import trials

PASSWORD = "correct horse battery"


def register(username="alice", verified=True):
    assert database.register_user(database.RegisterRequest(
        username=username, email=f"{username}@example.com", password=PASSWORD))
    if verified:
        assert database.mark_email_verified(username, f"{username}@example.com", datetime.now(timezone.utc))
    return database.get_user(username)


def writes(fake_stripe):
    return [name for name, _ in fake_stripe.calls if name.endswith((".create", ".update"))]


def test_a_plan_writes_nothing(tmp_db, fake_stripe):
    user = register()
    now = datetime.now(timezone.utc)

    plan = trials.plan(user, trials.trial_end(30, None, now), now)

    assert (plan.customer, plan.customer_id, plan.how) == (None, None, "new")
    assert plan.days == 30
    assert plan.price_id == fake_stripe.price_id and plan.price == "4.00 USD per month"
    assert plan.saved_card is False
    assert writes(fake_stripe) == []
    assert database.get_user("alice").customer_id is None


def test_grant_records_who_granted_it(tmp_db, fake_stripe):
    user = register()
    now = datetime.now(timezone.utc)
    plan = trials.plan(user, trials.trial_end(7, None, now), now)

    subscription = trials.grant(plan, "admin panel (owner)")

    [params] = fake_stripe.called("subscriptions.create")
    assert params["metadata"] == {"granted_by": "admin panel (owner)"}
    assert params["trial_settings"] == {"end_behavior": {"missing_payment_method": "cancel"}}
    assert subscription["status"] == "trialing"
    assert database.get_user("alice").customer_id == subscription["customer"]


def test_an_unverified_account_is_refused_before_any_stripe_call(tmp_db, fake_stripe):
    user = register(verified=False)
    now = datetime.now(timezone.utc)

    with pytest.raises(trials.TrialRefused) as refused:
        trials.plan(user, trials.trial_end(30, None, now), now)

    assert refused.value.reason == trials.TrialRefused.UNVERIFIED
    assert fake_stripe.calls == []


def test_a_live_subscription_on_an_unlinked_customer_says_so(tmp_db, fake_stripe):
    user = register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")
    now = datetime.now(timezone.utc)

    with pytest.raises(trials.TrialRefused) as refused:
        trials.plan(user, trials.trial_end(30, None, now), now)

    assert refused.value.reason == trials.TrialRefused.LIVE_SUBSCRIPTION
    assert refused.value.unlinked is True
    assert "trialing" in refused.value.message


def test_days_win_when_both_are_given():
    now = datetime(2026, 10, 10, 12, tzinfo=timezone.utc)
    assert trials.trial_end(1, "2027-01-01", now) == datetime(2026, 10, 11, tzinfo=timezone.utc)


@pytest.mark.parametrize("days, until", [(0, None), (731, None), (None, "2026-13-01"), (None, "20270101"),
                                         (None, "2026-10-10"), (None, None)])
def test_a_bad_end_is_refused(days, until):
    now = datetime(2026, 10, 10, 12, tzinfo=timezone.utc)
    with pytest.raises(trials.TrialRefused) as refused:
        trials.trial_end(days, until, now)
    assert refused.value.reason == trials.TrialRefused.BAD_END
    assert refused.value.message
