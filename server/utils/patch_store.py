"""Storage for on-device patch records, bundles and per-user signing keys.

Every function opens its own connection. `seq` is one counter shared by records
and bundles; it is allocated inside the same BEGIN IMMEDIATE as the write, so
two writers can never hand out the same value.
"""
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import config
import database

_RECORD_COLUMNS = ("package", "version_code", "version_name", "apk_sha256", "dex_sha256",
                   "patcher_version", "source")
_BODY_EXCLUDED = {"revision"}


def _now() -> str:
    return datetime.now(timezone.utc).strftime(database.TIMESTAMP_FORMAT)


def next_seq(conn: sqlite3.Connection) -> int:
    row = conn.execute(
        "SELECT MAX(COALESCE((SELECT MAX(seq) FROM patch_records), 0), "
        "COALESCE((SELECT MAX(seq) FROM patch_bundles), 0))").fetchone()
    return row[0] + 1


def _serialize(record: dict) -> str:
    body = {k: v for k, v in record.items() if k not in _BODY_EXCLUDED}
    return json.dumps(body, sort_keys=True, separators=(",", ":"))


def upsert_record(record: dict) -> int:
    body = _serialize(record)
    conn = database._connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            existing = conn.execute(
                "SELECT id, body, seq FROM patch_records "
                "WHERE package = ? AND dex_sha256 = ? AND patcher_version = ?",
                (record["package"], record["dex_sha256"], record["patcher_version"])).fetchone()
            if existing is not None and existing["body"] == body:
                seq = existing["seq"]
            else:
                seq = next_seq(conn)
                values = tuple(record[c] for c in _RECORD_COLUMNS)
                if existing is None:
                    conn.execute(
                        "INSERT INTO patch_records (package, version_code, version_name, apk_sha256, "
                        "dex_sha256, patcher_version, source, body, seq, updated_at) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        values + (body, seq, _now()))
                else:
                    conn.execute(
                        "UPDATE patch_records SET package = ?, version_code = ?, version_name = ?, "
                        "apk_sha256 = ?, dex_sha256 = ?, patcher_version = ?, source = ?, "
                        "body = ?, seq = ?, updated_at = ? WHERE id = ?",
                        values + (body, seq, _now(), existing["id"]))
            conn.execute("COMMIT")
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    finally:
        conn.close()
    return seq


def _bundle_seq(conn: sqlite3.Connection, package: str, patcher_version: str) -> int:
    row = conn.execute("SELECT seq FROM patch_bundles WHERE package = ? AND patcher_version = ?",
                       (package, patcher_version)).fetchone()
    return row["seq"] if row else 0


def _with_revision(conn: sqlite3.Connection, row: sqlite3.Row) -> dict:
    record = json.loads(row["body"])
    record["revision"] = max(row["seq"], _bundle_seq(conn, row["package"], row["patcher_version"]))
    return record


def find_record(package: str, patcher_version: str, *, apk_sha256: Optional[str] = None,
                dex_sha256: Optional[str] = None) -> Optional[dict]:
    if (apk_sha256 is None) == (dex_sha256 is None):
        raise ValueError("pass exactly one of apk_sha256 and dex_sha256")
    column, value = ("apk_sha256", apk_sha256) if apk_sha256 is not None else ("dex_sha256", dex_sha256)
    conn = database._connect()
    try:
        row = conn.execute(
            f"SELECT * FROM patch_records WHERE package = ? AND patcher_version = ? AND {column} = ? "
            "ORDER BY seq DESC LIMIT 1", (package, patcher_version, value)).fetchone()
        return _with_revision(conn, row) if row else None
    finally:
        conn.close()


def upsert_bundle(meta: dict, sha256: str, size: int) -> int:
    conn = database._connect()
    try:
        conn.execute("BEGIN IMMEDIATE")
        try:
            seq = next_seq(conn)
            conn.execute(
                "INSERT INTO patch_bundles (package, patcher_version, min_store_version_code, min_sdk, "
                "sha256, size, seq, uploaded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (package, patcher_version) DO UPDATE SET "
                "min_store_version_code = excluded.min_store_version_code, min_sdk = excluded.min_sdk, "
                "sha256 = excluded.sha256, size = excluded.size, seq = excluded.seq, "
                "uploaded_at = excluded.uploaded_at",
                (meta["package"], meta["patcher_version"], meta["min_store_version_code"],
                 meta["min_sdk"], sha256, size, seq, _now()))
            conn.execute("COMMIT")
        finally:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
    finally:
        conn.close()
    return seq


def bundle_path(package: str, patcher_version: str, sha256: str) -> Path:
    """Where the bundle with this content hash lives. The row's sha256 names the live file."""
    return Path(config.BUNDLES_DIR) / package / f"{patcher_version}-{sha256[:16]}.zip"


def get_bundle(package: str, patcher_version: str) -> Optional[dict]:
    conn = database._connect()
    try:
        row = conn.execute("SELECT * FROM patch_bundles WHERE package = ? AND patcher_version = ?",
                           (package, patcher_version)).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def latest_record(package: str, store_version_code: int, source: Optional[str]) -> Optional[dict]:
    conn = database._connect()
    try:
        rows = conn.execute(
            "SELECT r.*, b.seq AS bundle_seq FROM patch_records r "
            "JOIN patch_bundles b ON b.package = r.package AND b.patcher_version = r.patcher_version "
            "WHERE r.package = ? AND b.min_store_version_code <= ? "
            "AND (? IS NULL OR r.source = ?) "
            "AND json_extract(r.body, '$.supported') = 1 "
            "ORDER BY r.version_code DESC, b.seq DESC LIMIT 1",
            (package, store_version_code, source, source)).fetchall()
        return _with_revision(conn, rows[0]) if rows else None
    finally:
        conn.close()


def get_signing_key(user_id: int) -> Optional[sqlite3.Row]:
    conn = database._connect()
    try:
        return conn.execute("SELECT * FROM signing_keys WHERE user_id = ?", (user_id,)).fetchone()
    finally:
        conn.close()


def insert_signing_key_if_absent(user_id: int, nonce: bytes, ciphertext: bytes,
                                 cert_sha256: str, cert_sha1: str) -> None:
    conn = database._connect()
    try:
        conn.execute(
            "INSERT OR IGNORE INTO signing_keys (user_id, nonce, ciphertext, cert_sha256, cert_sha1, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)", (user_id, nonce, ciphertext, cert_sha256, cert_sha1, _now()))
    finally:
        conn.close()
