# The PatchingSite API server. Also runs the nightly DB snapshot when started
# with `--entrypoint backup-loop`. Linux/amd64 only: stitch's fastsigner is a
# static x86-64 binary.
FROM python:3.14-slim

ARG APP_COMMIT=unknown
LABEL org.opencontainers.image.revision=$APP_COMMIT

# Wheels only: the build must fail rather than compile anything.
COPY server/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --only-binary=:all: -r /tmp/requirements.txt \
    && rm /tmp/requirements.txt

COPY server/ /app/server/
COPY deploy/docker/app-entrypoint.sh /usr/local/bin/app-entrypoint
COPY deploy/docker/backup-loop.sh /usr/local/bin/backup-loop
COPY deploy/backup/patchingsite-backup.py /usr/local/bin/patchingsite-backup
RUN chmod 755 /usr/local/bin/app-entrypoint /usr/local/bin/backup-loop /usr/local/bin/patchingsite-backup

WORKDIR /app/server
ENTRYPOINT ["/usr/local/bin/app-entrypoint"]
