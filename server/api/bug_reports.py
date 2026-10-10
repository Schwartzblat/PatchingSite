"""Bug reports from the website and the store app.

Anyone may report, signed in or not: the people most likely to hit a bug are
the ones who cannot sign in. A report is stored first and mailed second, so an
unconfigured or failing mail server loses nothing - `manage.py bug-reports`
lists what arrived, including anything that never reached the inbox.
"""
from datetime import datetime, timezone
from typing import Annotated, Literal, Optional

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, EmailStr, Field, StringConstraints, field_validator

import config
import database
from utils import mailer
from utils.jwt_utils import TokenData, get_optional_token_data
from utils.logging_config import logger
from utils.ratelimit import GLOBAL_KEY, bug_report_mail_budget, bug_report_rate_limit, client_ip

router = APIRouter()

THANKS_MESSAGE = "Thanks! Your report was sent."

ContextKey = Annotated[str, StringConstraints(max_length=64)]
ContextValue = Annotated[str, StringConstraints(max_length=500)]


class BugReportRequest(BaseModel):
    message: Annotated[str, StringConstraints(strip_whitespace=True, min_length=10, max_length=5000)]
    # Validated as an address because it becomes the mail's Reply-To header.
    # EmailStr also enforces the 254-character limit.
    email: Optional[EmailStr] = None
    source: Literal["web", "android"]
    # Free-form diagnostics (url, app_version, os, device, user_agent...), kept
    # to flat short strings so a report cannot smuggle in a large blob.
    context: Optional[dict[ContextKey, ContextValue]] = Field(default=None, max_length=10)

    @field_validator("email", mode="before")
    @classmethod
    def _blank_email_is_none(cls, value):
        # Forms post an untouched optional field as "", which is "no email",
        # not an invalid one.
        if isinstance(value, str) and not value.strip():
            return None
        return value


OptionalToken = Annotated[Optional[TokenData], Depends(get_optional_token_data)]


def _mail_body(report_id: int, report: BugReportRequest, username, account_email, ip, received) -> str:
    reporter = username or "anonymous"
    if account_email:
        reporter += f" <{account_email}>"
    lines = [
        f"Bug report #{report_id} ({report.source})",
        f"From: {reporter}",
        f"Reply email given: {report.email or '-'}",
        f"IP: {ip}",
        f"Received: {received}",
        "",
        report.message,
    ]
    if report.context:
        lines += ["", "Context:"] + [f"  {key}: {value}" for key, value in report.context.items()]
    return "\n".join(lines) + "\n"


def _record_delivery(report_id: int):
    def _done(future):
        try:
            if future.result():
                database.mark_bug_report_mailed(report_id, datetime.now(timezone.utc))
        except Exception:
            logger.exception('Could not record the delivery of bug report #%s', report_id)
    return _done


@router.post("/bug-reports", status_code=201, dependencies=[Depends(bug_report_rate_limit)])
def create_bug_report(report: BugReportRequest, request: Request, token: OptionalToken) -> dict:
    now = datetime.now(timezone.utc)
    ip = client_ip(request)

    username = account_email = None
    if token is not None:
        user = database.get_user(token.username)
        if user is not None:
            username, account_email = user.username, user.email

    report_id = database.add_bug_report(
        source=report.source, message=report.message, email=report.email, username=username,
        client_ip=ip, context=report.context or {}, created_at=now,
    )
    logger.info('Bug report #%s from %s (%s)', report_id, username or 'anonymous', report.source)

    # Stored already, so nothing below may turn this into an error for the user.
    if bug_report_mail_budget.retry_after(GLOBAL_KEY):
        logger.warning('Not mailing bug report #%s: the daily bug report mail budget is spent', report_id)
        return {"id": report_id, "message": THANKS_MESSAGE}
    try:
        future = mailer.send(
            config.SUPPORT_EMAIL,
            f"Bug report #{report_id} from {username or 'a visitor'} ({report.source})",
            _mail_body(report_id, report, username, account_email, ip, now.strftime(database.TIMESTAMP_FORMAT)),
            reply_to=report.email or account_email,
        )
        future.add_done_callback(_record_delivery(report_id))
    except Exception:
        logger.exception('Could not queue the mail for bug report #%s', report_id)

    return {"id": report_id, "message": THANKS_MESSAGE}
