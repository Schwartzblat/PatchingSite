"""Email verification.

The rules, as the owner set them: an unverified account can sign in and browse,
but cannot subscribe (create-checkout answers 403) and is not sent password
reset links. Accounts that existed before this shipped were marked verified by
the migration that added the column (database._add_email_verified_at), so no
customer had to do anything.

Stateless, like password reset: the link carries a 48-hour JWT naming the
account and the address it was sent to, so there is no token table to migrate
or clean up. The state that matters, users.email_verified_at, is always read
from the row - never from a session token, which outlives the moment it was
minted by up to 90 days on a phone.
"""
from datetime import datetime, timezone
from typing import Annotated
from urllib.parse import quote

from fastapi import APIRouter, Depends
from pydantic import BaseModel, StringConstraints
from starlette import status
from starlette.exceptions import HTTPException

import config
import database
from utils import jwt_utils, mailer
from utils.jwt_utils import UserInDB, get_current_user
from utils.logging_config import logger
from utils.ratelimit import (
    GLOBAL_KEY,
    resend_verification_per_account_limit,
    resend_verification_rate_limit,
    reset_mail_budget,
    verify_email_rate_limit,
)

router = APIRouter(prefix="/auth")

# RequiredUser is annotated as `User`; the dependency really returns the full row,
# and the row is what carries email_verified_at.
CurrentUser = Annotated[UserInDB, Depends(get_current_user)]

VERIFIED_MESSAGE = "Your email address is verified."
VERIFY_INVALID_MESSAGE = "This verification link is invalid or has expired."
ALREADY_VERIFIED_MESSAGE = "Your email address is already verified."


def mail_unavailable_message() -> str:
    # Built per call, not at import, so it always names the configured address.
    return f"Email isn't available right now. Contact {config.SUPPORT_EMAIL} for help."


class VerifyEmailRequest(BaseModel):
    # A verification JWT is a few hundred bytes; the bound only stops a
    # megabyte of junk being handed to the JWT parser.
    token: Annotated[str, StringConstraints(strip_whitespace=True, max_length=4096)]


def _verification_link(username: str, email: str) -> str:
    token = jwt_utils.create_email_verification_token(username, email)
    return f"{config.FRONTEND_URL}/verify-email?token={quote(token)}"


def _verification_mail_body(username: str, email: str) -> str:
    return (
        f"Hello {username},\n\n"
        f"Confirm that this is the email address of your {config.SITE_HOST} account "
        f"by opening this link:\n\n"
        f"{_verification_link(username, email)}\n\n"
        "The link expires in 48 hours.\n\n"
        "You need a verified address to subscribe and to reset your password by email.\n\n"
        "If you didn't create this account, ignore this email.\n\n"
        f"Need help? Reply to this email or write to {config.SUPPORT_EMAIL}.\n"
    )


def send_verification_email(username: str, email: str) -> bool:
    """Queue a verification link for this account and address. True when queued.

    False, and logged, when there is no SMTP, when the day's mail budget is
    spent, or when the message could not even be built. Never raises: a signup
    must not fail because mail did. The SMTP session runs on the mailer's
    executor, so nothing here waits on a mail server.
    """
    if not mailer.is_configured():
        logger.warning('Not sending a verification link to %s: SMTP is not configured', username)
        return False
    if reset_mail_budget.retry_after(GLOBAL_KEY):
        logger.error('Not sending a verification link to %s: the daily mail budget is spent', username)
        return False
    try:
        mailer.send(
            email,
            f"Verify your email for {config.SITE_HOST}",
            _verification_mail_body(username, email),
            reply_to=config.SUPPORT_EMAIL,
        )
    except Exception:
        logger.exception('Could not queue a verification link for %s', username)
        return False
    # The mailer logs the delivery itself, or its failure.
    logger.info('Queued a verification link for %s', username)
    return True


@router.post("/verify-email", dependencies=[Depends(verify_email_rate_limit)])
def verify_email(request: VerifyEmailRequest) -> dict:
    """Public on purpose: the link is opened wherever the mail is read, which is
    often not the browser or phone the account is signed in on.

    One answer for every refusal - bad signature, expired, wrong kind of token,
    no such account, or an account that has since changed its address - since
    the person holding the link can do the same thing about all of them: ask
    for a new one.
    """
    claims = jwt_utils.read_email_verification_token(request.token)
    if claims is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=VERIFY_INVALID_MESSAGE)
    username, email = claims

    if not database.mark_email_verified(username, email, datetime.now(timezone.utc)):
        logger.info('Refused a verification link for %s: no such account, or its address changed', username)
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=VERIFY_INVALID_MESSAGE)

    logger.info('Email address verified for user %s', username)
    return {"message": VERIFIED_MESSAGE, "username": username}


@router.post("/resend-verification", dependencies=[Depends(resend_verification_rate_limit)])
def resend_verification(user: CurrentUser) -> dict:
    """Mail a fresh link to the signed-in account's address.

    The already-verified check reads the row get_current_user just loaded, not
    the session token. A missing SMTP setup is checked before the per-account
    limit so a site without mail does not also spend the user's quota; a spent
    daily budget answers the same 503, since for the user it is the same thing:
    mail is not going out today.
    """
    if user.email_verified_at is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_VERIFIED_MESSAGE)
    if not mailer.is_configured():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=mail_unavailable_message())

    wait = resend_verification_per_account_limit.retry_after(user.username.lower())
    if wait:
        logger.warning('Verification resend limit reached for user %s', user.username)
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many requests, please try again later.",
            headers={"Retry-After": str(wait)},
        )

    if not send_verification_email(user.username, user.email):
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=mail_unavailable_message())
    return {"message": f"We sent a new verification link to {user.email}."}
