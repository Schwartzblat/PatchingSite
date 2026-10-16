import base64
import hashlib
import os
import re
import tempfile
from pathlib import Path

from fastapi import Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from fastapi.routing import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from stitch.bundle import BundleError, verify_bundle

import config
from utils import patch_store, signing_keys
from utils.jwt_utils import UserInDB, get_current_user
from utils.ratelimit import signing_key_ip_rate_limit, signing_key_rate_limit
from utils.internal_auth import require_internal

router = APIRouter()

SHA256 = r"^[0-9a-f]{64}$"
PATCHER_VERSION = r"^[0-9A-Za-z][0-9A-Za-z.+-]{0,63}$"
MAX_BUNDLE_BYTES = 16 * 1024 * 1024


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


def _too_large() -> HTTPException:
    return HTTPException(status_code=413, detail="Bundle too large")


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _finalize_bundle(tmp: Path, public_key: bytes, digest: str, size: int) -> dict:
    """Verify the uploaded temp file, move it into place and record it (blocking work)."""
    try:
        meta = verify_bundle(tmp, public_key)
    except BundleError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    package = meta.get("package")
    patcher_version = meta.get("patcher_version")
    if package not in config.APPS_BY_PACKAGE:
        raise HTTPException(status_code=404, detail="Unknown package")
    if not isinstance(patcher_version, str) or not re.fullmatch(PATCHER_VERSION, patcher_version):
        raise HTTPException(status_code=422, detail="Invalid patcher_version")
    for key in ("min_store_version_code", "min_sdk"):
        if not _is_int(meta.get(key)):
            raise HTTPException(status_code=400, detail=f"Bundle metadata {key} must be an integer")

    dest = patch_store.bundle_path(package, patcher_version, digest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(tmp, 0o644)
    os.replace(tmp, dest)
    revision = patch_store.upsert_bundle(meta, digest, size)

    # The row names the live file; drop older content for this version (best effort).
    for other in dest.parent.glob(f"{patcher_version}-*.zip"):
        if other != dest and re.fullmatch(r"[0-9a-f]{16}", other.name[len(patcher_version) + 1:-4]):
            try:
                other.unlink()
            except OSError:
                pass
    return {"package": package, "patcher_version": patcher_version, "revision": revision}


@router.post("/internal/bundles", dependencies=[Depends(require_internal("BUNDLE_UPLOAD_SECRET"))])
async def post_bundle(request: Request):
    try:
        public_key = Path(config.BUNDLE_PUBLIC_KEY_PATH).read_bytes()
    except OSError:
        raise HTTPException(status_code=503, detail="Not configured")

    declared = request.headers.get("content-length")
    if declared and declared.isdigit() and int(declared) > MAX_BUNDLE_BYTES:
        raise _too_large()

    bundles_dir = Path(config.BUNDLES_DIR)
    bundles_dir.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=bundles_dir, prefix=".upload-", suffix=".tmp")
    tmp = Path(tmp_name)
    try:
        digest = hashlib.sha256()
        size = 0
        with os.fdopen(fd, "wb") as out:
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_BUNDLE_BYTES:
                    raise _too_large()
                digest.update(chunk)
                await run_in_threadpool(out.write, chunk)
        return await run_in_threadpool(_finalize_bundle, tmp, public_key, digest.hexdigest(), size)
    finally:
        tmp.unlink(missing_ok=True)


@router.get("/signing-key", dependencies=[Depends(signing_key_ip_rate_limit)])
async def get_signing_key(user: UserInDB = Depends(get_current_user)):
    wait = signing_key_rate_limit.retry_after(f"user:{user.id}")
    if wait:
        raise HTTPException(status_code=429, detail="Too many requests, please try again later.",
                            headers={"Retry-After": str(wait)})
    try:
        key = await run_in_threadpool(signing_keys.get_or_create, user.id)
    except signing_keys.NotConfigured:
        raise HTTPException(status_code=503, detail="Not configured")
    return JSONResponse(
        {"pkcs12": base64.b64encode(key.pkcs12).decode(), "password": key.password,
         "alias": signing_keys.ALIAS, "cert_sha256": key.cert_sha256, "cert_sha1": key.cert_sha1},
        headers={"Cache-Control": "no-store"})
