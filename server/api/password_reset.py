"""Password reset by email.

Stateless on purpose: the link carries a short-lived JWT bound to the account's
current password hash (see jwt_utils.password_hash_digest), so there is no
token table to migrate, expire or clean up, and a link stops working the moment
it has been used or the password has changed any other way.

Nothing here reveals whether an address has an account, or whether that
account's address is verified: /forgot-password answers the same 200 in every
case, and it answers before looking the address up at all, so neither the
lookup nor the sending can show in its latency. Only verified accounts are
actually sent a link (see api/email_verification.py for the rules).
"""
import hmac
from datetime import datetime, timezone
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, BackgroundTasks, Depends
from pydantic import BaseModel, EmailStr, StringConstraints
from starlette.exceptions import HTTPException

import config
import database
from utils import jwt_utils, mailer
from utils.logging_config import logger
from utils.ratelimit import (
    GLOBAL_KEY,
    forgot_password_rate_limit,
    reset_mail_budget,
    reset_mail_per_address_limit,
    reset_password_rate_limit,
)

router = APIRouter(prefix="/auth")

FORGOT_PASSWORD_MESSAGE = "If an account uses that email, we've sent a reset link. It expires in 1 hour."
RESET_DONE_MESSAGE = "Your password has been updated. You can log in now."
RESET_INVALID_MESSAGE = "This reset link is invalid or has expired."


class ForgotPasswordRequest(BaseModel):
    email: EmailStr


class ResetPasswordRequest(BaseModel):
    # A reset JWT is a few hundred bytes; the bound only stops a megabyte of
    # junk being handed to the JWT parser.
    token: Annotated[str, StringConstraints(strip_whitespace=True, max_length=4096)]
    # The same constraints registration applies.
    password: database.Password


def _reset_link(user) -> str:
    return f"{config.FRONTEND_URL}/reset-password?token={quote(jwt_utils.create_password_reset_token(user))}"


def _reset_mail_body(users) -> str:
    # One mail per address, listing every account on it: users.email is not
    # unique on the live database, and a separate mail per account would read
    # like a glitch (or like spam) to the person receiving them.
    if len(users) == 1:
        intro = f"Someone asked to reset the password of the {config.SITE_HOST} account below."
    else:
        intro = (f"Someone asked to reset the password for this email address, which is "
                 f"used by {len(users)} {config.SITE_HOST} accounts. Each account has its own link.")
    accounts = "\n\n".join(f"Account: {user.username}\nReset link: {_reset_link(user)}" for user in users)
    return (
        f"Hello,\n\n{intro}\n\n{accounts}\n\n"
        "Each link works once and expires in 1 hour.\n\n"
        "If you didn't ask for this, ignore this email: your password stays as it is.\n\n"
        f"Need help? Reply to this email or write to {config.SUPPORT_EMAIL}.\n"
    )


def _send_reset_links(email: str) -> None:
    """Look the address up and mail its links. Runs after the response is sent.

    Everything that depends on whether the address has accounts - the lookup,
    minting a token per account, building the mail - happens here rather than in
    the handler, because doing it there made the answer measurably slower for
    an address with accounts: a timing oracle for exactly what the identical
    answer hides. Failures are logged; the caller has long had their 200.
    """
    try:
        accounts = database.get_users_by_email(email)
        # Only accounts whose address is verified get a link. Registration does
        # not prove an address, so for an unverified account the inbox may
        # belong to a stranger - one typo at signup - and the link would hand
        # them the account. The caller already has the same 200 either way.
        users = [user for user in accounts if user.email_verified_at is not None]
        if not accounts:
            logger.info('Password reset requested for an address with no account')
        elif not users:
            logger.info('Not sending a password reset link for %s: email address not verified',
                        ', '.join(user.username for user in accounts))
        elif reset_mail_per_address_limit.retry_after(email.lower()):
            logger.warning('Not sending another reset mail to %s: per-address limit reached', email)
        elif reset_mail_budget.retry_after(GLOBAL_KEY):
            logger.error('Not sending a reset mail to %s: the daily reset mail budget is spent', email)
        else:
            logger.info('Sending a password reset link for %s', ', '.join(user.username for user in users))
            mailer.send(
                email,
                f"Reset your {config.SITE_HOST} password",
                _reset_mail_body(users),
                reply_to=config.SUPPORT_EMAIL,
            )
    except Exception:
        logger.exception('Could not send password reset links')


@router.post("/forgot-password", dependencies=[Depends(forgot_password_rate_limit)])
def forgot_password(request: ForgotPasswordRequest, background_tasks: BackgroundTasks) -> dict:
    if not mailer.is_configured():
        # The same answer for every address, so it reveals nothing about
        # which ones have accounts.
        raise HTTPException(
            status_code=503,
            detail=f"Password reset by email isn't available right now. Email {config.SUPPORT_EMAIL} for help.",
        )
    background_tasks.add_task(_send_reset_links, request.email)
    return {"message": FORGOT_PASSWORD_MESSAGE}


@router.post("/reset-password", dependencies=[Depends(reset_password_rate_limit)])
def reset_password(request: ResetPasswordRequest) -> dict:
    invalid = HTTPException(status_code=400, detail=RESET_INVALID_MESSAGE)

    claims = jwt_utils.read_password_reset_token(request.token)
    if claims is None:
        raise invalid
    username, pwh = claims

    user = database.get_user(username)
    # A used link fails here: the reset changed the hash its `pwh` was cut from.
    if user is None or not hmac.compare_digest(pwh, jwt_utils.password_hash_digest(user.password_hash)):
        logger.info('Refused a stale or used password reset link for %s', username)
        raise invalid
    # Using the link proves the inbox, so the same write verifies the address if
    # it was not already. The token is not bound to the address - it cannot be
    # changed today - so an address-change feature must bind it before this
    # stays true.
    if not database.set_password(user, request.password, email_verified_at=datetime.now(timezone.utc)):
        # Lost a race against another use of the same link.
        raise invalid

    logger.info('Password reset for user %s', user.username)
    return {"message": RESET_DONE_MESSAGE}
