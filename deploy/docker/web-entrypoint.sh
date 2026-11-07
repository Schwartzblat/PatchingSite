#!/usr/bin/env bash
#
# Container entrypoint for the website: renders the nginx config for $DOMAIN,
# makes sure a certificate exists, and runs nginx.
#
#   TLS_MODE=letsencrypt  (default) certbot --webroot, at most once per 15
#                         minutes even across restarts, until it succeeds. Until
#                         the first certificate arrives nginx serves only the
#                         ACME path, over HTTP (bootstrap.conf.template). Renews
#                         every 12 h.
#   TLS_MODE=selfsigned   a 10-year self-signed certificate, made on first start.
#                         Never contacts Let's Encrypt.
#   TLS_MODE=external     no certificate here: the host's own nginx keeps ports
#                         80 and 443, terminates TLS and forwards the site to
#                         127.0.0.1:$HTTP_PORT (deploy/nginx/androidmod-docker.conf),
#                         where this nginx serves it over plain HTTP, taking the
#                         visitor's address from X-Real-IP. HTTPS_PORT is unused.
#
# Certificates live in /etc/letsencrypt, a volume, so a restart or a rebuild
# reuses them instead of issuing again. A copy of the old host's /etc/letsencrypt
# works as is: its certificate is named after the domain too.
#
# nginx runs as a child. If it dies, the container exits non-zero; on SIGTERM it
# gets `nginx -s quit` and the container exits.

set -euo pipefail

log() { echo "web-entrypoint: $*"; }
die() {
    echo "web-entrypoint: $*" >&2
    exit 1
}

[ -n "${DOMAIN:-}" ] || die "DOMAIN is not set (the bare host name, e.g. androidmod.site)"
TLS_MODE=${TLS_MODE:-letsencrypt}
LETSENCRYPT_STAGING=${LETSENCRYPT_STAGING:-0}
WWW_REDIRECT=${WWW_REDIRECT:-1}
HTTP_PORT=${HTTP_PORT:-80}
HTTPS_PORT=${HTTPS_PORT:-443}
APP_PORT=${APP_PORT:-8000}

# All of these land verbatim in nginx config or on certbot's command line.
[[ $DOMAIN =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "DOMAIN '$DOMAIN' is not a bare host name"
for var in HTTP_PORT HTTPS_PORT APP_PORT; do
    [[ ${!var} =~ ^[0-9]+$ ]] || die "$var '${!var}' is not a port number"
done
case $WWW_REDIRECT in 0 | 1) ;; *) die "WWW_REDIRECT must be 0 or 1, not '$WWW_REDIRECT'" ;; esac
case $LETSENCRYPT_STAGING in 0 | 1) ;; *) die "LETSENCRYPT_STAGING must be 0 or 1, not '$LETSENCRYPT_STAGING'" ;; esac

case $TLS_MODE in
    letsencrypt)
        [ -n "${LETSENCRYPT_EMAIL:-}" ] || die "TLS_MODE=letsencrypt needs LETSENCRYPT_EMAIL"
        # certbot --cert-name $DOMAIN puts it here.
        CERT_DIR=/etc/letsencrypt/live/$DOMAIN
        ;;
    selfsigned) CERT_DIR=/etc/letsencrypt/selfsigned/$DOMAIN ;;
    external) CERT_DIR= ;;
    *) die "TLS_MODE must be letsencrypt, selfsigned or external, not '$TLS_MODE'" ;;
esac

# The default HTTPS port stays out of redirect URLs. Behind the host's nginx
# (external) visitors always arrive on 443.
if [ "$HTTPS_PORT" = 443 ] || [ "$TLS_MODE" = external ]; then HTTPS_SUFFIX=; else HTTPS_SUFFIX=":$HTTPS_PORT"; fi

# The names the certificate must cover.
names=("$DOMAIN")
if [ "$WWW_REDIRECT" = 1 ]; then names+=("www.$DOMAIN"); fi

export DOMAIN HTTP_PORT HTTPS_PORT APP_PORT CERT_DIR HTTPS_SUFFIX

TEMPLATES=/etc/nginx/androidmod-templates
SITE_CONF=/etc/nginx/conf.d/androidmod.conf
# Only these are substituted, so nginx's own $uri, $host, $request_uri... survive.
# shellcheck disable=SC2016 # envsubst wants the names, not their values
VARS='${DOMAIN} ${HTTP_PORT} ${HTTPS_PORT} ${APP_PORT} ${CERT_DIR} ${HTTPS_SUFFIX}'

render() { envsubst "$VARS" <"$TEMPLATES/$1"; }

check_config() {
    nginx -t -q || die "nginx -t rejected the rendered config in $SITE_CONF"
}

use_full_config() {
    render proxy.conf.template >/etc/nginx/snippets/androidmod-proxy.conf
    {
        # www first, as on the old host: nginx's default server for a port is
        # the first one listed, so a request for any other name gets the
        # redirect to the bare domain rather than the site.
        if [ "$WWW_REDIRECT" = 1 ]; then render www.conf.template; fi
        render site.conf.template
    } >"$SITE_CONF"
    check_config
}

use_bootstrap_config() {
    render bootstrap.conf.template >"$SITE_CONF"
    check_config
}

use_external_config() {
    render proxy.conf.template >/etc/nginx/snippets/androidmod-proxy.conf
    {
        # www first, for the same reason as in use_full_config.
        if [ "$WWW_REDIRECT" = 1 ]; then render external-www.conf.template; fi
        render external.conf.template
    } >"$SITE_CONF"
    check_config
}

make_selfsigned() {
    local san=
    for name in "${names[@]}"; do san+="${san:+,}DNS:$name"; done
    log "making a self-signed certificate for ${names[*]} in $CERT_DIR"
    mkdir -p "$CERT_DIR"
    # Written aside and moved into place, the key first: a restart halfway
    # through finds no fullchain.pem and simply starts over.
    (
        umask 077
        openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -quiet -subj "/CN=$DOMAIN" \
            -addext "subjectAltName=$san" \
            -keyout "$CERT_DIR/privkey.pem.new" -out "$CERT_DIR/fullchain.pem.new"
    )
    mv "$CERT_DIR/privkey.pem.new" "$CERT_DIR/privkey.pem"
    mv "$CERT_DIR/fullchain.pem.new" "$CERT_DIR/fullchain.pem"
}

nginx_pid=
child=

stop() {
    trap '' TERM INT QUIT
    log "stopping"
    if [ -n "$child" ]; then kill "$child" 2>/dev/null || true; fi
    nginx -s quit 2>/dev/null || kill -QUIT "$nginx_pid" 2>/dev/null || true
    wait "$nginx_pid" 2>/dev/null || true
    exit 0
}
# TERM is what web.Dockerfile's STOPSIGNAL sends; the nginx base image's QUIT
# and a terminal's INT are handled the same way.
trap stop TERM INT QUIT

start_nginx() {
    nginx -g 'daemon off;' &
    nginx_pid=$!
}

# Runs "$@" in the background and waits for it, or for nginx if that ends first.
# Waiting on a background child lets the TERM trap run at once instead of after
# a 15-minute sleep or a slow certbot, and a dead nginx ends the container even
# mid-certbot. Returns the command's status.
beside_nginx() {
    local status=0 ended=
    "$@" &
    child=$!
    wait -n -p ended "$nginx_pid" "$child" || status=$?
    if [ "$ended" = "$nginx_pid" ]; then
        kill "$child" 2>/dev/null || true
        die "nginx exited (status $status)"
    fi
    child=
    return "$status"
}

if [ "$TLS_MODE" = selfsigned ] || [ "$TLS_MODE" = external ]; then
    if [ "$TLS_MODE" = external ]; then
        use_external_config
        start_nginx
        log "serving ${names[*]} on 127.0.0.1:$HTTP_PORT, behind the host's nginx"
    else
        if [ ! -f "$CERT_DIR/fullchain.pem" ]; then make_selfsigned; fi
        use_full_config
        start_nginx
        log "serving ${names[*]} with the self-signed certificate in $CERT_DIR"
    fi
    status=0
    wait "$nginx_pid" || status=$?
    die "nginx exited (status $status)"
fi

# Proof that this nginx, not whatever else might hold the port, serves the ACME
# path: a token written for the purpose, read back over loopback. certbot must
# not run before that. A validation Let's Encrypt cannot complete still counts
# against its 5 failures per hostname per hour, and an nginx that cannot bind
# exits after a few seconds, so each restart would cost another one.
wait_until_serving() {
    local token i
    local probe=/var/www/certbot/.well-known/acme-challenge/web-entrypoint-probe
    local url=http://127.0.0.1:$HTTP_PORT/.well-known/acme-challenge/web-entrypoint-probe
    token=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
    mkdir -p "${probe%/*}"
    printf %s "$token" >"$probe"
    for ((i = 0; i < 30; i++)); do
        if [ "$(wget -q -Y off -T 2 -O - "$url" 2>/dev/null)" = "$token" ]; then
            rm -f "$probe"
            return 0
        fi
        beside_nginx sleep 1
    done
    die "nginx is not serving the ACME path on port $HTTP_PORT after 30 s; not running certbot"
}

# When certonly may next run, kept in the volume so the 15-minute spacing holds
# across restarts and recreated containers too. Claimed before each attempt
# rather than after a failure: an attempt cut short (nginx died, the container
# was stopped) may still have cost a validation.
SLOT_FILE=/etc/letsencrypt/web-entrypoint/$DOMAIN.next-attempt
RETRY_SECONDS=900
attempted=0
next_attempt=0

utc() { date -u -d "@$1" '+%H:%M:%S UTC'; }

take_attempt_slot() {
    local next=0 now
    if [ -f "$SLOT_FILE" ]; then next=$(<"$SLOT_FILE"); fi
    [[ $next =~ ^[0-9]+$ ]] || next=0
    now=$(date +%s)
    # Further off than one interval means the clock went back; wait no longer
    # than the interval itself.
    if ((next > now + RETRY_SECONDS)); then next=$((now + RETRY_SECONDS)); fi
    if ((next > now)); then
        # After a failure in this run the wait was logged already.
        if [ "$attempted" = 0 ]; then
            log "the last certbot attempt for $DOMAIN was under 15 minutes ago; waiting until $(utc "$next")"
        fi
        beside_nginx sleep $((next - now))
    fi
    next_attempt=$(($(date +%s) + RETRY_SECONDS))
    if ! { mkdir -p "${SLOT_FILE%/*}" && echo "$next_attempt" >"$SLOT_FILE"; }; then
        die "cannot write $SLOT_FILE; not running certbot without it"
    fi
    attempted=1
}

if [ -f "$CERT_DIR/fullchain.pem" ]; then
    use_full_config
else
    log "no certificate for $DOMAIN yet: serving only the ACME path, over HTTP"
    use_bootstrap_config
fi
start_nginx
wait_until_serving

# Run even when the certificate exists: --keep-until-expiring makes that a no-op
# until it is due for renewal, and --expand adds www.$DOMAIN to a certificate
# that does not name it yet.
certonly=(certonly --webroot -w /var/www/certbot --cert-name "$DOMAIN"
    --email "$LETSENCRYPT_EMAIL" --agree-tos --non-interactive
    --keep-until-expiring --expand)
for name in "${names[@]}"; do certonly+=(-d "$name"); done
if [ "$LETSENCRYPT_STAGING" = 1 ]; then certonly+=(--staging); fi

# certonly picks an account by itself only when there is one. With two it must
# ask, and under --non-interactive it fails, on every attempt. Two exist once
# the old host's /etc/letsencrypt is copied over an account certbot registered
# here (a first start in letsencrypt mode before DNS moved, say). The lineage's
# renewal conf names its own account, the one renew uses, so certonly gets that
# one too: only a plain id, and only one stored for the ACME server in use, since
# --account with any other fails outright. Otherwise certbot chooses as before.
# Read before every attempt, so a copy made while this runs is picked up.
certonly_account=()
pick_account() {
    local conf=/etc/letsencrypt/renewal/$DOMAIN.conf line id=
    local server=acme-v02.api.letsencrypt.org/directory
    if [ "$LETSENCRYPT_STAGING" = 1 ]; then server=acme-staging-v02.api.letsencrypt.org/directory; fi
    certonly_account=()
    if [ ! -f "$conf" ] || [ ! -r "$conf" ]; then return 0; fi
    while IFS= read -r line || [ -n "$line" ]; do
        if [[ $line =~ ^[[:space:]]*account[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$ ]]; then
            id=${BASH_REMATCH[1]}
            break
        fi
    done <"$conf"
    if [[ $id =~ ^[A-Za-z0-9]+$ ]] && [ -d "/etc/letsencrypt/accounts/$server/$id" ]; then
        certonly_account=(--account "$id")
    fi
}

issued=0
last_renew=$(date +%s)
while :; do
    if [ "$issued" = 0 ]; then
        # Waits, up to 15 minutes, if the last attempt was more recent.
        take_attempt_slot
        pick_account
        if beside_nginx certbot "${certonly[@]}" "${certonly_account[@]}"; then
            rm -f "$SLOT_FILE"
            issued=1
            use_full_config
            nginx -s reload || die "nginx -s reload failed"
            log "certificate for ${names[*]} in place, serving HTTPS"
        else
            # Never faster, never giving up: Let's Encrypt allows 5 failed
            # validations per hostname per hour, and a container that exited
            # here would restart into a loop that locks the domain out (DNS not
            # moved yet, say). Meanwhile nginx keeps serving the ACME path.
            log "certbot could not get a certificate for ${names[*]}; retrying in 15 minutes (at $(utc "$next_attempt"))"
        fi
    fi

    # Every 12 hours, whenever a certificate exists. That includes one certonly
    # keeps failing to update (copied without www.$DOMAIN while www has no DNS
    # record, say): the site's own name stays renewed meanwhile. The explicit
    # --webroot overrides `authenticator = nginx` in a renewal conf copied from
    # the old host. certbot renews only what is due, then reloads.
    if [ -f "$CERT_DIR/fullchain.pem" ] && (($(date +%s) - last_renew >= 43200)); then
        beside_nginx certbot renew --webroot -w /var/www/certbot \
            --deploy-hook 'nginx -t -q && nginx -s reload' ||
            log "certbot renew failed; trying again in 12 hours"
        last_renew=$(date +%s)
    fi

    if [ "$issued" = 1 ]; then beside_nginx sleep 43200; fi
done
