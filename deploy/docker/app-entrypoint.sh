#!/bin/sh
# Container entrypoint for the API server.
#
# Runs `manage.py doctor` first so a bad config (wrong data-dir owner, test
# Stripe keys, missing keystore...) fails the container loudly, with the report
# in `docker logs`, instead of running half-working. Arguments replace the
# server: `docker compose run app python manage.py check`.

set -eu
umask 077
cd /app/server

if [ "$#" -gt 0 ]; then
    exec "$@"
fi

# Both are host files that must belong to APP_UID. doctor only checks that the
# keystore exists, so an unreadable one would give a healthy site on which every
# paid download fails; an unreadable .env, a bare PermissionError. Checked here,
# not above the branch, so `docker compose run app sh` can still look into it.
must_read() {
    if [ ! -r "$1" ]; then
        echo "app-entrypoint: uid $(id -u) cannot read $1 ($2 on the host); it must be owned by APP_UID, mode 600" >&2
        exit 1
    fi
}
must_read /app/.env "the checkout's .env"
must_read /run/keystore.jks HOST_KEYSTORE_FILE

python manage.py doctor

# Loopback only: nginx (host network) is the sole client, and
# --forwarded-allow-ips keeps a spoofed X-Forwarded-For from anyone else.
exec uvicorn main:app --host 127.0.0.1 --port "${APP_PORT:-8000}" --workers 1 \
    --proxy-headers --forwarded-allow-ips=127.0.0.1
