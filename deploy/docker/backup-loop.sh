#!/bin/sh
# Nightly database snapshot without a cron daemon.
#
#   backup-loop         sleep until 03:30 UTC, snapshot, repeat forever
#   backup-loop --now   snapshot once and exit with its status
#
# A failed snapshot is logged and retried the next night; it never ends the loop.

set -eu
umask 077

snapshot() {
    patchingsite-backup /opt/server_data/users.db /backups --keep "${BACKUP_KEEP:-14}"
}

if [ "${1:-}" = "--now" ]; then
    snapshot
    exit $?
fi

sleep_pid=
# sleep runs in the background and we `wait`, so SIGTERM is handled at once
# instead of after the sleep finishes.
trap 'if [ -n "$sleep_pid" ]; then kill "$sleep_pid" 2>/dev/null || true; fi; exit 0' TERM INT

while :; do
    now=$(date -u +%s)
    next=$(date -u -d "today 03:30" +%s)
    if [ "$next" -le "$now" ]; then
        next=$(date -u -d "tomorrow 03:30" +%s)
    fi
    echo "backup-loop: next snapshot at $(date -u -d "@$next" '+%Y-%m-%d %H:%M UTC')"
    sleep $((next - now)) &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=

    if snapshot; then
        echo "backup-loop: snapshot ok"
    else
        echo "backup-loop: snapshot FAILED (status $?), retrying tomorrow"
    fi
done
