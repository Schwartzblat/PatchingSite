"""Bearer-token auth for clients that cannot hold a cookie (the Android app).

These endpoints mint the *same* kind of session token the cookie carries - same key,
same `typ` - so every existing endpoint accepts it without change. Two things differ.
The lifetime, because re-entering a password every three hours on a phone is not
acceptable. And the `client` claim ("mobile" here, "web" from /login): both kinds are
signed with one key, so a 90-day phone token sent as a cookie would otherwise open the
admin panel, which refuses anything but "web". There is no refresh token and no
server-side revocation; the tradeoff is written up in the design doc.
"""
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from starlette import status
from starlette.exceptions import HTTPException

import database
from utils.jwt_utils import (
    MOBILE_TOKEN_EXPIRE,
    MOBILE_TOKEN_MAX_LIFETIME,
    RequiredToken,
    UserInDB,
    TOKEN_CLIENT_MOBILE,
    create_access_token,
    get_current_user,
)
from utils.logging_config import logger
from utils.ratelimit import login_rate_limit

router = APIRouter(prefix="/auth")

# `RequiredUser` is annotated as `User`, which has no customer_id. The dependency
# really returns a UserInDB, and create_access_token needs the full row.
CurrentUser = Annotated[UserInDB, Depends(get_current_user)]


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int  # seconds, as OAuth 2.0 uses it


def _issue(
    user: UserInDB,
    ttl: timedelta = MOBILE_TOKEN_EXPIRE,
    issued_at: datetime | None = None,
) -> TokenResponse:
    return TokenResponse(
        access_token=create_access_token(user, expires_in=ttl, issued_at=issued_at, client=TOKEN_CLIENT_MOBILE),
        expires_in=int(ttl.total_seconds()),
    )


@router.post("/token", dependencies=[Depends(login_rate_limit)])
def issue_token(request: database.LoginRequest) -> TokenResponse:
    user = database.login_user(request)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    logger.debug('Issued mobile token for %s', user.username)
    return _issue(user)


@router.post("/renew")
def renew_token(current_user: CurrentUser, token: RequiredToken) -> TokenResponse:
    """Re-mint from the live row: slides the expiry and refreshes stale claims.

    `customer_id` is baked into the token, so a phone that subscribed after signing
    in holds a token that says it has no customer until it calls this.

    Sliding the expiry is not unbounded. The renewed token carries the ORIGINAL
    `iat` forward, and once the chain is older than MOBILE_TOKEN_MAX_LIFETIME the
    renewal is refused with a 401, which sends the app back to the sign-in screen.
    Without the ceiling a stolen token could be renewed every month forever, since
    there is no server-side revocation.

    A token minted before `iat` existed has none. Those are treated as issued now
    rather than refused, so deploying this does not sign anybody out.
    """
    now = datetime.now(timezone.utc)
    issued_at = token.iat
    # A future `iat` is clamped rather than trusted. Only this server can mint one,
    # but an unclamped future value would make `now - issued_at` negative and the
    # ceiling silently unreachable - the failure mode this cap exists to prevent.
    if issued_at is None or issued_at > now:
        issued_at = now
    elif now - issued_at > MOBILE_TOKEN_MAX_LIFETIME:
        logger.info('Refusing to renew an expired session chain for %s', current_user.username)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session expired. Please sign in again.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return _issue(current_user, issued_at=issued_at)
