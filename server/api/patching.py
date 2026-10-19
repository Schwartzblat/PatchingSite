import base64
import hashlib
import os
import re
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import jwt
from fastapi import Depends, Header, HTTPException, Path as PathParam, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from fastapi.routing import APIRouter
from pydantic import BaseModel, ConfigDict, Field

import config
from utils import patch_store, signing_keys
from utils.jwt_utils import RequiredToken, UserInDB, create_app_jwt, get_current_user
from utils.ratelimit import (RateLimiter, app_token_ip_rate_limit, app_token_rate_limit,
                             patch_ip_rate_limit, patch_rate_limit, signing_key_ip_rate_limit,
                             signing_key_rate_limit)
from utils.stripe_utils import RequiredSubscription
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
    from stitch.bundle import BundleError, verify_bundle
    try:
        meta = verify_bundle(tmp, public_key)
    except BundleError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    package = meta.get("package")
    patcher_version = meta.get("patcher_version")
    if package != config.GENERIC_PACKAGE and package not in config.APPS_BY_PACKAGE:
        raise HTTPException(status_code=404, detail="Unknown package")
    if package == config.GENERIC_PACKAGE:
        # A generic bundle carries the ad catalog and no per-app features.
        files = meta.get("files")
        if not isinstance(files, dict) or "catalog.json" not in files:
            raise HTTPException(status_code=400, detail="A generic bundle needs catalog.json")
        if "features.json" in files:
            raise HTTPException(status_code=400, detail="A generic bundle must not contain features.json")
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
        import stitch.bundle  # noqa: F401  (needs Stitch >= 1.5.0; imported lazily so the site starts without it)
    except ImportError:
        raise HTTPException(status_code=503, detail="Not configured")
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


def _account_reader(limiter: RateLimiter):
    """Dependency: signed in, subscribed and within `limiter`'s per-account budget."""
    def reader(subscription: RequiredSubscription, user: UserInDB = Depends(get_current_user)) -> UserInDB:
        wait = limiter.retry_after(f"user:{user.id}")
        if wait:
            raise HTTPException(status_code=429, detail="Too many requests, please try again later.",
                                headers={"Retry-After": str(wait)})
        return user
    return reader


patch_reader = _account_reader(patch_rate_limit)


def _known_package(package: str) -> str:
    if package not in config.APPS_BY_PACKAGE:
        raise HTTPException(status_code=404, detail="Unknown package")
    return package


def _generic_enabled() -> None:
    if not config.GENERIC_PATCHING:
        raise HTTPException(status_code=404, detail="Generic patching is off")


def _bundle_package(package: str) -> str:
    """Like _known_package, but `generic` is also allowed (when its switch is on)."""
    if package == config.GENERIC_PACKAGE:
        _generic_enabled()
        return package
    return _known_package(package)


def _store_version_code(raw: str | None) -> int:
    try:
        return max(int(raw), 0) if raw is not None else 0
    except ValueError:
        return 0


read_deps = [Depends(patch_ip_rate_limit), Depends(patch_reader)]


@router.get("/patch/generic/latest", dependencies=read_deps)
async def get_generic_latest(x_store_version_code: str | None = Header(default=None)):
    _generic_enabled()
    package = config.GENERIC_PACKAGE
    row = await run_in_threadpool(patch_store.latest_bundle, package,
                                  _store_version_code(x_store_version_code))
    newest = await run_in_threadpool(patch_store.newest_bundle, package)
    if newest is not None and (row is None or newest["seq"] > row["seq"]):
        # The newest bundle needs a newer store app: tell this one to update itself.
        return JSONResponse({"detail": "Store update required",
                             "min_store_version_code": newest["min_store_version_code"]},
                            status_code=426)
    if row is None:
        raise HTTPException(status_code=404, detail="No generic bundle yet")
    try:
        bundle_id = await run_in_threadpool(patch_store.bundle_id, row)
    except (OSError, KeyError, ValueError):  # ValueError covers zipfile.BadZipFile
        raise HTTPException(status_code=404, detail="No generic bundle yet")
    return {
        "package": package,
        "patcher_version": row["patcher_version"],
        "bundle_id": bundle_id,
        "sha256": row["sha256"],
        "size": row["size"],
        "min_sdk": row["min_sdk"],
        "min_store_version_code": row["min_store_version_code"],
        "revision": row["seq"],
    }


@router.get("/patch/{package}/latest", dependencies=read_deps)
async def get_latest(package: str, x_store_version_code: str | None = Header(default=None)):
    _known_package(package)
    source = config.OFFICIAL_SOURCES.get(package)
    record = await run_in_threadpool(
        patch_store.latest_record, package, _store_version_code(x_store_version_code), source)
    newest = await run_in_threadpool(patch_store.newest_supported, package, source)
    if newest is not None and (record is None or newest[0] > record["version_code"]):
        # A newer build exists, but only for a newer store app: tell it to update itself.
        return JSONResponse({"detail": "Store update required", "min_store_version_code": newest[1]},
                            status_code=426)
    if record is None:
        raise HTTPException(status_code=404, detail="No supported version yet")
    return {
        "package": package,
        "version_code": record["version_code"],
        "version_name": record["version_name"],
        "apk_url": config.OFFICIAL_APK_URLS.get(package),
        "apk_sha256": record["apk_sha256"],
        "dex_sha256": record["dex_sha256"],
        "patcher_version": record["patcher_version"],
        "revision": record["revision"],
        "features_off": record["features_off"],
    }


@router.get("/patch/{package}/artifacts", dependencies=read_deps)
async def get_artifacts(package: str,
                        patcher_version: str = Query(pattern=PATCHER_VERSION),
                        apk_sha256: str | None = Query(default=None, pattern=SHA256),
                        dex_sha256: str | None = Query(default=None, pattern=SHA256)):
    _known_package(package)
    if (apk_sha256 is None) == (dex_sha256 is None):
        raise HTTPException(status_code=422, detail="Pass exactly one of apk_sha256 and dex_sha256")
    record = await run_in_threadpool(
        lambda: patch_store.find_record(package, patcher_version,
                                        apk_sha256=apk_sha256, dex_sha256=dex_sha256))
    if record is None:
        raise HTTPException(status_code=404, detail="Unknown build")
    return record


@router.get("/patch/{package}/bundle/{patcher_version}", dependencies=read_deps)
async def get_bundle(package: str, patcher_version: str = PathParam(pattern=PATCHER_VERSION)):
    _bundle_package(package)
    row = await run_in_threadpool(patch_store.get_bundle, package, patcher_version)
    path = patch_store.bundle_path(package, patcher_version, row["sha256"]) if row else None
    if path is None or not path.is_file():
        raise HTTPException(status_code=404, detail="Unknown bundle")
    return FileResponse(path, media_type="application/zip", headers={"ETag": f'"{row["sha256"]}"'})


class AppTokenRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    package: str


@router.post("/app-token", dependencies=[Depends(app_token_ip_rate_limit),
                                         Depends(_account_reader(app_token_rate_limit))])
def post_app_token(body: AppTokenRequest, token: RequiredToken, subscription: RequiredSubscription):
    """The same paywall token POST /downloads bakes into a server-patched APK."""
    _bundle_package(body.package)
    app_token = create_app_jwt(token, body.package, customer_id=subscription.client_id)
    exp = jwt.decode(app_token, options={"verify_signature": False})["exp"]
    return {"token": app_token,
            "expires_at": datetime.fromtimestamp(exp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
