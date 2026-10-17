#!/usr/bin/env bash
#
# One-time hardening of the host for a public launch. Every step checks before it
# changes anything, so re-running it is safe and only finishes what is left; it is
# also how a change to deploy/nginx/ ships.
#
#   deploy/launch-setup.sh --dry-run    show what each step would do
#   deploy/launch-setup.sh              do it
#
# Runs from the dev machine, like deploy.sh, against the host named in
# deploy/deploy.env. It never touches the SSH daemon's configuration. Steps:
#   1. make the keystore and the data directory private to the SSH user
#   2. add UMask=0077 to the service via a drop-in
#   3. pin APP_JWT_SECRET_KEY in .env to the value it is derived as today
#   4. add the SMTP settings to .env, from a local password file
#   5. install the nginx site, snippets and http settings (rolls back if nginx -t fails)
#   6. install the nightly database snapshot timer and take a first snapshot
#   7. add www.<domain> to the certificate, once its DNS record exists
#   8. restart the service if .env or the unit changed

set -euo pipefail

step() { printf '\n==> %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
die() { printf 'launch-setup: %s\n' "$*" >&2; exit 1; }
remote() { ssh -o BatchMode=yes "$HOST" "$@"; }

dry_run=0
case ${1:-} in
    -n | --dry-run) dry_run=1 ;;
    '') ;;
    *) sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
# In a dry run every remote command that would change something is printed
# instead; the checks that decide whether a step is needed still run.
change() {
    if ((dry_run)); then note "would run: $*"; else remote "$@"; fi
}
# Reports a change that change() just made; silent in a dry run, where it didn't.
did() { ((dry_run)) || note "$@"; }

cd "$(git rev-parse --show-toplevel)"

# Read only after the arguments, so the usage text works on a machine with no
# deploy.env. The same file and variables deploy.sh uses.
source deploy/deploy-env.sh
load_deploy_env
missing=()
for var in DEPLOY_HOST DEPLOY_REPO DEPLOY_SITE_URL; do
    [[ -n ${!var:-} ]] || missing+=("$var")
done
((${#missing[@]} == 0)) || die "set ${missing[*]} in deploy/deploy.env (see deploy/deploy.env.example)"
HOST=$DEPLOY_HOST
REMOTE_REPO=$DEPLOY_REPO
SITE_URL=${DEPLOY_SITE_URL%/}
SERVICE=${DEPLOY_SERVICE:-patchingsite}
# The certificate is named after the site's host name, and www.<it> redirects to it.
DOMAIN=${SITE_URL#*://}
DOMAIN=${DOMAIN%%[/:]*}
# The account that sends mail (and owns the app password), and the address users
# see for support and where bug reports go. Separate settings so support can move
# without touching the credentials; support defaults to the sending account.
SMTP_USER=${SMTP_USERNAME:-}
SUPPORT=${SUPPORT_EMAIL:-$SMTP_USER}
SMTP_PASSWORD_FILE=${SMTP_PASSWORD_FILE:-$HOME/.config/androidmod/smtp-app-password}

restart_needed=0

step "1. Private keystore and data directory"
# Both paths as the server itself resolves them from the host's .env, rather than
# assumed: KEYSTORE_PATH (relative to server/, as the signer is handed it) and
# SERVER_DATA_DIR. config prints nothing on import, so this is the only line out.
IFS=$'\t' read -r data_dir keystore < <(remote "cd '$REMOTE_REPO/server' && .venv/bin/python -" <<'PY'
import os, pathlib, config
keystore = os.environ.get("KEYSTORE_PATH")
print(f"{config.DATA_DIR}\t{pathlib.Path(keystore).resolve() if keystore else ''}")
PY
) || true
[[ -n ${data_dir:-} ]] || die "could not read the server's settings in $HOST:$REMOTE_REPO/server"
check="[ \$(stat -c %a '$data_dir') = 700 ] &&
    [ -z \"\$(find '$data_dir' -type f -perm /077 -print -quit)\" ]"
fix="chmod 700 '$data_dir' &&
    find '$data_dir' -type d -exec chmod 700 {} + &&
    find '$data_dir' -type f -exec chmod 600 {} +"
if [[ -n ${keystore:-} ]]; then
    check="[ \$(stat -c %a '$keystore') = 600 ] && $check"
    fix="chmod 600 '$keystore' && $fix"
else
    note "no KEYSTORE_PATH in the host's .env, so there is no keystore to protect"
fi
if remote "$check"; then
    note "already private"
else
    change "$fix"
    did "${keystore:+keystore 600, }$data_dir 700, its files 600"
fi

step "2. UMask for the service"
DROPIN=/etc/systemd/system/$SERVICE.service.d/umask.conf
if remote "test -f '$DROPIN'"; then
    note "already installed"
else
    # A drop-in, so it covers whatever unit the host already runs: one installed
    # before deploy/patchingsite.service set UMask=0077 makes every file the
    # service creates (the database, app.log, cached APKs) world-readable.
    change "sudo mkdir -p '$(dirname "$DROPIN")' &&
        printf '[Service]\nUMask=0077\n' | sudo tee '$DROPIN' >/dev/null && sudo systemctl daemon-reload"
    did "installed $DROPIN"
    restart_needed=1
fi

step "3. Pin APP_JWT_SECRET_KEY"
if remote "grep -q '^APP_JWT_SECRET_KEY=' '$REMOTE_REPO/.env'"; then
    note "already pinned"
elif ((dry_run)); then
    note "would append APP_JWT_SECRET_KEY (today's derived value) to $REMOTE_REPO/.env"
else
    # The value config.py derives today, written out, so nothing that holds an
    # app token notices. Printed nowhere; write_text keeps the file's 600 mode.
    remote "cd '$REMOTE_REPO/server' && .venv/bin/python -" <<'PY'
import pathlib, config
env = pathlib.Path("../.env")
text = env.read_text()
text += "" if text.endswith("\n") else "\n"
env.write_text(text + (
    "# Pinned at launch to the value config.py derived from JWT_SECRET_KEY, so\n"
    "# rotating that key no longer invalidates every APK already handed out.\n"
    f"APP_JWT_SECRET_KEY={config.APP_JWT_SECRET_KEY}\n"))
PY
    note "appended to $REMOTE_REPO/.env"
    restart_needed=1
fi

step "4. SMTP settings"
if remote "grep -q '^SMTP_PASSWORD=' '$REMOTE_REPO/.env'"; then
    note "already configured"
elif [[ -z $SMTP_USER ]]; then
    note "skipped: set SMTP_USERNAME (and SUPPORT_EMAIL, if different) in deploy/deploy.env and re-run"
elif [[ ! -s $SMTP_PASSWORD_FILE ]]; then
    note "skipped: put the account's app password in $SMTP_PASSWORD_FILE and re-run"
elif ((dry_run)); then
    note "would append SMTP_USERNAME, SMTP_PASSWORD and SUPPORT_EMAIL to $REMOTE_REPO/.env"
else
    # Over stdin, never on a command line where ps or shell history could see it.
    # Google shows app passwords in groups of four; the spaces are not part of it.
    tr -d ' \n' <"$SMTP_PASSWORD_FILE" | remote "
        set -e
        password=\$(cat)
        printf 'SMTP_USERNAME=%s\nSMTP_PASSWORD=%s\nSUPPORT_EMAIL=%s\n' \
            '$SMTP_USER' \"\$password\" '$SUPPORT' >> '$REMOTE_REPO/.env'
    "
    note "appended to $REMOTE_REPO/.env"
    restart_needed=1
fi

step "5. nginx"
stage=/tmp/androidmod-nginx.$$
declare -A NGINX_FILES=(
    [deploy/nginx/androidmod.site.conf]=/etc/nginx/sites-available/androidmod.site
    [deploy/nginx/androidmod-headers.conf]=/etc/nginx/snippets/androidmod-headers.conf
    [deploy/nginx/androidmod-proxy.conf]=/etc/nginx/snippets/androidmod-proxy.conf
    [deploy/nginx/androidmod-v2.conf]=/etc/nginx/snippets/androidmod-v2.conf
    [deploy/nginx/androidmod-v2-proxy.conf]=/etc/nginx/snippets/androidmod-v2-proxy.conf
    [deploy/nginx/androidmod-http.conf]=/etc/nginx/conf.d/androidmod-http.conf
)
stale=()
for src in "${!NGINX_FILES[@]}"; do
    remote "sudo cmp -s - '${NGINX_FILES[$src]}'" <"$src" || stale+=("$src")
done
if ((${#stale[@]} == 0)); then
    note "already up to date"
elif ((dry_run)); then
    for src in "${stale[@]}"; do note "would install $src -> ${NGINX_FILES[$src]}"; done
else
    remote "mkdir -p '$stage'"
    install_cmds=''
    for src in "${!NGINX_FILES[@]}"; do
        scp -q -o BatchMode=yes "$src" "$HOST:$stage/$(basename "$src")"
        install_cmds+="install_one '$stage/$(basename "$src")' '${NGINX_FILES[$src]}'; "
    done
    # Every file is backed up before it is replaced and all of them are put
    # back if the combined config does not pass nginx -t, so a bad edit can
    # never take the running site down: reload only happens on a valid config.
    remote "
        set -e
        backups=() created=()
        install_one() {
            if sudo test -e \"\$2\"; then
                sudo cp -p \"\$2\" \"\$2.pre-launch\"; backups+=(\"\$2\")
            else
                created+=(\"\$2\")
            fi
            sudo install -m 644 -o root -g root \"\$1\" \"\$2\"
        }
        $install_cmds
        sudo ln -sf /etc/nginx/sites-available/androidmod.site /etc/nginx/sites-enabled/androidmod.site
        if ! sudo nginx -t 2>&1; then
            for f in \"\${backups[@]}\"; do sudo mv \"\$f.pre-launch\" \"\$f\"; done
            # A file that did not exist before (conf.d, snippets) would otherwise
            # be picked up, still broken, by the next unrelated reload.
            for f in \"\${created[@]}\"; do sudo rm -f \"\$f\"; done
            echo 'nginx -t failed; previous config restored, nothing reloaded' >&2
            exit 1
        fi
        sudo systemctl reload nginx
        rm -rf '$stage'
    "
    note "installed ${#stale[@]} file(s) and reloaded nginx (previous versions kept as *.pre-launch)"
fi

step "6. Nightly database snapshots"
if remote "systemctl is-enabled --quiet patchingsite-backup.timer 2>/dev/null &&
        sudo cmp -s /usr/local/bin/patchingsite-backup -" <deploy/backup/patchingsite-backup.py; then
    note "already installed"
elif ((dry_run)); then
    note "would install /usr/local/bin/patchingsite-backup and patchingsite-backup.{service,timer}, then take a snapshot"
else
    for f in patchingsite-backup.py patchingsite-backup.service patchingsite-backup.timer; do
        scp -q -o BatchMode=yes "deploy/backup/$f" "$HOST:/tmp/$f"
    done
    remote "
        set -e
        sudo install -m 755 -o root -g root /tmp/patchingsite-backup.py /usr/local/bin/patchingsite-backup
        sudo install -m 644 -o root -g root /tmp/patchingsite-backup.service /tmp/patchingsite-backup.timer /etc/systemd/system/
        rm -f /tmp/patchingsite-backup.py /tmp/patchingsite-backup.service /tmp/patchingsite-backup.timer
        sudo systemctl daemon-reload
        sudo systemctl enable --now patchingsite-backup.timer
        sudo systemctl start patchingsite-backup.service
        sudo journalctl -u patchingsite-backup.service -n 3 --no-pager -o cat
    "
fi

step "7. www.$DOMAIN certificate"
# Only address lines: on a resolver timeout dig prints its error to stdout too.
a_record() { dig +short +tries=3 +time=5 A "$1" | grep -E '^[0-9.]+$' | tail -1 || true; }
apex_ip=$(a_record "$DOMAIN")
www_ip=$(a_record "www.$DOMAIN")
if remote "sudo openssl x509 -in '/etc/letsencrypt/live/$DOMAIN/cert.pem' -noout -ext subjectAltName" |
    grep -qF "DNS:www.$DOMAIN"; then
    note "the certificate already covers www"
elif [[ -z $www_ip ]]; then
    note "skipped: www.$DOMAIN has no A record yet (add one pointing at $apex_ip, then re-run)"
elif [[ $www_ip != "$apex_ip" ]]; then
    note "skipped: www.$DOMAIN points at $www_ip, not $apex_ip"
else
    # certonly: the nginx plugin answers the challenge but must not rewrite the
    # site file, which this repo owns. certonly also drops the installer from the
    # renewal config, so the deploy hook (saved into it) keeps renewals reloading
    # nginx.
    change "sudo certbot certonly --nginx --non-interactive --cert-name '$DOMAIN' \
        -d '$DOMAIN' -d 'www.$DOMAIN' \
        --deploy-hook 'systemctl reload nginx' && sudo systemctl reload nginx"
    did "certificate now covers www.$DOMAIN"
fi

step "8. Service"
if ((restart_needed)); then
    note "restarting $SERVICE (waits for in-flight signings to finish)"
    change "sudo systemctl restart '$SERVICE'"
    if ((!dry_run)); then
        for _ in $(seq 1 60); do curl -fsS -m 5 "$SITE_URL/api/health" >/dev/null 2>&1 && break; sleep 1; done
        curl -fsS -m 5 "$SITE_URL/api/health" >/dev/null || die "$SITE_URL/api/health did not come back"
        note "healthy"
    fi
else
    note "nothing to apply"
fi

step "Done$( ((dry_run)) && echo ': dry run, nothing was changed')"
