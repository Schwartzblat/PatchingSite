"""manage.py grant-trial: a free trial for one account, made on Stripe and linked to it.

Stripe is always the in-memory FakeStripe from conftest.
"""
import argparse
import sys
from datetime import datetime, timedelta, timezone

import pytest
import stripe

import database
import manage

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


def grant(username="alice", until=None, days=None, yes=True):
    if until is None and days is None:
        until = in_days(30)
    return manage.cmd_grant_trial(argparse.Namespace(username=username, until=until, days=days, yes=yes))


def in_days(days):
    return (datetime.now(timezone.utc).date() + timedelta(days=days)).isoformat()


def midnight_utc(iso_date):
    return int(datetime.fromisoformat(iso_date).replace(tzinfo=timezone.utc).timestamp())


def created_subscriptions(fake_stripe):
    return fake_stripe.called("subscriptions.create")


def test_an_account_without_a_customer_gets_a_new_one_tagged_and_linked(tmp_db, fake_stripe):
    register()
    until = in_days(30)

    assert grant(until=until) == 0

    [params] = fake_stripe.called("customers.create")
    assert params["email"] == "alice@example.com"
    assert params["metadata"] == {"username": "alice"}
    customer_id = linked()
    assert customer_id in fake_stripe.customers

    [subscription] = created_subscriptions(fake_stripe)
    assert subscription["customer"] == customer_id
    assert [item["price"] for item in subscription["items"]] == [fake_stripe.price_id]
    assert subscription["trial_end"] == midnight_utc(until)
    # Without a card the trial ends cancelled, not past_due with an unpaid invoice.
    assert subscription["trial_settings"] == {"end_behavior": {"missing_payment_method": "cancel"}}
    assert subscription["metadata"] == {"granted_by": "manage.py grant-trial"}


def test_days_counts_to_midnight_utc(tmp_db, fake_stripe):
    register()

    assert grant(days=14) == 0

    [subscription] = created_subscriptions(fake_stripe)
    assert subscription["trial_end"] == midnight_utc(in_days(14))


def test_the_linked_customer_is_reused(tmp_db, fake_stripe):
    register(customer_id="cus_linked")
    fake_stripe.add_customer("cus_linked", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_linked", "canceled")

    assert grant() == 0

    assert fake_stripe.called("customers.create") == []
    assert [params["customer"] for params in created_subscriptions(fake_stripe)] == ["cus_linked"]
    assert linked() == "cus_linked"


def test_a_customer_linked_by_hand_is_tagged(tmp_db, fake_stripe):
    """The owner set users.customer_id in the database for a dashboard customer."""
    register(customer_id="cus_by_hand")
    fake_stripe.add_customer("cus_by_hand", "alice@example.com")

    assert grant() == 0

    assert fake_stripe.customers["cus_by_hand"]["metadata"] == {"username": "alice"}
    assert [params["customer"] for params in created_subscriptions(fake_stripe)] == ["cus_by_hand"]


def test_the_resolvers_match_is_reused_and_linked(tmp_db, fake_stripe):
    """What the site would link: here a lapsed subscriber's tagged customer."""
    register()
    fake_stripe.add_customer("cus_lapsed", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_lapsed", "canceled")

    assert grant() == 0

    assert fake_stripe.called("customers.create") == []
    assert linked() == "cus_lapsed"
    assert [params["customer"] for params in created_subscriptions(fake_stripe)] == ["cus_lapsed"]


def test_an_untagged_customer_without_a_live_subscription_is_not_reused(tmp_db, fake_stripe):
    """Its address alone does not make it the account's (it may be the other
    product's customer), so a new, tagged one is made instead."""
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")

    assert grant() == 0

    assert fake_stripe.called("customers.create")[0]["metadata"] == {"username": "alice"}
    assert linked() not in (None, "cus_dash")
    assert fake_stripe.customers["cus_dash"]["metadata"] == {}


def test_a_linked_customer_stripe_no_longer_has_is_replaced(tmp_db, fake_stripe, capsys):
    register(customer_id="cus_gone")

    assert grant() == 0

    assert linked() not in (None, "cus_gone")
    assert "cus_gone" in capsys.readouterr().out


def test_an_unverified_account_is_refused_before_any_stripe_call(tmp_db, fake_stripe, capsys):
    register(verified=False)

    assert grant() == 1

    assert fake_stripe.calls == []
    assert linked() is None
    assert "verified" in capsys.readouterr().out


@pytest.mark.parametrize("status", ["active", "trialing"])
def test_a_customer_with_a_live_subscription_is_refused(tmp_db, fake_stripe, capsys, status):
    register(customer_id="cus_linked")
    fake_stripe.add_customer("cus_linked", "alice@example.com", username="alice")
    fake_stripe.add_subscription("cus_linked", status)

    assert grant() == 1

    assert created_subscriptions(fake_stripe) == []
    assert status in capsys.readouterr().out


def test_a_dashboard_customer_with_a_live_subscription_is_refused_and_left_alone(tmp_db, fake_stripe, capsys):
    register()
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")

    assert grant() == 1

    assert created_subscriptions(fake_stripe) == []
    assert linked() is None
    assert "Subscription page" in capsys.readouterr().out


def test_an_unknown_account_is_refused(tmp_db, fake_stripe, capsys):
    assert grant("ghost") == 1
    assert fake_stripe.calls == []
    assert "ghost" in capsys.readouterr().out


@pytest.mark.parametrize("until, days", [
    (-1, None),                       # yesterday
    (0, None),                        # today's midnight has already passed
    (731, None),                      # past Stripe's two-year limit
    ("2026-13-01", None),             # not a date
    ("next week", None),
    ("20270101", None),               # ISO, but not the documented YYYY-MM-DD
    (None, 0),
    (None, -3),
    (None, 10 ** 9),
])
def test_a_bad_trial_end_is_refused_before_anything_happens(tmp_db, fake_stripe, capsys, until, days):
    """An int `until` is days from today, worked out here rather than at collection."""
    register()
    if isinstance(until, int):
        until = in_days(until)

    assert grant(until=until, days=days) != 0

    assert fake_stripe.calls == []
    assert capsys.readouterr().out.strip()


def test_two_years_out_is_still_allowed(tmp_db, fake_stripe):
    register()
    assert grant(until=in_days(730)) == 0


def test_the_summary_says_who_until_when_and_how_it_ends(tmp_db, fake_stripe, capsys):
    register()
    until = in_days(30)

    assert grant(until=until) == 0

    out = capsys.readouterr().out
    assert "alice" in out
    assert linked() in out
    assert f"{until} 00:00 UTC" in out
    assert "(30 day(s) from now)" in out
    assert "TEST" in out
    assert "cancelled" in out  # what happens at the end without a card


def test_a_customer_with_a_saved_card_is_warned_it_will_be_charged(tmp_db, fake_stripe, capsys):
    register(customer_id="cus_card")
    fake_stripe.add_customer("cus_card", "alice@example.com", username="alice", payment_method="pm_card")

    assert grant() == 0

    assert "charged" in capsys.readouterr().out


def test_it_asks_first_and_a_no_changes_nothing(tmp_db, fake_stripe, monkeypatch, capsys):
    register()
    monkeypatch.setattr("builtins.input", lambda prompt: "n")

    assert grant(yes=False) == 1

    assert fake_stripe.called("customers.create") == []
    assert created_subscriptions(fake_stripe) == []
    assert linked() is None


def test_a_closed_stdin_is_a_no(tmp_db, fake_stripe, monkeypatch):
    register()

    def _eof(prompt):
        raise EOFError

    monkeypatch.setattr("builtins.input", _eof)

    assert grant(yes=False) == 1
    assert created_subscriptions(fake_stripe) == []


def test_a_yes_at_the_prompt_grants(tmp_db, fake_stripe, monkeypatch):
    register()
    monkeypatch.setattr("builtins.input", lambda prompt: "y")

    assert grant(yes=False) == 0
    assert len(created_subscriptions(fake_stripe)) == 1


def test_a_stripe_refusal_is_reported(tmp_db, fake_stripe, capsys):
    """A restricted key without the permission says so, and the operator must see it."""
    register()
    fake_stripe.fail("subscriptions.create", stripe.PermissionError(
        "The provided key does not have the required permissions for this endpoint"))

    assert grant() == 1

    assert "required permissions" in capsys.readouterr().out


def test_a_stripe_outage_during_the_lookup_changes_nothing(tmp_db, fake_stripe, capsys):
    register()
    fake_stripe.fail("customers.list", stripe.APIConnectionError("down"))

    assert grant() == 1

    assert fake_stripe.called("customers.create") == []
    assert created_subscriptions(fake_stripe) == []
    assert "Stripe" in capsys.readouterr().out


@pytest.mark.parametrize("argv", [
    ["grant-trial", "alice"],
    ["grant-trial", "alice", "--until", "2027-01-01", "--days", "5"],
])
def test_exactly_one_of_until_and_days_is_required(monkeypatch, argv):
    monkeypatch.setattr(sys, "argv", ["manage.py", *argv])
    with pytest.raises(SystemExit) as excinfo:
        manage.main()
    assert excinfo.value.code == 2
