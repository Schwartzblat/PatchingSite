from datetime import datetime
from functools import lru_cache
from pathlib import Path

from androguard.core.apk import APK
from fastapi import HTTPException
from fastapi.routing import APIRouter
from loguru import logger as androguard_logger
from starlette.responses import FileResponse

import config
from utils.logging_config import logger

router = APIRouter()

# androguard logs every manifest chunk at DEBUG through loguru, which this server
# does not otherwise use. disable() rather than androguard's own set_log(): Stitch
# already calls set_log at import, and a second call raises, because it removes
# loguru handler 0 by id.
androguard_logger.disable("androguard")

STORE_APK_FILENAME = "patched-store.apk"
APK_MEDIA_TYPE = "application/vnd.android.package-archive"


def _store_apk():
    """The store APK, or None while none has been deployed.

    Read from config on every call rather than once at import, so dropping a new
    build into place needs no restart.
    """
    path = config.STORE_APK_PATH
    return path if path.is_file() else None


@lru_cache(maxsize=4)
def _apk_version(path: Path, mtime_ns: int, size: int) -> tuple[int | None, str | None]:
    """(versionCode, versionName) read from the APK's own manifest.

    The store app compares versionCode against its installed build to decide
    whether to offer an update, so the number comes from the artifact itself: a
    sidecar file would go stale the first time someone copied in a new build
    without it. Cached on (mtime, size) so each launch-time check does not re-open
    a 28 MB zip, while replacing the file still takes effect without a restart.

    Anything unreadable is (None, None). The app offers no update it cannot
    order, which is the safe failure: a guessed number could offer a downgrade.
    """
    try:
        apk = APK(str(path))
        code = apk.get_androidversion_code()
        return (int(code) if code is not None else None), apk.get_androidversion_name()
    except Exception as error:  # androguard raises a zoo of types on a bad zip
        logger.warning("Could not read the store APK's version from %s: %s", path, error)
        return None, None


@router.get("/store")
def store_info() -> dict:
    """Public, like the catalogue: the store app is useless without an account anyway."""
    apk = _store_apk()
    if apk is None:
        return {"available": False}
    stat = apk.stat()
    version_code, version_name = _apk_version(apk, stat.st_mtime_ns, stat.st_size)
    return {
        "available": True,
        "filename": STORE_APK_FILENAME,
        "size": stat.st_size,
        "updated_at": datetime.fromtimestamp(stat.st_mtime).strftime('%Y-%m-%d %H:%M'),
        "version_code": version_code,
        "version_name": version_name,
    }


@router.get("/store/download")
def download_store():
    apk = _store_apk()
    if apk is None:
        raise HTTPException(status_code=404, detail="The store app is not available yet")
    # The APK media type lets Android's browser offer "Open" straight into the
    # package installer instead of leaving a generic file in Downloads.
    return FileResponse(str(apk), media_type=APK_MEDIA_TYPE, filename=STORE_APK_FILENAME)
