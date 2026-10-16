from fastapi import Depends, HTTPException
from fastapi.routing import APIRouter
from pydantic import BaseModel, ConfigDict, Field

import config
from utils import patch_store
from utils.internal_auth import require_internal

router = APIRouter()

SHA256 = r"^[0-9a-f]{64}$"
PATCHER_VERSION = r"^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$"


class PatchRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    package: str
    version_name: str
    version_code: int = Field(ge=1)
    apk_sha256: str = Field(pattern=SHA256)
    dex_sha256: str = Field(pattern=SHA256)
    patcher_version: str = Field(pattern=PATCHER_VERSION)
    artifacts: dict[str, str]
    missing_keys: list[str]
    features_off: list[str]
    supported: bool
    source: str


@router.post("/internal/records", dependencies=[Depends(require_internal("CRAWLER_SECRET"))])
def post_record(record: PatchRecord):
    if record.package not in config.APPS_BY_PACKAGE:
        raise HTTPException(status_code=404, detail="Unknown package")
    return {"revision": patch_store.upsert_record(record.model_dump())}
