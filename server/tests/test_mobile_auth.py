"""Bearer-token endpoints used by the Android client. Stripe is never touched."""
import pytest
from starlette.testclient import TestClient

import config
import database

GOOD_PASSWORD = "correct horse battery"


@pytest.fixture
def client(tmp_db):
    import main

    with TestClient(main.app) as test_client:
        yield test_client


def register(client, username="alice"):
    return client.post("/api/register", json={
        "username": username, "email": f"{username}@example.com", "password": GOOD_PASSWORD,
    })


def get_token(client, username="alice", password=GOOD_PASSWORD):
    return client.post("/api/auth/token", json={"username": username, "password": password})


def test_token_endpoint_returns_a_usable_bearer_token(client):
    register(client)
    response = get_token(client)
    assert response.status_code == 200

    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["expires_in"] == config.MOBILE_TOKEN_TTL_DAYS * 24 * 60 * 60

    # No cookie: this endpoint is for clients that cannot hold one.
    assert "token" not in client.cookies

    me = client.get("/api/users/me", headers={"Authorization": f"Bearer {body['access_token']}"})
    assert me.status_code == 200
    assert me.json()["username"] == "alice"


def test_token_endpoint_accepts_an_email_and_the_token_names_the_username(client):
    register(client)
    response = get_token(client, username="Alice@Example.com")
    assert response.status_code == 200

    token = response.json()["access_token"]
    me = client.get("/api/users/me", headers={"Authorization": f"Bearer {token}"})
    assert me.json()["username"] == "alice"


def test_token_endpoint_rejects_a_bad_password(client):
    register(client)
    assert get_token(client, password="wrong password entirely").status_code == 401


def test_renew_requires_authentication(client):
    assert client.post("/api/auth/renew").status_code == 401


def test_renew_picks_up_a_customer_id_written_after_the_token_was_minted(client):
    """The reason renew exists: customer_id is a JWT claim, and checkout sets it later."""
    register(client)
    original = get_token(client).json()["access_token"]

    database.update_user_customer_id("username", "alice", "cus_after_checkout")

    renewed = client.post("/api/auth/renew", headers={"Authorization": f"Bearer {original}"})
    assert renewed.status_code == 200

    from utils import jwt_utils
    claims = jwt_utils.decode_token(renewed.json()["access_token"])
    assert claims["customer_id"] == "cus_after_checkout"


def test_renew_works_from_a_session_cookie_too(client):
    register(client)
    assert client.post("/api/login", json={"username": "alice", "password": GOOD_PASSWORD}).status_code == 200
    assert client.post("/api/auth/renew").status_code == 200


def test_token_endpoint_is_rate_limited(client):
    register(client)
    from utils.ratelimit import login_rate_limit

    statuses = [get_token(client, password="nope").status_code for _ in range(40)]
    assert 429 in statuses, "brute force against /auth/token must be throttled"
    login_rate_limit.reset()


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _token_aged(days):
    """A session token whose renewal chain started `days` ago.

    The expiry is still in the future - only the issue date is old, which is
    exactly the shape a token renewed every month for a year would have.
    """
    from datetime import datetime, timedelta, timezone

    from utils import jwt_utils

    user = database.get_user(username="alice")
    return jwt_utils.create_access_token(
        user,
        expires_in=jwt_utils.MOBILE_TOKEN_EXPIRE,
        issued_at=datetime.now(timezone.utc) - timedelta(days=days),
        client=None,
    )


def test_issued_tokens_carry_an_issued_at_claim(client):
    register(client)
    from utils import jwt_utils

    claims = jwt_utils.decode_token(get_token(client).json()["access_token"])
    assert "iat" in claims, "the absolute lifetime cap is measured from iat"


def test_renew_works_inside_the_absolute_lifetime_cap(client):
    register(client)
    aged = _token_aged(config.MOBILE_TOKEN_MAX_LIFETIME_DAYS - 1)

    assert client.post("/api/auth/renew", headers=_bearer(aged)).status_code == 200


def test_renew_is_refused_once_the_absolute_lifetime_cap_is_passed(client):
    """A stolen token must not be renewable forever: 30 days sliding, 90 absolute."""
    register(client)
    aged = _token_aged(config.MOBILE_TOKEN_MAX_LIFETIME_DAYS + 1)

    response = client.post("/api/auth/renew", headers=_bearer(aged))
    assert response.status_code == 401, "the renewal chain has to end somewhere"

    # The old token is still accepted elsewhere until it expires on its own - this
    # is a cap on renewal, not the server-side revocation the design ruled out.
    assert client.get("/api/users/me", headers=_bearer(aged)).status_code == 200


def test_renew_stamps_an_issued_at_on_a_token_that_has_none(client):
    """Backwards compatibility: nobody gets signed out by deploying the cap."""
    import jwt

    from utils import jwt_utils

    register(client)
    user = database.get_user(username="alice")
    from datetime import datetime, timedelta, timezone

    legacy = jwt.encode(
        {
            "username": user.username,
            "email": user.email,
            "customer_id": "",
            "typ": jwt_utils.TOKEN_TYPE_SESSION,
            "exp": datetime.now(timezone.utc) + timedelta(days=10),
        },
        jwt_utils.SECRET_KEY,
        algorithm=jwt_utils.ALGORITHM,
    )
    assert "iat" not in jwt_utils.decode_token(legacy)

    response = client.post("/api/auth/renew", headers=_bearer(legacy))
    assert response.status_code == 200
    assert "iat" in jwt_utils.decode_token(response.json()["access_token"])


def test_renewal_does_not_slide_the_absolute_cap(client):
    """The renewed token keeps the ORIGINAL iat, or the ceiling would never arrive."""
    register(client)
    from utils import jwt_utils

    aged = _token_aged(config.MOBILE_TOKEN_MAX_LIFETIME_DAYS - 1)
    original_iat = jwt_utils.decode_token(aged)["iat"]

    renewed = client.post("/api/auth/renew", headers=_bearer(aged)).json()["access_token"]
    assert jwt_utils.decode_token(renewed)["iat"] == original_iat


def test_a_token_minted_slightly_ahead_of_our_clock_is_still_accepted(client):
    """Every session token now carries iat, and PyJWT rejects a future one by default.

    Single-instance today, but a second app instance whose clock ran a few seconds
    ahead would otherwise have its freshly minted cookies rejected here.
    """
    import jwt as pyjwt
    from datetime import datetime, timedelta, timezone

    from utils import jwt_utils

    register(client)
    user = database.get_user(username="alice")
    ahead = pyjwt.encode(
        {
            "username": user.username,
            "email": user.email,
            "customer_id": "",
            "typ": jwt_utils.TOKEN_TYPE_SESSION,
            "iat": datetime.now(timezone.utc) + timedelta(seconds=5),
            "exp": datetime.now(timezone.utc) + timedelta(days=10),
        },
        jwt_utils.SECRET_KEY,
        algorithm=jwt_utils.ALGORITHM,
    )

    assert client.get("/api/users/me", headers=_bearer(ahead)).status_code == 200


def test_a_future_issued_at_cannot_lift_the_absolute_cap(client):
    """The clamp in renew_token: a future iat must not make the ceiling unreachable."""
    import jwt as pyjwt
    from datetime import datetime, timedelta, timezone

    from utils import jwt_utils

    register(client)
    user = database.get_user(username="alice")
    now = datetime.now(timezone.utc)
    ahead = pyjwt.encode(
        {
            "username": user.username,
            "email": user.email,
            "customer_id": "",
            "typ": jwt_utils.TOKEN_TYPE_SESSION,
            "iat": now + timedelta(days=3650),
            "exp": now + timedelta(days=10),
        },
        jwt_utils.SECRET_KEY,
        algorithm=jwt_utils.ALGORITHM,
    )

    renewed = client.post("/api/auth/renew", headers=_bearer(ahead))
    assert renewed.status_code == 200
    claims = jwt_utils.decode_token(renewed.json()["access_token"])
    # Restamped to now, so the chain is bounded from here rather than from the year 2036.
    assert claims["iat"] <= int(datetime.now(timezone.utc).timestamp()) + 1
