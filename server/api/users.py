from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, StringConstraints
from starlette import status
from starlette.responses import Response

import config
import database
from api import email_verification
from utils.jwt_utils import (
    SESSION_COOKIE_MAX_AGE_SECONDS,
    RequiredUser,
    User,
    UserInDB,
    TOKEN_CLIENT_WEB,
    create_access_token,
    get_current_user,
)
from utils.logging_config import logger
from utils.passwords import verify_password
from utils.ratelimit import change_password_rate_limit, login_rate_limit, register_rate_limit
from utils.stripe_utils import forget_customer_lookup, invalidate_subscription_status

router = APIRouter()

COOKIE_NAME = "token"
# delete_cookie only matches the cookie when these are identical to set_cookie.
COOKIE_ATTRS = {"path": "/", "httponly": True, "samesite": "lax"}


class Me(User):
    # A boolean rather than the timestamp: clients only ever branch on it. Read
    # from the row on every call, so a client that polls this notices the link
    # was opened in another tab or on another device.
    email_verified: bool
    # Whether the caller may use the admin panel (config.ADMIN_USERNAMES), so
    # the web client knows whether to offer the link. It only ever describes
    # the caller; every admin endpoint checks for itself.
    is_admin: bool


TAKEN_MESSAGE = "That username or email is already in use."


@router.post('/register', dependencies=[Depends(register_rate_limit)])
def register_user(request: database.RegisterRequest) -> dict:
    # A name listed in ADMIN_USERNAMES before its account exists would hand the
    # admin panel to whoever registered it first. Refused with the words any
    # taken name gets, so the answer says nothing about who the admins are.
    if config.is_admin(request.username):
        logger.warning('Refused to register %s: the name is listed in ADMIN_USERNAMES', request.username)
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=TAKEN_MESSAGE)
    result = database.register_user(request)
    if result:
        # Queued, not sent: the SMTP session runs on the mailer's executor. False
        # tells the client to offer a resend or the support address instead of
        # "check your inbox" (no SMTP, or the day's mail budget is spent).
        sent = email_verification.send_verification_email(request.username, request.email)
        return {"message": "User registered successfully", "verification_sent": sent}
    logger.warning('Failed to register user %s (%s)', request.username, result.value)
    # One generic message for any collision. Saying whether the username or the
    # email was taken would let a client enumerate which accounts already exist.
    raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=TAKEN_MESSAGE)


# Plain `def`, so FastAPI runs it on the threadpool: every login - failed ones
# included, see passwords.spend_a_verify - costs an Argon2 verify, which as an
# `async def` ran on the event loop and stalled every other request meanwhile.
@router.post("/login", dependencies=[Depends(login_rate_limit)])
def login_for_access_token(response: Response, request: database.LoginRequest) -> dict:
    user = database.login_user(request)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    access_token = create_access_token(user, client=TOKEN_CLIENT_WEB)
    response.set_cookie(
        key=COOKIE_NAME,
        value=f'Bearer {access_token}',
        max_age=SESSION_COOKIE_MAX_AGE_SECONDS,
        secure=config.IS_PRODUCTION,
        **COOKIE_ATTRS,
    )
    return {'message': 'Login successful'}


@router.post("/users/logout")
async def logout_user(response: Response):
    response.delete_cookie(key=COOKIE_NAME, secure=config.IS_PRODUCTION, **COOKIE_ATTRS)
    return {"message": "Logout successful"}


@router.get("/users/me", response_model=Me)
async def read_users_me(current_user: RequiredUser):
    return Me(
        username=current_user.username,
        email=current_user.email,
        created_at=current_user.created_at,
        email_verified=current_user.email_verified_at is not None,
        is_admin=config.is_admin(current_user.username),
    )


# --- device slots -------------------------------------------------------------------
#
# Patched apps register each phone they run on (is_device_allowed) up to
# MAX_DEVICES_ALLOWED. Users used to have to email support to free a slot after
# replacing a phone; now they can clear them all themselves, at most once per
# DEVICE_RESET_INTERVAL_DAYS so one account cannot be rotated around a group.

DEVICE_RESET_INTERVAL = timedelta(days=config.DEVICE_RESET_INTERVAL_DAYS)

# RequiredUser is annotated as `User`; the dependency really returns the full row.
CurrentUser = Annotated[UserInDB, Depends(get_current_user)]


def _iso(moment: datetime | None) -> str | None:
    return moment.astimezone(timezone.utc).strftime(database.TIMESTAMP_FORMAT) if moment else None


def _next_reset_at(user: UserInDB, now: datetime) -> datetime | None:
    """When the next reset opens up, or None if one is allowed now."""
    if user.devices_reset_at is None:
        return None
    next_at = user.devices_reset_at + DEVICE_RESET_INTERVAL
    return next_at if next_at > now else None


def _devices_state(user: UserInDB, now: datetime) -> dict:
    next_at = _next_reset_at(user, now)
    return {
        "used": len(user.device_fingerprints),
        "limit": config.MAX_DEVICES_ALLOWED,
        "last_reset_at": _iso(user.devices_reset_at),
        "next_reset_at": _iso(next_at),
        "can_reset": next_at is None,
    }


def _long_date(moment: datetime) -> str:
    """'October 30, 2026'. %B is English here: the server never calls setlocale."""
    return f"{moment:%B} {moment.day}, {moment.year}"


@router.get("/users/me/devices")
def read_devices(current_user: CurrentUser) -> dict:
    return _devices_state(current_user, datetime.now(timezone.utc))


@router.post("/users/me/devices/reset")
def reset_devices(current_user: CurrentUser) -> dict:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    result, last_reset_at = database.reset_devices(current_user.username, now, DEVICE_RESET_INTERVAL)
    if result is database.DeviceResetResult.USER_NOT_FOUND:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required!")
    if result is database.DeviceResetResult.REFUSED:
        next_at = last_reset_at + DEVICE_RESET_INTERVAL
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=f"You can reset your devices again on {_long_date(next_at)}.",
            headers={"Retry-After": str(max(1, int((next_at - now).total_seconds())))},
        )

    # A reset is the moment the user's phones start re-asking is_device_allowed;
    # they should get an answer fresh from Stripe when they do. An account
    # resetting before it is linked gets a fresh customer lookup too.
    invalidate_subscription_status(current_user.customer_id)
    forget_customer_lookup(current_user.username)
    refreshed = database.get_user(current_user.username)
    if refreshed is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Authentication required!")
    return _devices_state(refreshed, now)


# --- password -----------------------------------------------------------------------
#
# Changing the password while signed in. Other sessions are left signed in, on
# purpose: session tokens are not bound to the password, so there is nothing to
# end them with (a reset by email leaves them too). A reset link mailed before
# the change does die, because it is bound to the old hash.

class ChangePasswordRequest(BaseModel):
    # Checked, not validated: an account older than the length rules may still
    # have a shorter password. The bound is LoginRequest's.
    current_password: Annotated[str, StringConstraints(max_length=1024)]
    # The same constraints registration applies.
    new_password: database.Password


# Plain `def`, like /login: the verify and the new hash are both Argon2, and
# belong on the threadpool rather than the event loop.
@router.post("/users/me/password", dependencies=[Depends(change_password_rate_limit)])
def change_password(request: ChangePasswordRequest, current_user: CurrentUser) -> dict:
    if not verify_password(request.current_password, current_user.password_hash,
                           current_user.password_salt, current_user.password_scheme):
        logger.info('Refused a password change for %s: wrong current password', current_user.username)
        # 400, not 401: the web client reads any 401 as an expired session and
        # would send the user to the login page.
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Your current password is incorrect.")
    if request.new_password == request.current_password:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Choose a new password that is different from your current one.",
        )
    # No email_verified_at: knowing the password proves nothing about the inbox.
    if not database.set_password(current_user, request.new_password):
        # The password changed between reading the row and this write: another
        # tab, or a reset link. That change wins; this one is not applied.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Your password was changed somewhere else meanwhile. Reload the page and try again.",
        )
    logger.info('Password changed for user %s', current_user.username)
    return {"message": "Your password has been changed."}
