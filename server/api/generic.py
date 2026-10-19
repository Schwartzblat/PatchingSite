"""The generic ad remover's public status, and customers' reports on it.

A report is stored without the account or the IP (see database.generic_reports),
and nothing here logs who sent it or which app it was about.
"""
from datetime import datetime, timezone
from typing import Annotated, Optional

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StringConstraints, field_validator
from starlette.exceptions import HTTPException

import config
import database
from utils import works_with
from utils.jwt_utils import UserInDB, get_current_user
from utils.logging_config import logger
from utils.ratelimit import generic_report_ip_rate_limit, generic_report_rate_limit
from utils.stripe_utils import RequiredSubscription

router = APIRouter(prefix="/generic")

THANKS_MESSAGE = "Thanks! Your report was sent."

Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]


@router.get("")
def generic_status():
    # works_with is served whatever the switch says; the store shows the card only when enabled.
    return {"enabled": config.GENERIC_PATCHING, "works_with": works_with.current()}


class GenericReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    package: Annotated[str, Field(max_length=255)]
    app_name: Name
    version_name: Name
    version_code: Annotated[StrictInt, Field(ge=0)]
    worked: StrictBool
    note: Optional[Annotated[str, StringConstraints(strip_whitespace=True, max_length=500)]] = None
    store_version_code: Annotated[StrictInt, Field(ge=0)]
    android_sdk: Annotated[StrictInt, Field(ge=1, le=1000)]

    @field_validator("package")
    @classmethod
    def _package_shape(cls, value: str) -> str:
        # fullmatch: PACKAGE_RE ends in `$`, which would let a trailing newline through.
        if not works_with.PACKAGE_RE.fullmatch(value):
            raise ValueError("not an Android package name")
        return value

    @field_validator("note")
    @classmethod
    def _blank_note_is_none(cls, value):
        return value or None


def _reporter(request: Request, subscription: RequiredSubscription,
              user: UserInDB = Depends(get_current_user)) -> None:
    """Signed in and subscribed, then the per-IP and per-account limits.

    The account key lives only in the limiter's memory and is never logged.
    """
    generic_report_ip_rate_limit(request)
    generic_report_rate_limit.check(f"user:{user.id}", logged_as="an account")


@router.post("/reports", status_code=201, dependencies=[Depends(_reporter)])
def create_report(report: GenericReport) -> dict:
    if not config.GENERIC_PATCHING:
        raise HTTPException(status_code=404, detail="Generic patching is off")
    report_id = database.add_generic_report(
        package=report.package, app_name=report.app_name, version_name=report.version_name,
        version_code=report.version_code, worked=report.worked, note=report.note,
        store_version_code=report.store_version_code, android_sdk=report.android_sdk,
        created_on=datetime.now(timezone.utc).date(),
    )
    logger.info('Generic report stored (#%s)', report_id)
    return {"message": THANKS_MESSAGE}
