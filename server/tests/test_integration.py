"""End-to-end tests across module boundaries.

Each module's own suite mocks its neighbours, so these exercise the seams:
real sqlite, real JWTs, real cookies, real routing. The device-limit bug this
file's `test_device_limit_holds_through_the_real_database` covers was invisible
to both sides' unit tests because each stubbed the other.
"""
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from starlette.testclient import TestClient

import config
import database
from database import DeviceResult, RegisterRequest
from utils import jwt_utils, stripe_utils
from utils.passwords import SCHEME_ARGON2, SCHEME_LEGACY, legacy_hash, new_salt

GOOD_PASSWORD = "correct horse battery"


@pytest.fixture
def app_client(tmp_db, monkeypatch):
    """The real app, against a throwaway DB, with Stripe faked out."""
    import main

    # payment.py imported the symbol directly, so it holds its own reference.
    _set_subscription_status(monkeypatch, "active")
    # An account with no customer asks Stripe for one; by default there is none.
    _use_stripe(monkeypatch, _stripe_with_customers())
    client = TestClient(main.app)
    with client:
        yield client


def _set_subscription_status(monkeypatch, status):
    from api import payment

    monkeypatch.setattr(stripe_utils, "get_subscription_status", lambda cid: status)
    monkeypatch.setattr(payment, "get_subscription_status", lambda cid: status, raising=False)


def _session_cookie(client):
    """Starlette quotes the cookie because `Bearer <jwt>` contains a space."""
    return client.cookies.get("token").strip('"')


def register(client, username="alice", email=None, password=GOOD_PASSWORD):
    return client.post("/api/register", json={
        "username": username,
        "email": email or f"{username}@example.com",
        "password": password,
    })


def login(client, username="alice", password=GOOD_PASSWORD):
    return client.post("/api/login", json={"username": username, "password": password})


def verify_email(username):
    """create-checkout refuses an unverified address; the checkout tests here are
    about what happens once an account is allowed to subscribe."""
    user = database.get_user(username)
    assert database.mark_email_verified(user.username, user.email, datetime.now(timezone.utc))


def test_register_login_me_round_trip(app_client):
    assert register(app_client).status_code == 200
    assert login(app_client).status_code == 200

    me = app_client.get("/api/users/me")
    assert me.status_code == 200
    body = me.json()
    assert body["username"] == "alice"
    # Credentials must never reach the client.
    assert "password_hash" not in body and "password_salt" not in body


def test_logout_clears_the_session(app_client):
    register(app_client)
    login(app_client)
    assert app_client.post("/api/users/logout").status_code == 200
    assert app_client.get("/api/users/me").status_code == 401


def test_an_apk_token_cannot_be_used_as_a_session_cookie(app_client):
    """The headline fix: app tokens ship inside every APK we hand out.

    Before, both token kinds were signed with one key and carried no `typ`, so
    a token extracted from a downloaded APK was a permanent login for that user.
    """
    register(app_client)
    login(app_client)
    session = jwt_utils.get_token_data(SimpleNamespace(
        cookies={"token": _session_cookie(app_client)}, client=None))
    app_token = jwt_utils.create_app_jwt(session, "com.whatsapp")

    app_client.cookies.clear()
    app_client.cookies.set("token", f"Bearer {app_token}")

    assert app_client.get("/api/users/me").status_code == 401


def test_a_session_token_cannot_be_replayed_as_an_apk_token(app_client):
    """The mirror image: a stolen browser cookie must not unlock the app API."""
    register(app_client)
    login(app_client)
    raw = _session_cookie(app_client).removeprefix("Bearer ")

    with pytest.raises(Exception):
        jwt_utils.get_app_token_data(raw)


def test_legacy_password_is_upgraded_on_first_login(app_client, tmp_db):
    """Live users were stored with 1000x salted SHA-512 and one shared salt."""
    salt = new_salt()
    with database._connect() as conn:
        conn.execute(
            "INSERT INTO users (username, email, password_hash, password_salt, password_scheme)"
            " VALUES (?, ?, ?, ?, ?)",
            ("legacyuser", "legacy@example.com", legacy_hash(GOOD_PASSWORD, salt), salt, SCHEME_LEGACY),
        )

    assert login(app_client, "legacyuser").status_code == 200
    assert database.get_user("legacyuser").password_scheme == SCHEME_ARGON2
    # Still works after the silent upgrade, and the wrong password still fails.
    app_client.cookies.clear()
    assert login(app_client, "legacyuser").status_code == 200
    assert login(app_client, "legacyuser", "wrong").status_code == 401


def test_device_limit_holds_through_the_real_database(app_client, monkeypatch):
    """Regression: the handler read every DeviceResult as success.

    `DeviceResult.LIMIT_REACHED is not False` is True, so a device the database
    had refused was reported to the phone as allowed.
    """
    register(app_client)
    user = database.get_user("alice")
    database.update_user_customer_id("username", "alice", "cus_test")
    monkeypatch.setattr(jwt_utils, "get_app_token_data", lambda raw: SimpleNamespace(
        username=user.username, customer_id="cus_test", package_name="com.whatsapp"))

    allowed = []
    for i in range(config.MAX_DEVICES_ALLOWED + 3):
        r = app_client.post("/api/subscription/is_device_allowed",
                            data={"token": "t", "device": f"dev{i}", "sku": "sku"})
        allowed.append(r.status_code)

    assert allowed[:config.MAX_DEVICES_ALLOWED] == [200] * config.MAX_DEVICES_ALLOWED
    assert set(allowed[config.MAX_DEVICES_ALLOWED:]) == {403}
    assert len(database.get_user("alice").device_fingerprints) == config.MAX_DEVICES_ALLOWED
    # A device already on the account keeps working once the limit is reached.
    again = app_client.post("/api/subscription/is_device_allowed",
                            data={"token": "t", "device": "dev0", "sku": "sku"})
    assert again.status_code == 200


def test_downloads_require_a_session(app_client):
    r = app_client.post("/api/downloads", json={"package": "com.whatsapp", "version": "1.0"})
    assert r.status_code == 401


def test_downloads_require_a_subscription(app_client, monkeypatch):
    register(app_client)
    login(app_client)
    _set_subscription_status(monkeypatch, "free")
    r = app_client.post("/api/downloads", json={"package": "com.whatsapp", "version": "1.0"})
    assert r.status_code == 402


def test_unknown_package_is_404_not_500(app_client):
    assert app_client.get("/api/list/com.not.a.real.app").status_code == 404


def test_duplicate_registration_is_rejected(app_client):
    assert register(app_client).status_code == 200
    assert register(app_client, email="other@example.com").status_code == 400
    # Case-insensitive, matching the unique index.
    assert register(app_client, username="ALICE", email="x@example.com").status_code == 400


def test_collision_response_does_not_reveal_which_field_was_taken(app_client):
    # A taken username and a taken email must be indistinguishable to the client,
    # or /register becomes an account-enumeration oracle.
    assert register(app_client, username="alice", email="alice@example.com").status_code == 200

    email_taken = register(app_client, username="bob", email="alice@example.com")
    username_taken = register(app_client, username="ALICE", email="carol@example.com")

    assert email_taken.status_code == username_taken.status_code == 400
    assert email_taken.json() == username_taken.json()
    assert "already in use" in email_taken.json()["detail"]


def test_weak_input_is_rejected_before_it_reaches_the_database(app_client):
    assert register(app_client, password="short").status_code == 422
    assert register(app_client, username="a b", email="c@d.co").status_code == 422
    assert register(app_client, username="bob", email="not-an-email").status_code == 422


def test_login_is_rate_limited(app_client):
    register(app_client)
    codes = [login(app_client, password="wrong").status_code for _ in range(15)]
    assert 429 in codes, "brute force was not throttled"


def test_health_is_public(app_client):
    assert app_client.get("/api/health").status_code == 200


# --- account takeover via unverified email (security regression) -------------

class _StripeLike(dict):
    def __getattr__(self, name):
        try:
            return self[name]
        except KeyError:
            raise AttributeError(name)


def _customer(cid, email, username=None):
    meta = {"username": username} if username else {}
    return _StripeLike({"id": cid, "email": email, "metadata": _StripeLike(meta)})


def _stripe_with_customers(*customers):
    """Every customer comes back from every list and search, whatever the address:
    the server must pick out its own, never trust the filter to have done it."""
    from types import SimpleNamespace

    def _update(cid, params=None, options=None):
        customer = next(c for c in customers if c["id"] == cid)
        customer["metadata"].update((params or {}).get("metadata", {}))
        return customer

    return SimpleNamespace(v1=SimpleNamespace(
        customers=SimpleNamespace(
            list=lambda params=None, options=None: _StripeLike({"data": list(customers)}),
            search=lambda params=None, options=None: _StripeLike({"data": list(customers)}),
            create=lambda params=None, options=None: _customer(
                "cus_new", params.get("email"), params.get("metadata", {}).get("username")),
            retrieve=lambda cid, params=None, options=None: next(
                (c for c in customers if c["id"] == cid), None),
            update=_update,
        ),
    ))


def _use_stripe(monkeypatch, fake):
    """payment.py holds its own reference to the client, so both modules are patched."""
    from api import payment

    monkeypatch.setattr(stripe_utils, "stripe_client", fake)
    monkeypatch.setattr(payment, "stripe_client", fake)


def test_a_shared_email_cannot_claim_another_users_stripe_customer(app_client, monkeypatch):
    """Registration never verifies the email and users.email is not unique.

    Matching a Stripe customer on email alone let an attacker who registered
    with the victim's address POST confirm_success first and absorb the
    victim's paid subscription, including billing-portal access.
    """
    from api import payment

    register(app_client, username="mallory", email="victim@example.com")
    login(app_client, "mallory")

    victims_customer = _customer("cus_victim", "victim@example.com", username="victim")
    _use_stripe(monkeypatch, _stripe_with_customers(victims_customer))
    monkeypatch.setattr(payment, "find_subscription", lambda cid: _StripeLike({"id": "sub_v"}))

    response = app_client.post("/api/subscription/confirm_success")

    assert response.status_code == 404
    assert database.get_user("mallory").customer_id in (None, "")


def test_checkout_never_reuses_a_customer_the_account_does_not_own(app_client, monkeypatch):
    """Reusing a customer by email attached the victim's saved payment methods."""
    from api import payment

    register(app_client, username="mallory2", email="victim@example.com")
    verify_email("mallory2")
    login(app_client, "mallory2")

    victims_customer = _customer("cus_victim", "victim@example.com", username="victim")
    fake = _stripe_with_customers(victims_customer)
    created = {}
    fake.v1.checkout = __import__("types").SimpleNamespace(sessions=__import__("types").SimpleNamespace(
        create=lambda params=None, options=None: created.update(params) or _StripeLike(
            {"url": "https://checkout.stripe.test/s"})))
    _use_stripe(monkeypatch, fake)
    monkeypatch.setattr(payment, "get_default_price_id", lambda: "price_x")

    response = app_client.post("/api/subscription/create-checkout", json={"priceId": "price_x"})

    assert response.status_code == 200
    assert created["customer"] != "cus_victim", "checkout bound to someone else's Stripe customer"


def test_a_customer_the_account_does_own_is_still_linked(app_client, monkeypatch):
    """The fix must not break the legitimate path."""
    from api import payment

    register(app_client, username="owner", email="owner@example.com")
    login(app_client, "owner")

    own = _customer("cus_owner", "owner@example.com", username="owner")
    _use_stripe(monkeypatch, _stripe_with_customers(own))
    monkeypatch.setattr(payment, "find_subscription", lambda cid: _StripeLike({"id": "sub_o"}))
    # Only a customer holding a subscription to this product is ever linked.
    monkeypatch.setattr(stripe_utils, "customer_subscription",
                        lambda cid: (True, _StripeLike({"id": "sub_o", "status": "active"})))

    assert app_client.post("/api/subscription/confirm_success").status_code == 200
    assert database.get_user("owner").customer_id == "cus_owner"


# --- checkout guard: one subscription per account ----------------------------

def _cookie_claims(client):
    import jwt

    raw = _session_cookie(client).split(" ", 1)[1]
    return jwt.decode(raw, jwt_utils.SECRET_KEY, algorithms=[jwt_utils.ALGORITHM],
                      options={"verify_iat": False})


def _checkout_fake(monkeypatch, customers, active_for, missing=()):
    """Stripe with the given customers, where only `active_for` has an active
    subscription and the ids in `missing` no longer exist at all.

    Returns the list checkout.sessions.create appends to, so a test can prove no
    second checkout was ever opened.
    """
    from api import payment

    fake = _stripe_with_customers(*customers)
    created = []
    fake.v1.checkout = SimpleNamespace(sessions=SimpleNamespace(
        create=lambda params=None, options=None: created.append(params) or _StripeLike(
            {"url": "https://checkout.stripe.test/s"})))
    _use_stripe(monkeypatch, fake)
    monkeypatch.setattr(payment, "get_default_price_id", lambda: "price_x")

    def _subscription(cid):
        if cid in missing:
            return False, None
        return True, _StripeLike({"id": f"sub_{cid}", "status": "active" if cid == active_for else "canceled"})

    # The duplicate guard (payment) and the customer lookup (stripe_utils) each
    # hold their own reference.
    monkeypatch.setattr(payment, "customer_subscription", _subscription)
    monkeypatch.setattr(stripe_utils, "customer_subscription", _subscription)
    return created


def test_checkout_for_a_paying_account_links_the_customer_and_refuses(app_client, monkeypatch):
    """Paid, but confirm_success never ran (the tab closed on Stripe's redirect).

    Refusing is not enough: the 409 must also stamp the customer and refresh the
    cookie, or the account would stay unsubscribed on our side while Stripe bills it.
    """
    register(app_client, username="payer", email="payer@example.com")
    verify_email("payer")
    login(app_client, "payer")
    created = _checkout_fake(
        monkeypatch, [_customer("cus_payer", "payer@example.com", username="payer")], active_for="cus_payer")

    response = app_client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 409
    assert response.json() == {"detail": "This account already has an active subscription."}
    assert created == []
    assert database.get_user("payer").customer_id == "cus_payer"
    assert _cookie_claims(app_client)["customer_id"] == "cus_payer"


def test_checkout_is_refused_for_the_stamped_customer_even_if_its_email_changed(app_client, monkeypatch):
    """The email lookup misses a customer whose address was edited in the billing portal."""
    register(app_client, username="moved", email="moved@example.com")
    verify_email("moved")
    database.update_user_customer_id("username", "moved", "cus_moved")
    login(app_client, "moved")
    created = _checkout_fake(monkeypatch, [], active_for="cus_moved")

    response = app_client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 409
    assert created == []


def test_a_lapsed_subscriber_checks_out_again_on_their_own_customer(app_client, monkeypatch):
    """No active subscription: checkout goes ahead, reusing the stamped customer
    rather than creating a second one when the email lookup comes back empty."""
    register(app_client, username="lapsed", email="lapsed@example.com")
    verify_email("lapsed")
    database.update_user_customer_id("username", "lapsed", "cus_lapsed")
    login(app_client, "lapsed")
    created = _checkout_fake(monkeypatch, [], active_for=None)

    response = app_client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 200
    assert [params["customer"] for params in created] == ["cus_lapsed"]


def test_a_stamped_customer_stripe_no_longer_has_is_not_reused(app_client, monkeypatch):
    """Reusing it would make every checkout fail with Stripe's "No such customer"."""
    register(app_client, username="orphan", email="orphan@example.com")
    verify_email("orphan")
    database.update_user_customer_id("username", "orphan", "cus_gone")
    login(app_client, "orphan")
    created = _checkout_fake(monkeypatch, [], active_for=None, missing={"cus_gone"})

    response = app_client.post("/api/subscription/create-checkout", json={})

    assert response.status_code == 200
    assert [params["customer"] for params in created] == ["cus_new"]


# --- malicious bundle -> arbitrary file write (security regression) ----------

def test_a_bundle_cannot_write_outside_its_temp_dir(tmp_path):
    """XAPKs come from third-party mirrors, so their arcnames are untrusted.

    `main_apk_name` used to be taken verbatim from the zip central directory
    and joined onto the temp dir. extractall() strips `..`, but the raw arcname
    does not, and _embed_token opens with 'a' -- which creates the file. A
    poisoned upstream build could therefore write anywhere the server can.
    """
    import zipfile

    from utils.apps_utils import patch_paywall

    target = tmp_path / "SHOULD_NOT_EXIST.apk"
    escape = f"../../../../{target.relative_to(target.anchor)}"

    inner = tmp_path / "inner.apk"
    with zipfile.ZipFile(inner, "w") as z:
        z.writestr("classes.dex", b"x" * 4096)

    malicious = tmp_path / "evil.xapk"
    with zipfile.ZipFile(malicious, "w") as z:
        z.writestr("base.apk", inner.read_bytes())
        z.writestr(escape, inner.read_bytes() + b"padding-to-make-this-the-largest")

    signed = []
    try:
        patch_paywall(malicious, "tok", output_path=tmp_path / "out.xapk",
                      signer=lambda *a, **k: signed.append(a))
    except Exception:
        pass  # failing closed is an acceptable outcome; escaping is not

    assert not target.exists(), f"bundle escaped its temp dir and wrote {target}"
