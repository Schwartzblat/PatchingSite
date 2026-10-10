import re
from pathlib import Path
from typing import Annotated

from fastapi import Depends
from starlette.exceptions import HTTPException

import config

# Version directories are taken straight from a URL segment / request body, so
# anything that is not a plain build name is rejected before it touches the FS.
VERSION_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$')

_VERSION_TOKEN_RE = re.compile(r'\d+|[A-Za-z]+')

# Rank of each kind of version token at one position. Letters (beta, rc) sort
# below the end of a version, so 1.0-beta < 1.0, and the end sorts below any
# further number, so 1.0 < 1.0.1.
_LETTERS, _END, _NUMBER = 0, 1, 2


def version_sort_key(version: str) -> tuple:
    """Order version names by their numbers: 5.9 < 5.10, 99.0 < 100.0.

    Sorting the raw strings put 5.10 before 5.9 and 100.0 before 99.0, so the
    store listed an old build as the newest. Runs of digits compare as integers,
    runs of letters case-insensitively, and separators are ignored. The raw
    string breaks ties, so the order is total and stable for names that differ
    only in punctuation.
    """
    parts = [
        (_NUMBER, int(token), '') if token.isdigit() else (_LETTERS, 0, token.lower())
        for token in _VERSION_TOKEN_RE.findall(version)
    ]
    parts.append((_END, 0, ''))
    return tuple(parts), version


PACKAGE_NOT_FOUND = HTTPException(status_code=404, detail="Unknown package")
VERSION_NOT_FOUND = HTTPException(status_code=404, detail="Version not found")


def is_valid_package(package_name: str) -> bool:
    return package_name in config.APPS_BY_PACKAGE


def validate_package_name(package_name: str) -> str:
    """FastAPI dependency *and* plain validator for body-supplied packages."""
    if not is_valid_package(package_name):
        raise PACKAGE_NOT_FOUND
    return package_name


ValidPackage = Annotated[str, Depends(validate_package_name)]


def validate_version(version: str) -> str:
    if not isinstance(version, str) or not VERSION_RE.match(version):
        raise VERSION_NOT_FOUND
    return version


def _inside_root(path: Path, root: Path) -> bool:
    return path == root or path.is_relative_to(root)


def package_dir(package_name: str) -> Path:
    """Resolved <PATCHED_APPS_PATH>/<package>, 404 unless it really is a directory in there."""
    validate_package_name(package_name)
    root = Path(config.PATCHED_APPS_PATH).resolve()
    path = (root / package_name).resolve()
    if not _inside_root(path, root) or not path.is_dir():
        raise PACKAGE_NOT_FOUND
    return path


def version_dir(package_name: str, version: str) -> Path:
    validate_version(version)
    root = Path(config.PATCHED_APPS_PATH).resolve()
    path = (package_dir(package_name) / version).resolve()
    if not _inside_root(path, root) or not path.is_dir():
        raise VERSION_NOT_FOUND
    return path
