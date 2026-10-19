"""The Works-with list: apps tested with the generic ad remover, edited by hand.

The file is operator-edited, so a typo must never take the endpoint down:
`current()` logs the problem once per file change and serves an empty list,
and `manage.py doctor` reports the same error.
"""
import json
import re
from datetime import date

import config
from utils.logging_config import logger

PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
KEYS = frozenset({"package", "name", "note", "tested_version", "tested_on"})
NOTE_MAX = 200

_cache: tuple = (None, [])  # (key, entries) for the last file state read


class WorksWithError(Exception):
    pass


def parse(text: str) -> list[dict]:
    try:
        data = json.loads(text)
    except ValueError as e:
        raise WorksWithError(f"not valid JSON: {e}") from None
    if not isinstance(data, list):
        raise WorksWithError("the file must hold a JSON list")
    seen = set()
    for index, entry in enumerate(data):
        where = f"entry {index}"
        if not isinstance(entry, dict):
            raise WorksWithError(f"{where}: not an object")
        if set(entry) != KEYS:
            raise WorksWithError(f"{where}: keys must be exactly {', '.join(sorted(KEYS))}")
        for key, value in entry.items():
            if not isinstance(value, str) or not value:
                raise WorksWithError(f"{where}: {key} must be a non-empty string")
        if not PACKAGE_RE.fullmatch(entry["package"]):
            raise WorksWithError(f"{where}: bad package name")
        if entry["package"] in seen:
            raise WorksWithError(f"{where}: duplicate package")
        seen.add(entry["package"])
        try:
            parsed = date.fromisoformat(entry["tested_on"])
        except ValueError:
            parsed = None
        if parsed is None or parsed.isoformat() != entry["tested_on"]:
            raise WorksWithError(f"{where}: tested_on must be YYYY-MM-DD")
        if len(entry["note"]) > NOTE_MAX:
            raise WorksWithError(f"{where}: note is longer than {NOTE_MAX} characters")
    return data


def current() -> list[dict]:
    """The list from config.GENERIC_WORKS_WITH_PATH; [] when missing or invalid."""
    global _cache
    path = config.GENERIC_WORKS_WITH_PATH
    try:
        stat = path.stat()
        key = (str(path), stat.st_mtime_ns, stat.st_size)
    except OSError:
        key = (str(path), None, None)
    if _cache[0] == key:
        return _cache[1]
    try:
        entries = parse(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, WorksWithError) as e:
        logger.error("works-with list ignored (%s): %s", path.name, e)
        entries = []
    _cache = (key, entries)
    return entries
