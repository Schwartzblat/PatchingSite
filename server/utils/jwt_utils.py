import hashlib
import hmac
from datetime import timedelta, datetime, timezone
from typing import Annotated, List

import jwt
from fastapi import Depends
from pydantic import BaseModel, Field
from starlette import status
from starlette.exceptions import HTTPException
from starlette.requests import Request

import config
from utils.logging_config import logger
from utils.passwords import SCHEME_LEGACY

ALGORITHM = "HS256"

# Session cookies and APK-embedded tokens are signed with different keys and
# carry a mandatory `typ`, so a token lifted out of an APK can never be replayed
# as a browser session.
SECRET_KEY = config.JWT_SECRET_KEY
APP_SECRET_KEY = config.APP_JWT_SECRET_KEY

TOKEN_TYPE_SESSION = "session"

# Where a session token was minted. /login (the browser's cookie) and /auth/token
# (the phone's bearer token) produce the same kind of token under the same key,
# and a phone token can be sent as a cookie by hand, so nothing but this claim
# tells the two apart. The admin panel takes web tokens only: a phone token is
# unrevocable and lives up to 90 days. None means minted before the claim existed.
TOKEN_CLIENT_WEB = "web"
TOKEN_CLIENT_MOBILE = "mobile"
TOKEN_TYPE_APP = "app"
# Mailed in a reset link. Signed with the session key, so every other validator
# must - and does - refuse it by `typ`: get_token_data wants "session", and the
# legacy app-token path refuses any token that carries a `typ` at all.
TOKEN_TYPE_PASSWORD_RESET = "password_reset"
# Mailed in a verification link, and refused everywhere else the same way: by
# `typ` on the session path, by carrying a `typ` at all on the legacy app-token
# path, and by `typ` (and its missing `pwh`) on the reset path.
TOKEN_TYPE_EMAIL_VERIFY = "email_verify"

ACCESS_TOKEN_EXPIRE_MINUTES = timedelta(minutes=config.SESSION_TOKEN_TTL_MINUTES)
APP_TOKEN_EXPIRE = timedelta(days=config.APP_TOKEN_TTL_DAYS)
MOBILE_TOKEN_EXPIRE = timedelta(days=config.MOBILE_TOKEN_TTL_DAYS)
# How long a renewal chain may run before the user has to sign in again. See
# config.MOBILE_TOKEN_MAX_LIFETIME_DAYS.
MOBILE_TOKEN_MAX_LIFETIME = timedelta(days=config.MOBILE_TOKEN_MAX_LIFETIME_DAYS)
PASSWORD_RESET_EXPIRE = timedelta(hours=1)
# Long enough to survive a weekend and a spam folder. The link only proves an
# inbox, it grants nothing on its own, so the reset link's hour would be all
# cost and no safety here.
EMAIL_VERIFY_EXPIRE = timedelta(hours=48)
# PyJWT validates `iat` whenever it is present and rejects one in the future with
# ImmatureSignatureError, using no leeway by default. Every session token now
# carries `iat`, the cookie's included, so a second app instance whose clock ran a
# little ahead of the one validating would start rejecting freshly minted cookies
# on a path the web client depends on.
#
# The fix is to stop validating `iat`, not to add `leeway`: leeway is global in
# PyJWT and widens `exp` by the same amount, which would quietly start accepting
# expired tokens (test_auth.py pins a 5-second-expired token as rejected, and it
# is right to). Nothing is lost by not validating it - `iat` is signed with
# SECRET_KEY, so only this server mints it, and the one thing it is used for (the
# renewal cap in api/auth.py) clamps a future value itself.
# Set-Cookie max-age is seconds, not a timedelta.
SESSION_COOKIE_MAX_AGE_SECONDS = config.SESSION_TOKEN_TTL_MINUTES * 60

CREDENTIALS_EXCEPTION = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED,
    detail="Authentication required!",
)


class Token(BaseModel):
    access_token: str
    token_type: str


class TokenData(BaseModel):
    username: str | None = None
    email: str | None = None
    customer_id: str | None = None
    typ: str = TOKEN_TYPE_SESSION
    # TOKEN_CLIENT_*, or None for a token minted before this claim existed.
    client: str | None = None
    # Optional because tokens minted before this claim existed are still valid and
    # must keep working. /auth/renew treats a missing `iat` as "issued now".
    iat: datetime | None = None
    exp: datetime


class AppTokenData(BaseModel):
    username: str | None = None
    customer_id: str | None = None
    package_name: str | None = None
    typ: str | None = None
    exp: datetime | None = None


class User(BaseModel):
    username: str
    email: str | None = None
    created_at: datetime | None = None

class UserInDB(User):
    # The users.id row key, for tables that reference the account. Kept out of
    # dumps so it never reaches a response body.
    id: int | None = Field(default=None, exclude=True)
    # excluded from every dump so credentials cannot leak into a response body
    password_hash: str = Field(exclude=True, repr=False)
    password_salt: str = Field(exclude=True, repr=False)
    password_scheme: str = Field(default=SCHEME_LEGACY, exclude=True, repr=False)
    customer_id: str | None = None
    device_fingerprints: List[str] = []
    # When the user last cleared their device slots (UTC), or None if never.
    devices_reset_at: datetime | None = None
    # When the address in `email` was proven to be the user's (UTC), or None.
    # Deliberately not a session token claim: a token outlives the moment it was
    # minted, so anything that depends on verification reads the row instead.
    email_verified_at: datetime | None = None


def create_access_token(
    user: UserInDB,
    expires_in: timedelta | None = None,
    issued_at: datetime | None = None,
    *,
    client: str | None,
) -> str:
    """Mint a session token.

    `client` is required so every mint site has to say where the token goes
    (TOKEN_CLIENT_WEB or TOKEN_CLIENT_MOBILE; None only for tests of legacy tokens).

    `issued_at` is carried forward by /auth/renew so the absolute lifetime of a
    renewal chain is measured from the first token in it, not from the last
    renewal. Everything else - the cookie path included - stamps `iat` with now.
    """
    now = datetime.now(timezone.utc)
    to_encode = TokenData(
        username=user.username,
        email=user.email,
        customer_id=user.customer_id if user.customer_id else '',
        typ=TOKEN_TYPE_SESSION,
        client=client,
        iat=issued_at or now,
        exp=now + (expires_in or ACCESS_TOKEN_EXPIRE_MINUTES),
    )
    return jwt.encode(to_encode.model_dump(), SECRET_KEY, algorithm=ALGORITHM)


def create_app_jwt(token: TokenData, package_name: str, customer_id: str | None = None) -> str:
    """Mint the token a patched APK carries and posts to is_device_allowed.

    `customer_id` is how the phone's device check finds the account, so the
    download flow passes the one on the account's row (resolved by
    RequiredSubscription). The session token's own claim is only the fallback:
    it was stamped at sign-in, and for an account linked since then it is
    empty, which baked an APK that could never find its account.
    """
    customer_id = customer_id or token.customer_id
    to_encode = AppTokenData(
        username=token.username,
        customer_id=customer_id if customer_id else '',
        package_name=package_name,
        typ=TOKEN_TYPE_APP,
        exp=datetime.now(timezone.utc) + APP_TOKEN_EXPIRE,
    )
    return jwt.encode(to_encode.model_dump(), APP_SECRET_KEY, algorithm=ALGORITHM)


def password_hash_digest(password_hash: str) -> str:
    """The `pwh` claim of a reset token: a short keyed digest of the stored hash.

    Binding the token to the hash it was issued against is what makes a reset
    link single-use without any server-side state: the reset changes the hash,
    and with it the digest every outstanding link carries. So does any other
    password change, including the transparent legacy-to-Argon2 upgrade on login.
    Keyed, so the claim (readable by anyone holding the link) says nothing about
    the hash itself.
    """
    message = b"pwh:" + password_hash.encode()
    return hmac.new(SECRET_KEY.encode(), message, hashlib.sha256).hexdigest()[:32]


def create_password_reset_token(user: UserInDB) -> str:
    now = datetime.now(timezone.utc)
    return jwt.encode({
        "typ": TOKEN_TYPE_PASSWORD_RESET,
        "username": user.username,
        "pwh": password_hash_digest(user.password_hash),
        "iat": now,
        "exp": now + PASSWORD_RESET_EXPIRE,
    }, SECRET_KEY, algorithm=ALGORITHM)


def read_password_reset_token(token: str) -> tuple[str, str] | None:
    """(username, pwh) from a valid, unexpired reset token; None for anything else.

    Every other kind of token is refused: a session token by its `typ`, an app
    token by its key, a legacy app token by its missing `typ` and `exp`. The
    caller still has to compare `pwh` against the live row.
    """
    try:
        payload = jwt.decode(
            token,
            SECRET_KEY,
            algorithms=[ALGORITHM],
            options={"require": ["exp", "typ", "username", "pwh"], "verify_iat": False},
        )
    except jwt.PyJWTError as e:
        logger.debug('Password reset token rejected: %s', e)
        return None
    if payload.get("typ") != TOKEN_TYPE_PASSWORD_RESET:
        logger.warning('Rejected %r token presented as a password reset token', payload.get("typ"))
        return None
    username, pwh = payload.get("username"), payload.get("pwh")
    if not isinstance(username, str) or not isinstance(pwh, str):
        return None
    return username, pwh


def create_email_verification_token(username: str, email: str) -> str:
    """A link token proving that whoever opens it reads `email`.

    The address is a claim rather than looked up when the link is opened, so a
    link sent to an address the account has since stopped using verifies
    nothing (database.mark_email_verified matches on both). Stateless like the
    reset token: nothing to store, and it can be used any number of times,
    which is harmless since verifying twice changes nothing.
    """
    now = datetime.now(timezone.utc)
    return jwt.encode({
        "typ": TOKEN_TYPE_EMAIL_VERIFY,
        "username": username,
        "email": email,
        "iat": now,
        "exp": now + EMAIL_VERIFY_EXPIRE,
    }, SECRET_KEY, algorithm=ALGORITHM)


def read_email_verification_token(token: str) -> tuple[str, str] | None:
    """(username, email) from a valid, unexpired verification token; None for anything else.

    Every other kind of token is refused: session and reset tokens by `typ`, an
    app token by its key, a legacy app token by its missing `typ` and `exp`.
    """
    try:
        payload = jwt.decode(
            token,
            SECRET_KEY,
            algorithms=[ALGORITHM],
            options={"require": ["exp", "typ", "username", "email"], "verify_iat": False},
        )
    except jwt.PyJWTError as e:
        logger.debug('Email verification token rejected: %s', e)
        return None
    if payload.get("typ") != TOKEN_TYPE_EMAIL_VERIFY:
        logger.warning('Rejected %r token presented as an email verification token', payload.get("typ"))
        return None
    username, email = payload.get("username"), payload.get("email")
    if not isinstance(username, str) or not isinstance(email, str) or not username or not email:
        return None
    return username, email


def decode_token(token: str) -> dict:
    """Decode a session token. Expiry and `typ` are mandatory."""
    return jwt.decode(
        token,
        SECRET_KEY,
        algorithms=[ALGORITHM],
        options={"require": ["exp", "typ", "username"], "verify_iat": False},
    )


def _client_host(request: Request) -> str:
    return request.client.host if request.client else "unknown"


def _bearer_token(request: Request) -> str:
    """The session cookie, or an Authorization header for clients that have none.

    Order matters: a browser always sends the cookie, and must not be affected by
    a stale or attacker-supplied header. Accepting the header costs nothing in CSRF
    terms - a cross-origin page cannot set it without a preflight we do not grant.
    """
    raw = request.cookies.get("token", '').strip()
    if not raw:
        raw = request.headers.get("authorization", '').strip()
    prefix, _, rest = raw.partition(" ")
    if prefix.lower() == "bearer":
        return rest.strip()
    return raw


def get_token_data(request: Request) -> TokenData:
    token = _bearer_token(request)
    if not token:
        raise CREDENTIALS_EXCEPTION
    try:
        payload = decode_token(token)
    except jwt.PyJWTError as e:
        # never log the token itself
        logger.debug('Session token rejected for %s: %s', _client_host(request), e)
        raise CREDENTIALS_EXCEPTION
    if payload.get("typ") != TOKEN_TYPE_SESSION:
        logger.warning('Rejected %r token presented as a session cookie from %s',
                       payload.get("typ"), _client_host(request))
        raise CREDENTIALS_EXCEPTION
    try:
        return TokenData.model_validate(payload)
    except ValueError as e:
        logger.debug('Malformed session token from %s: %s', _client_host(request), e)
        raise CREDENTIALS_EXCEPTION


def get_optional_token_data(request: Request) -> TokenData | None:
    """The caller's session if they presented a valid one, else None - never a 401.

    For endpoints that anyone may call but that want to know who is asking when
    they can. A missing, expired or foreign token all read as anonymous.
    """
    try:
        return get_token_data(request)
    except HTTPException:
        return None


def _legacy_app_token_data(token: str) -> AppTokenData:
    """Accept tokens minted before the split-key scheme so installed APKs keep working."""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
    except jwt.PyJWTError as e:
        logger.debug('App token rejected: %s', e)
        raise CREDENTIALS_EXCEPTION
    if payload.get("typ") is not None or not payload.get("package_name") or not payload.get("username"):
        logger.warning('Rejected %r token presented as an app token', payload.get("typ"))
        raise CREDENTIALS_EXCEPTION
    # Read from config on each call, like everything else here, so tests (and a
    # future reload) see the current value rather than one frozen at import.
    cutoff = config.LEGACY_APP_TOKEN_CUTOFF
    if cutoff is not None and datetime.now(timezone.utc) >= cutoff:
        logger.warning('Rejected legacy app token for user %s (%s): LEGACY_APP_TOKEN_CUTOFF %s has passed',
                       payload.get("username"), payload.get("package_name"), cutoff.date().isoformat())
        raise CREDENTIALS_EXCEPTION
    logger.warning('Accepted legacy app token for user %s (%s): old signing key, no expiry',
                   payload.get("username"), payload.get("package_name"))
    return AppTokenData.model_validate(payload)


def get_app_token_data(token: str) -> AppTokenData:
    if not token:
        raise CREDENTIALS_EXCEPTION
    try:
        payload = jwt.decode(
            token,
            APP_SECRET_KEY,
            algorithms=[ALGORITHM],
            options={"require": ["exp", "typ", "username"]},
        )
    except jwt.PyJWTError:
        return _legacy_app_token_data(token)
    if payload.get("typ") != TOKEN_TYPE_APP:
        logger.warning('Rejected %r token presented as an app token', payload.get("typ"))
        raise CREDENTIALS_EXCEPTION
    try:
        return AppTokenData.model_validate(payload)
    except ValueError as e:
        logger.debug('Malformed app token: %s', e)
        raise CREDENTIALS_EXCEPTION


async def get_current_user(request: Request) -> UserInDB:
    import database  # deferred: database imports UserInDB from this module

    token_data = get_token_data(request)
    user = database.get_user(username=token_data.username)
    if user is None:
        raise CREDENTIALS_EXCEPTION
    return user


RequiredUser = Annotated[User, Depends(get_current_user)]
RequiredToken = Annotated[TokenData, Depends(get_token_data)]
