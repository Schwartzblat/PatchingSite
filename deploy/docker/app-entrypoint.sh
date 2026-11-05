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

python manage.py doctor

# Loopback only: nginx (host network) is the sole client, and
# --forwarded-allow-ips keeps a spoofed X-Forwarded-For from anyone else.
exec uvicorn main:app --host 127.0.0.1 --port "${APP_PORT:-8000}" --workers 1 \
    --proxy-headers --forwarded-allow-ips=127.0.0.1
