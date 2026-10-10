#!/usr/bin/env python3
"""Online snapshot of the PatchingSite database, run nightly on the host.

    patchingsite-backup.py <database> <target-dir> [--keep N]

Installed as /usr/local/bin/patchingsite-backup and run by
patchingsite-backup.timer; deploy/backup/pull-backups.sh then copies the
snapshots off the host.

Uses SQLite's backup API rather than cp: the database runs in WAL mode, so a
plain copy of users.db can miss writes still sitting in users.db-wal, and the
service keeps running while this does. Standard library only, so it depends on
neither the server's virtualenv nor the checkout's current state.
"""

import argparse
import sqlite3
import sys
import time
from pathlib import Path


def snapshot(database: Path, target_dir: Path) -> Path:
    stamp = time.strftime("%Y-%m-%d-%H%M%S", time.gmtime())
    final = target_dir / f"users-{stamp}.db"
    # Written under a name the pruning glob and the pull script both ignore, and
    # renamed only once it verifies, so neither ever sees a half-written file.
    partial = target_dir / f".users-{stamp}.db.partial"

    source = sqlite3.connect(database)
    copy = sqlite3.connect(partial)
    try:
        source.backup(copy)
        result = copy.execute("PRAGMA integrity_check").fetchone()[0]
        # A snapshot that stays in WAL mode would need its -wal/-shm files to be
        # complete; DELETE mode makes the .db file self-contained for copying.
        copy.execute("PRAGMA journal_mode=DELETE")
    finally:
        copy.close()
        source.close()

    if result != "ok":
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"integrity_check on the snapshot failed: {result}")
    partial.rename(final)
    return final


def prune(target_dir: Path, keep: int) -> list[Path]:
    # The timestamped names sort chronologically, so the oldest come first.
    snapshots = sorted(target_dir.glob("users-*.db"))
    doomed = snapshots[:-keep] if keep > 0 else []
    for path in doomed:
        path.unlink()
    return doomed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("database", type=Path)
    parser.add_argument("target_dir", type=Path)
    parser.add_argument("--keep", type=int, default=14, help="snapshots to keep (default 14)")
    args = parser.parse_args()

    if not args.database.is_file():
        print(f"no database at {args.database}", file=sys.stderr)
        return 1
    args.target_dir.mkdir(mode=0o700, parents=True, exist_ok=True)

    path = snapshot(args.database, args.target_dir)
    print(f"snapshot {path} ({path.stat().st_size} bytes)")
    for old in prune(args.target_dir, args.keep):
        print(f"pruned {old}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
