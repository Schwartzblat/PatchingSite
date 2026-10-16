import os
from datetime import datetime
from typing import List

from fastapi import HTTPException, Query
from fastapi.routing import APIRouter
from pydantic import BaseModel
from starlette.responses import FileResponse

import config
from utils import downloads
from utils.apps_utils import find_patched_apk
from utils.jwt_utils import RequiredToken, create_app_jwt
from utils.logging_config import logger
from utils.stripe_utils import RequiredSubscription
from utils.utils import ValidPackage, package_dir, validate_package_name, version_dir, version_sort_key

router = APIRouter()


class DownloadRequest(BaseModel):
    package: str
    version: str


@router.get("/apps")
def list_apps() -> List[dict]:
    """The catalogue. Public, like the website's app grid.

    Icons are deliberately absent: the Android client bundles its own, so this
    endpoint stays free of static-file concerns.
    """
    return [
        {
            "key": app.key,
            "name": app.name,
            "package": app.package,
            "features": list(app.features),
            "on_device": app.package in config.ON_DEVICE_PACKAGES,
        }
        for app in config.APPS
    ]


@router.get("/list/{package_name}")
def list_versions(package_name: ValidPackage) -> List[dict]:
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
    if package_name in config.ON_DEVICE_PACKAGES:
        return []
    package_path = package_dir(package_name)
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
    package_name = validate_package_name(request.package)
    if package_name in config.ON_DEVICE_PACKAGES:
        raise HTTPException(
            status_code=410,
            detail="This app is now patched on your phone. Open the androidmod store app to install it.",
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
