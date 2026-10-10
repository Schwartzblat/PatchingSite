"""The admin panel's API (api/admin.py) and the admin role it rests on.

Stripe is always the in-memory FakeStripe from conftest, and the database a tmp
file. Admins come from config.ADMIN_USERNAMES, set here through the `admins`
fixture; `owner` is registered straight into the database, because /register
refuses a listed name.
"""
import sqlite3
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

import pytest
import stripe
from starlette.testclient import TestClient

import config
import database

PASSWORD = "correct horse battery"


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


@pytest.fixture
def admins(monkeypatch):
    """Lists `owner` as the admin. Call it with other names to change the list."""
    def set_admins(*names):
        monkeypatch.setattr(config, "ADMIN_USERNAMES", frozenset(name.lower() for name in names))

    set_admins("owner")
    return set_admins


def register(username, email=None, verified=True, customer_id=None):
    assert database.register_user(database.RegisterRequest(
        username=username, email=email or f"{username}@example.com", password=PASSWORD))
    if verified:
        user = database.get_user(username)
        assert database.mark_email_verified(username, user.email, datetime.now(timezone.utc))
    if customer_id:
        assert database.update_user_customer_id("username", username, customer_id)
    return database.get_user(username)


def sign_in(client, username):
    assert client.post("/api/login", json={"username": username, "password": PASSWORD}).status_code == 200
    return client


def as_admin(client):
    register("owner")
    return sign_in(client, "owner")


# --- the role ---------------------------------------------------------------------

def test_admin_usernames_ignore_spaces_case_and_empty_entries():
    assert config._admin_usernames(" Owner , ,boss ") == frozenset({"owner", "boss"})
    assert config._admin_usernames("") == frozenset()
    assert config._admin_usernames(None) == frozenset()


def test_me_says_whether_the_account_is_an_admin(client, admins):
    as_admin(client)
    assert client.get("/api/users/me").json()["is_admin"] is True

    register("alice")
    client.cookies.clear()
    sign_in(client, "alice")
    assert client.get("/api/users/me").json()["is_admin"] is False


def test_admin_names_match_in_any_case(client, admins):
    admins("OWNER")
    as_admin(client)
    assert client.get("/api/users/me").json()["is_admin"] is True


def test_register_refuses_a_listed_name_with_the_usual_words(client, admins):
    admins("owner", "boss")

    response = client.post("/api/register", json={
        "username": "Boss", "email": "boss@example.com", "password": PASSWORD})

    assert response.status_code == 400
    assert response.json()["detail"] == "That username or email is already in use."
    assert database.get_user("boss") is None


def test_a_name_no_longer_listed_can_be_registered(client, admins):
    admins()

    response = client.post("/api/register", json={
        "username": "boss", "email": "boss@example.com", "password": PASSWORD})

    assert response.status_code == 200


# --- helpers ----------------------------------------------------------------------

def test_the_dashboard_url_follows_the_stripe_mode(monkeypatch):
    monkeypatch.setattr(config, "STRIPE_TEST_MODE", True)
    assert config.stripe_dashboard_url("cus_1") == "https://dashboard.stripe.com/test/customers/cus_1"
    monkeypatch.setattr(config, "STRIPE_TEST_MODE", False)
    assert config.stripe_dashboard_url("cus_1") == "https://dashboard.stripe.com/customers/cus_1"


# --- who may use it ---------------------------------------------------------------

def test_the_panel_needs_a_session(client, admins):
    assert client.get("/api/admin/users").status_code == 401


def test_a_bearer_token_alone_is_not_enough(client, admins):
    register("owner")
    token = client.post("/api/auth/token", json={"username": "owner", "password": PASSWORD}).json()["access_token"]
    client.cookies.clear()

    response = client.get("/api/admin/users", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 401


def test_an_admins_bearer_token_does_not_lift_a_non_admin_cookie(client, admins):
    register("owner")
    token = client.post("/api/auth/token", json={"username": "owner", "password": PASSWORD}).json()["access_token"]
    register("alice")
    client.cookies.clear()
    sign_in(client, "alice")

    response = client.get("/api/admin/users", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 403


def phone_token(client, username="owner"):
    """A token as the Android app gets it, with the browser's cookie jar emptied."""
    token = client.post("/api/auth/token", json={"username": username, "password": PASSWORD}).json()["access_token"]
    client.cookies.clear()
    return token


def test_the_login_cookie_still_opens_the_panel(client, admins):
    as_admin(client)
    assert client.get("/api/admin/users").status_code == 200


def test_a_phone_token_sent_as_a_cookie_is_refused(client, admins):
    """/login and /auth/token mint the same kind of token; the claim tells them apart."""
    register("owner")
    token = phone_token(client)
    client.cookies.set("token", f"Bearer {token}")

    assert client.get("/api/admin/users").status_code == 401


def test_a_renewed_phone_token_is_still_refused(client, admins):
    register("owner")
    token = phone_token(client)
    renewed = client.post("/api/auth/renew", headers={"Authorization": f"Bearer {token}"}).json()["access_token"]
    client.cookies.set("token", f"Bearer {renewed}")

    assert client.get("/api/admin/users").status_code == 401


def test_a_cookie_minted_before_the_client_claim_is_refused(client, admins):
    import jwt
    from utils import jwt_utils

    register("owner")
    now = datetime.now(timezone.utc)
    legacy = jwt.encode({"username": "owner", "email": "owner@example.com", "customer_id": "", "typ": "session",
                         "iat": now, "exp": now + timedelta(hours=1)},
                        jwt_utils.SECRET_KEY, algorithm=jwt_utils.ALGORITHM)
    client.cookies.set("token", f"Bearer {legacy}")

    assert client.get("/api/admin/users").status_code == 401


def test_a_phone_token_cannot_turn_into_a_web_cookie_through_confirm_success(client, admins, fake_stripe):
    register("owner")
    fake_stripe.add_customer("cus_owner", "owner@example.com", username="owner")
    fake_stripe.add_subscription("cus_owner", "trialing")
    token = phone_token(client)

    response = client.post("/api/subscription/confirm_success", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200
    laundered = response.cookies.get("token")
    assert laundered, "confirm_success is expected to set a cookie here"
    client.cookies.clear()
    client.cookies.set("token", laundered)
    assert client.get("/api/admin/users").status_code == 401


def test_a_non_admin_is_refused(client, admins):
    register("alice")
    sign_in(client, "alice")

    response = client.get("/api/admin/users")

    assert response.status_code == 403
    assert response.json()["detail"] == "This page is for administrators."


def test_the_panel_is_off_when_nobody_is_listed(client, admins):
    as_admin(client)
    admins()
    assert client.get("/api/admin/users").status_code == 403


def test_an_admin_whose_account_was_deleted_is_refused(client, admins):
    as_admin(client)
    database.delete_user("owner")
    assert client.get("/api/admin/users").status_code == 401


def test_every_admin_route_refuses_a_non_admin(client, admins):
    """Generic on purpose: a route added later without RequiredAdmin fails here."""
    import main

    register("alice")
    sign_in(client, "alice")
    # The OpenAPI schema, not app.routes: this FastAPI nests each include_router'd
    # router in one _IncludedRouter whose routes carry no .path to filter on.
    routes = [(method.upper(), path.replace("{username}", "alice"))
              for path, operations in main.app.openapi()["paths"].items() if path.startswith("/api/admin/")
              for method in sorted(operations)]
    assert len(routes) >= 2
    for method, path in routes:
        response = client.request(method, path, json={})
        assert response.status_code == 403, (method, path, response.status_code)


# --- the list ---------------------------------------------------------------------

def test_the_list_is_newest_first_and_never_asks_stripe(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    register("bob", verified=False)
    assert database.update_user_device_fingerprints("alice", "phone_sku")

    body = client.get("/api/admin/users").json()

    assert [user["username"] for user in body["users"]] == ["bob", "alice", "owner"]
    assert (body["total"], body["limit"], body["devices_limit"]) == (3, 200, config.MAX_DEVICES_ALLOWED)
    bob, alice, owner = body["users"]
    assert alice == {"username": "alice", "email": "alice@example.com", "email_verified": True,
                     "created_at": alice["created_at"], "customer_id": "cus_alice",
                     "devices_used": 1, "is_admin": False}
    assert datetime.strptime(alice["created_at"], "%Y-%m-%dT%H:%M:%SZ")
    assert bob["email_verified"] is False and bob["customer_id"] is None
    assert owner["is_admin"] is True
    assert fake_stripe.calls == []


def test_the_list_searches_usernames_and_emails(client, admins):
    as_admin(client)
    register("alice", email="alice@mail.example.com")
    register("bob", email="robert@example.com")

    def names(q):
        return [user["username"] for user in client.get("/api/admin/users", params={"q": q}).json()["users"]]

    assert names("ALI") == ["alice"]
    assert names("robert") == ["bob"]
    assert names("  bob ") == ["bob"]
    assert names("   ") == ["bob", "alice", "owner"]


# --- one account ------------------------------------------------------------------

def test_the_detail_shows_a_granted_trial(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    fake_stripe.add_customer("cus_alice", "alice@example.com", username="alice")
    sub = fake_stripe.add_subscription("cus_alice", "trialing")
    sub.update({"trial_end": 1_800_000_000, "metadata": {"granted_by": "manage.py grant-trial"}})

    body = client.get("/api/admin/users/ALICE").json()

    assert body["user"]["username"] == "alice"
    assert body["user"]["devices_limit"] == config.MAX_DEVICES_ALLOWED
    assert body["user"]["devices_reset_at"] is None
    assert body["subscription"] == {
        "id": sub["id"], "status": "trialing", "trial_end": "2027-01-15T08:00:00Z",
        "current_period_end": "2027-01-15T08:00:00Z", "cancel_at_period_end": False,
        "granted_by": "manage.py grant-trial"}
    assert body["stripe"] == {
        "customer_id": "cus_alice", "dashboard_url": "https://dashboard.stripe.com/test/customers/cus_alice",
        "customer_missing": False, "error": None, "test_mode": True}


def test_looking_at_an_unlinked_account_links_nothing(client, admins, fake_stripe):
    """resolve_customer_id would link this dashboard trial; the panel must only look."""
    as_admin(client)
    register("alice")
    fake_stripe.add_customer("cus_dash", "alice@example.com")
    fake_stripe.add_subscription("cus_dash", "trialing")

    body = client.get("/api/admin/users/alice").json()

    assert body["subscription"] is None
    assert body["stripe"]["customer_id"] is None and body["stripe"]["dashboard_url"] is None
    assert fake_stripe.calls == []
    assert database.get_user("alice").customer_id is None


def test_a_linked_customer_stripe_no_longer_has(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_gone")

    body = client.get("/api/admin/users/alice").json()

    assert body["subscription"] is None
    assert body["stripe"]["customer_missing"] is True


def test_another_products_subscription_is_not_shown(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    fake_stripe.add_customer("cus_alice", "alice@example.com")
    fake_stripe.add_subscription("cus_alice", "active", product=fake_stripe.other_product_id)

    body = client.get("/api/admin/users/alice").json()

    assert body["subscription"] is None
    assert body["stripe"]["customer_missing"] is False


def test_a_stripe_outage_still_shows_the_account(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    fake_stripe.add_customer("cus_alice", "alice@example.com")
    fake_stripe.fail("subscriptions.list", stripe.APIConnectionError("down"))

    response = client.get("/api/admin/users/alice")

    assert response.status_code == 200
    assert response.json()["user"]["username"] == "alice"
    assert response.json()["subscription"] is None
    assert response.json()["stripe"]["error"] == "Billing is temporarily unavailable, please try again shortly"


def test_an_unknown_account_is_404(client, admins):
    as_admin(client)
    response = client.get("/api/admin/users/ghost")
    assert response.status_code == 404
    assert response.json()["detail"] == "No such user."


def insert_legacy_user(db_path, username):
    """A row older than the username pattern, which /register would refuse today."""
    conn = sqlite3.connect(db_path)
    conn.execute("INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, 'x', '')",
                 (username, "legacy@example.com"))
    conn.commit()
    conn.close()


def test_a_username_that_needs_escaping_still_reaches_its_account(client, admins, tmp_db):
    as_admin(client)
    insert_legacy_user(tmp_db, "first.last@old")

    response = client.get("/api/admin/users/" + quote("first.last@old", safe=""))

    assert response.status_code == 200
    assert response.json()["user"]["username"] == "first.last@old"


# --- email --------------------------------------------------------------------------

def test_a_new_email_is_unverified_unless_said_otherwise(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    fake_stripe.add_customer("cus_alice", "alice@example.com", username="alice")

    response = client.post("/api/admin/users/alice/email", json={"email": "new@example.com"})

    assert response.status_code == 200
    assert response.json()["user"]["email"] == "new@example.com"
    assert response.json()["user"]["email_verified"] is False
    assert database.get_user("alice").email_verified_at is None
    # The Stripe customer may hold the other product's subscriptions; its address is left alone.
    assert fake_stripe.calls == []
    assert fake_stripe.customers["cus_alice"]["email"] == "alice@example.com"


def test_a_new_email_can_be_marked_verified(client, admins):
    as_admin(client)
    register("alice", verified=False)

    response = client.post("/api/admin/users/alice/email", json={"email": "new@example.com", "verified": True})

    assert response.json()["user"]["email_verified"] is True


def test_an_address_another_account_holds_is_409_in_any_case(client, admins):
    as_admin(client)
    register("alice")
    register("bob")

    response = client.post("/api/admin/users/alice/email", json={"email": "BOB@example.com"})

    assert response.status_code == 409
    assert response.json()["detail"] == "That email is already in use."
    assert database.get_user("alice").email == "alice@example.com"


def test_the_same_address_is_400_but_a_change_of_case_is_allowed(client, admins):
    as_admin(client)
    register("alice")

    assert client.post("/api/admin/users/alice/email", json={"email": "alice@example.com"}).status_code == 400
    response = client.post("/api/admin/users/alice/email", json={"email": "Alice@example.com"})
    assert response.status_code == 200
    assert response.json()["user"]["email"] == "Alice@example.com"


def test_a_malformed_address_is_refused(client, admins):
    as_admin(client)
    register("alice")
    assert client.post("/api/admin/users/alice/email", json={"email": "not-an-address"}).status_code == 422


# --- verify -------------------------------------------------------------------------

def test_mark_verified(client, admins):
    as_admin(client)
    register("alice", verified=False)

    response = client.post("/api/admin/users/alice/verify-email")

    assert response.status_code == 200
    assert response.json()["user"]["email_verified"] is True


def test_mark_verified_keeps_the_first_stamp(client, admins):
    as_admin(client)
    before = register("alice").email_verified_at

    assert client.post("/api/admin/users/alice/verify-email").status_code == 200

    assert database.get_user("alice").email_verified_at == before


# --- devices ------------------------------------------------------------------------

def test_clearing_devices_leaves_the_users_own_reset(client, admins):
    as_admin(client)
    register("alice")
    for index in range(3):
        assert database.update_user_device_fingerprints("alice", f"phone{index}_sku")

    response = client.post("/api/admin/users/alice/devices/reset")

    assert response.status_code == 200
    assert response.json()["user"]["devices_used"] == 0
    assert database.get_user("alice").devices_reset_at is None
    # ...so the user can still reset by themselves.
    client.cookies.clear()
    sign_in(client, "alice")
    assert client.get("/api/users/me/devices").json()["can_reset"] is True


# --- delete -------------------------------------------------------------------------

def test_delete_removes_the_account_and_unlinks_its_reports(client, admins, tmp_db):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    report_id = database.add_bug_report(source="web", message="It broke", email="reply@example.com",
                                        username="alice", client_ip="198.51.100.4", context={},
                                        created_at=datetime.now(timezone.utc))

    response = client.delete("/api/admin/users/ALICE")

    assert response.status_code == 200
    assert response.json() == {"deleted": "alice", "customer_id": "cus_alice",
                               "dashboard_url": "https://dashboard.stripe.com/test/customers/cus_alice"}
    assert database.get_user("alice") is None
    conn = sqlite3.connect(tmp_db)
    row = conn.execute("SELECT username, email, client_ip, message FROM bug_reports WHERE id = ?",
                       (report_id,)).fetchone()
    conn.close()
    assert row == (None, None, None, "It broke")


def test_delete_without_a_customer(client, admins):
    as_admin(client)
    register("alice")
    assert client.delete("/api/admin/users/alice").json() == {
        "deleted": "alice", "customer_id": None, "dashboard_url": None}


def test_an_admin_cannot_delete_their_own_account(client, admins):
    as_admin(client)

    response = client.delete("/api/admin/users/Owner")

    assert response.status_code == 403
    assert response.json()["detail"] == "You can't delete your own account here."
    assert database.get_user("owner") is not None


def test_delete_reaches_a_username_that_needs_escaping(client, admins, tmp_db):
    as_admin(client)
    insert_legacy_user(tmp_db, "first.last@old")

    response = client.delete("/api/admin/users/" + quote("first.last@old", safe=""))

    assert response.status_code == 200
    assert database.get_user("first.last@old") is None


@pytest.mark.parametrize("method, path", [
    ("POST", "/api/admin/users/ghost/email"),
    ("POST", "/api/admin/users/ghost/verify-email"),
    ("POST", "/api/admin/users/ghost/devices/reset"),
    ("DELETE", "/api/admin/users/ghost"),
])
def test_an_action_on_an_unknown_account_is_404(client, admins, method, path):
    as_admin(client)
    response = client.request(method, path, json={"email": "ghost@example.com"})
    assert response.status_code == 404


# --- free trial ---------------------------------------------------------------------

def in_days(days):
    return (datetime.now(timezone.utc).date() + timedelta(days=days)).isoformat()


def midnight_utc(iso_date):
    return int(datetime.fromisoformat(iso_date).replace(tzinfo=timezone.utc).timestamp())


def test_preview_says_what_a_grant_would_do_and_writes_nothing(client, admins, fake_stripe):
    as_admin(client)
    register("alice")

    response = client.post("/api/admin/users/alice/trial/preview", json={"days": 30})

    assert response.status_code == 200
    assert response.json() == {
        "email": "alice@example.com", "customer_id": None, "customer_how": "new", "note": None,
        "trial_end": f"{in_days(30)}T00:00:00Z", "days": 30, "price_id": fake_stripe.price_id,
        "price": "4.00 USD per month", "saved_card": False, "test_mode": True}
    assert fake_stripe.called("customers.create") == []
    assert fake_stripe.called("subscriptions.create") == []
    assert database.get_user("alice").customer_id is None


def test_preview_warns_that_a_saved_card_will_be_charged(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_card")
    fake_stripe.add_customer("cus_card", "alice@example.com", username="alice", payment_method="pm_card")

    body = client.post("/api/admin/users/alice/trial/preview", json={"until": in_days(10)}).json()

    assert body["saved_card"] is True
    assert (body["customer_id"], body["customer_how"]) == ("cus_card", "already linked to this account")


def test_grant_makes_a_trial_that_ends_cancelled_and_names_the_admin(client, admins, fake_stripe):
    as_admin(client)
    register("alice")

    response = client.post("/api/admin/users/alice/trial", json={"until": in_days(30)})

    assert response.status_code == 200
    [params] = fake_stripe.called("subscriptions.create")
    assert params["trial_end"] == midnight_utc(in_days(30))
    assert params["trial_settings"] == {"end_behavior": {"missing_payment_method": "cancel"}}
    assert params["metadata"] == {"granted_by": "admin panel (owner)"}
    customer_id = database.get_user("alice").customer_id
    assert customer_id
    assert response.json() == {"subscription_id": response.json()["subscription_id"], "status": "trialing",
                               "customer_id": customer_id, "trial_end": f"{in_days(30)}T00:00:00Z"}
    detail = client.get("/api/admin/users/alice").json()
    assert detail["subscription"]["status"] == "trialing"
    assert detail["subscription"]["granted_by"] == "admin panel (owner)"


def test_a_grant_rechecks_rather_than_trusting_the_preview(client, admins, fake_stripe):
    as_admin(client)
    register("alice", customer_id="cus_alice")
    fake_stripe.add_customer("cus_alice", "alice@example.com", username="alice")
    assert client.post("/api/admin/users/alice/trial/preview", json={"days": 30}).status_code == 200
    fake_stripe.add_subscription("cus_alice", "active")  # subscribed meanwhile

    response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 409
    assert "active" in response.json()["detail"]
    assert fake_stripe.called("subscriptions.create") == []


def test_a_second_grant_is_refused(client, admins, fake_stripe):
    as_admin(client)
    register("alice")
    assert client.post("/api/admin/users/alice/trial", json={"days": 30}).status_code == 200

    response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 409
    assert len(fake_stripe.called("subscriptions.create")) == 1


@pytest.mark.parametrize("path", ["trial/preview", "trial"])
def test_an_unverified_address_is_409_before_any_stripe_call(client, admins, fake_stripe, path):
    as_admin(client)
    register("alice", verified=False)

    response = client.post(f"/api/admin/users/alice/{path}", json={"days": 30})

    assert response.status_code == 409
    assert response.json()["detail"].endswith("Mark the email verified first.")
    assert fake_stripe.calls == []


@pytest.mark.parametrize("body", [
    {},
    {"days": 5, "until": "2030-01-01"},
    {"days": 0},
    {"days": 731},
    {"until": "2027-13-01"},
    {"until": "next week"},
    {"until": in_days(0)},
])
def test_a_bad_end_is_400_and_asks_stripe_nothing(client, admins, fake_stripe, body):
    as_admin(client)
    register("alice")

    response = client.post("/api/admin/users/alice/trial", json=body)

    assert response.status_code == 400
    assert response.json()["detail"]
    assert fake_stripe.calls == []


def test_a_stripe_refusal_is_502_with_its_message(client, admins, fake_stripe):
    as_admin(client)
    register("alice")
    fake_stripe.fail("subscriptions.create", stripe.PermissionError(
        "The provided key does not have the required permissions for this endpoint"))

    response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 502
    assert "required permissions" in response.json()["detail"]
    assert "reuses the customer" in response.json()["detail"]


def test_a_stripe_outage_is_503(client, admins, fake_stripe):
    as_admin(client)
    register("alice")
    fake_stripe.fail("customers.list", stripe.APIConnectionError("down"))

    response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 503
    assert fake_stripe.called("customers.create") == []


def test_a_trial_for_an_unknown_account_is_404(client, admins, fake_stripe):
    as_admin(client)
    assert client.post("/api/admin/users/ghost/trial", json={"days": 30}).status_code == 404


# --- final-review fixes -------------------------------------------------------------

def insert_user_with_email(db_path, username, email):
    conn = sqlite3.connect(db_path)
    conn.execute("INSERT INTO users (username, email, password_hash, password_salt) VALUES (?, ?, 'x', '')",
                 (username, email))
    conn.commit()
    conn.close()


def test_is_admin_matches_the_way_the_database_does(admins):
    assert config.is_admin("OWNER") is True
    # Different accounts to sqlite's lower(): a trailing space, and the Kelvin
    # sign, which Python's lower() folds to "k".
    assert config.is_admin("owner ") is False
    admins("kowner")
    assert config.is_admin("Kowner") is False
    assert config.is_admin("Kowner") is True


def test_a_lookalike_account_is_not_an_admin(client, admins, tmp_db):
    as_admin(client)
    insert_user_with_email(tmp_db, "owner ", "lookalike@example.com")
    conn = sqlite3.connect(tmp_db)
    # Same password as the real owner, so the lookalike can sign in by its email.
    conn.execute("UPDATE users SET password_hash = (SELECT password_hash FROM users WHERE username = 'owner'), "
                 "password_salt = (SELECT password_salt FROM users WHERE username = 'owner'), "
                 "password_scheme = (SELECT password_scheme FROM users WHERE username = 'owner'), "
                 "email_verified_at = CURRENT_TIMESTAMP WHERE username = 'owner '")
    conn.commit()
    conn.close()

    client.cookies.clear()
    assert client.post("/api/login", json={"username": "lookalike@example.com", "password": PASSWORD}).status_code == 200
    assert client.get("/api/users/me").json()["is_admin"] is False
    assert client.get("/api/admin/users").status_code == 403

    client.cookies.clear()
    sign_in(client, "owner")
    assert client.get("/api/admin/users").status_code == 200


def test_a_network_failure_on_grant_is_503_not_502(client, admins, fake_stripe):
    as_admin(client)
    register("alice")
    fake_stripe.fail("subscriptions.create", stripe.APIConnectionError("down"))

    response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 503
    assert "reuses the customer" in response.json()["detail"]


def test_saving_an_address_unchanged_but_for_case_keeps_it_verified(client, admins, tmp_db):
    as_admin(client)
    register("alice")
    conn = sqlite3.connect(tmp_db)
    conn.execute("UPDATE users SET email = 'alice@Example.com' WHERE username = 'alice'")
    conn.commit()
    conn.close()
    stamp = database.get_user("alice").email_verified_at
    assert stamp is not None

    same = client.post("/api/admin/users/alice/email", json={"email": "alice@example.com"})
    assert same.status_code == 200
    assert same.json()["user"]["email_verified"] is True
    assert database.get_user("alice").email_verified_at == stamp

    cased = client.post("/api/admin/users/alice/email", json={"email": "ALICE@example.com"})
    assert cased.status_code == 200
    assert cased.json()["user"]["email_verified"] is True
    assert database.get_user("alice").email_verified_at == stamp


def test_a_refused_grant_is_logged(client, admins, fake_stripe, caplog):
    as_admin(client)
    register("alice", verified=False)

    with caplog.at_level("WARNING"):
        response = client.post("/api/admin/users/alice/trial", json={"days": 30})

    assert response.status_code == 409
    assert any("owner" in r.getMessage() and "alice" in r.getMessage() and "unverified" in r.getMessage()
               for r in caplog.records)
