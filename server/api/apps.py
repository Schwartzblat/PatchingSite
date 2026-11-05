import os
from datetime import datetime
from typing import List

from fastapi import HTTPException, Query
from fastapi.routing import APIRouter
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import FileResponse

import config
from utils import downloads
from utils.apps_utils import find_patched_apk
from utils.friends import require_visible_package, visible_apps
from utils.jwt_utils import OptionalToken, RequiredToken, create_app_jwt
from utils.logging_config import logger
from utils.stripe_utils import RequiredSubscription
from utils.utils import PACKAGE_NOT_FOUND, package_dir, version_dir, version_sort_key

router = APIRouter()


class DownloadRequest(BaseModel):
    package: str
    version: str


@router.get("/apps")
def list_apps(token: OptionalToken) -> List[dict]:
    """The catalogue. Public, like the website's app grid, plus the friends-only
    apps for an account flagged as a friend.

    Icons of the public apps are bundled by the clients. The friends-only icons
    are served here (/apps/<package>/icon), to friends only, so their names and
    pictures are not in the clients at all.
    """
    entries = []
    for app in visible_apps(token.username if token else None):
        entry = {
            "key": app.key,
            "name": app.name,
            "package": app.package,
            "features": list(app.features),
            "on_device": app.package in config.ON_DEVICE_PACKAGES,
        }
        if app.package in config.FRIEND_APPS_BY_PACKAGE:
            entry.update(
                friends_only=True,
                name_he=app.name_he,
                features_he=list(app.features_he),
                icon=f"/api/apps/{app.package}/icon",
            )
        entries.append(entry)
    return entries


@router.get("/apps/{package_name}/icon")
def get_icon(package_name: str, token: OptionalToken):
    """A friends-only app's icon. Anyone else gets the unknown-package 404."""
    require_visible_package(package_name, token.username if token else None)
    app = config.FRIEND_APPS_BY_PACKAGE.get(package_name)
    if app is None:
        raise PACKAGE_NOT_FOUND
    return FileResponse(
        str(config.FRIEND_ICONS_DIR / f"{app.key}.webp"),
        media_type="image/webp",
        headers={"Cache-Control": "private, max-age=86400"},
    )


@router.get("/list/{package_name}")
def list_versions(package_name: str, token: OptionalToken) -> List[dict]:
    """
    returns sorted [{
        id: str,  # stable, used as a react key
        version: str,
        type: str,  # apk or xapk
        filename: str,
        size: int,  # in bytes
        updated_at: str
    }]
    """
    require_visible_package(package_name, token.username if token else None)
    if package_name in config.ON_DEVICE_PACKAGES:
        return []
    try:
        package_path = package_dir(package_name)
    except StarletteHTTPException:
        # A friends-only app nobody has built yet is not an unknown package.
        if package_name in config.FRIEND_APPS_BY_PACKAGE:
            return []
        raise
    versions = []
    for version_directory in sorted(os.listdir(package_path)):
        directory = package_path / version_directory
        if not directory.is_dir():
            continue
        apk_file = find_patched_apk(directory)
        if apk_file is None:
            continue
        versions.append({
            'id': f'{package_name}:{version_directory}',
            'version': version_directory,
            'type': apk_file.suffix.lstrip('.'),
            'filename': apk_file.name,
            'size': apk_file.stat().st_size,
            'updated_at': datetime.fromtimestamp(apk_file.stat().st_mtime).strftime('%Y-%m-%d %H:%M'),
        })
    # Newest first, by number: a plain string sort put 5.10 below 5.9.
    return sorted(versions, key=lambda item: version_sort_key(item['version']), reverse=True)


def _resolve_patched_apk(package_name: str, version: str):
    apk_file = find_patched_apk(version_dir(package_name, version))
    if apk_file is None:
        raise HTTPException(status_code=404, detail="No patched build for this version")
    return apk_file


@router.post("/downloads", status_code=202)
def create_download(request: DownloadRequest, token: RequiredToken, subscription: RequiredSubscription) -> dict:
    """RequiredSubscription already rejects anything that is not an active subscription.

    Its client_id is the customer on the account's row, which is what the APK
    must carry: the session token's claim is empty for an account linked after
    it signed in.
    """
    package_name = require_visible_package(request.package, token.username)
    if package_name in config.ON_DEVICE_PACKAGES:
        raise HTTPException(
            status_code=410,
            detail="This app is now patched on your phone. Open the Patched Store app to install it.",
        )
    apk_file = _resolve_patched_apk(package_name, request.version)
    logger.debug('Download requested: %s %s by %s', package_name, request.version, token.username)
    try:
        job = downloads.enqueue(
            username=token.username,
            package=package_name,
            version=request.version,
            source_apk=apk_file,
            app_token=create_app_jwt(token, package_name, customer_id=subscription.client_id),
        )
    except downloads.TooManyJobsForUser:
        raise HTTPException(
            status_code=429,
            detail=f"You already have {config.DOWNLOAD_MAX_JOBS_PER_USER} downloads being prepared. "
                   "Wait for one to finish, then try again.",
        )
    except downloads.QueueFull:
        raise HTTPException(status_code=429, detail="Too many downloads are being prepared, try again shortly")
    except downloads.WorkersStopped:
        raise HTTPException(status_code=503, detail="Server is restarting, try again shortly")
    return {'job_id': job.job_id, 'state': downloads.accepted_state(job), 'cached': job.cached}


@router.get("/downloads/{job_id}")
async def get_download(job_id: str, token: RequiredToken) -> dict:
    """Async on purpose: polling must never occupy a threadpool worker."""
    job = downloads.get_job(job_id, token.username)
    if job is None:
        raise HTTPException(status_code=404, detail="Download job not found")
    return downloads.job_status(job)


@router.get("/downloads/{job_id}/file")
async def download_file(job_id: str, token: RequiredToken, sig: str = Query(...)):
    job = downloads.get_job(job_id, token.username)
    if job is None or not downloads.verify_signature(job_id, token.username, sig):
        raise HTTPException(status_code=404, detail="Download job not found")
    if job.state != downloads.READY or job.path is None or not job.path.is_file():
        raise HTTPException(status_code=404, detail="Download is no longer available, request it again")
    return FileResponse(str(job.path), media_type='application/octet-stream', filename=job.filename)
