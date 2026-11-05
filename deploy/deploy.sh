#!/usr/bin/env bash
#
# Deploys the commit at HEAD to the live host: the server, the website and the
# Android store app, each only if its code changed since it was last deployed.
#
#   deploy/deploy.sh                    deploy whatever changed
#   deploy/deploy.sh --dry-run          print the plan, change nothing
#   deploy/deploy.sh --force client     also deploy a component with no code change
#                                       (server | client | android | all; repeatable)
#   deploy/deploy.sh --skip android     leave a changed component undeployed for now
#                                       (server | client | android; repeatable)
#
# Runs from a developer machine, never on the host: the host has no JDK or Android
# SDK, and the release signing secrets live in this machine's env files. The host
# is named in deploy/deploy.env; copy deploy/deploy.env.example to start.
# DEPLOY_RUNTIME there says how the host runs the site: systemd (the default) or
# docker (docker-compose.yml, from the host's checkout).

set -euo pipefail

COMPONENTS=(server client android)

# Sets `paths` to the git pathspec of what counts as a change to component $1.
# Tests and Markdown never do: a README edit must not restart the service (queued
# downloads die with the process) and must not publish an APK, since every new
# build offers all users an update.
component_paths() {
    case $1 in
        server) paths=(server ':!server/tests' ':!*.md') ;;
        client) paths=(client ':!*.md') ;;
        android) paths=(android ':!android/app/src/test' ':!*.md') ;;
    esac
    # docker: also what the images and the compose file take from outside
    # server/ and client/, since a change there only ships when they are rebuilt.
    if [[ $RUNTIME == docker ]]; then
        case $1 in
            server) paths+=(Dockerfile deploy/docker/app-entrypoint.sh deploy/docker/backup-loop.sh
                deploy/backup/patchingsite-backup.py docker-compose.yml .dockerignore) ;;
            client) paths+=(deploy/docker/web.Dockerfile deploy/docker/web-entrypoint.sh deploy/docker/nginx
                deploy/nginx/androidmod-headers.conf docker-compose.yml .dockerignore) ;;
        esac
    fi
}

APK=android/app/build/outputs/apk/release/app-release.apk
MAPPING=android/app/build/outputs/mapping/release/mapping.txt

step() { printf '\n==> %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
die() { printf 'deploy: %s\n' "$*" >&2; exit 1; }
remote() { ssh -o BatchMode=yes "$HOST" "$@"; }
healthy() { curl -fsS -m 5 "$SITE_URL/api/health" >/dev/null 2>&1; }
# A short hash for display. A commit this clone does not know is shown as such
# rather than failing, since the plan is printed before anything can go wrong.
short() { git rev-parse -q --short --verify "$1^{commit}" 2>/dev/null || echo "unknown commit ${1:0:7}"; }

usage() { sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

dry_run=0
declare -A forced=() skipped=()
while (($#)); do
    case $1 in
        -n | --dry-run) dry_run=1 ;;
        --force)
            (($# >= 2)) || die "--force needs a component"
            case $2 in
                server | client | android) forced[$2]=1 ;;
                all) for c in "${COMPONENTS[@]}"; do forced[$c]=1; done ;;
                *) die "unknown component '$2' (server, client, android or all)" ;;
            esac
            shift
            ;;
        --skip)
            (($# >= 2)) || die "--skip needs a component"
            case $2 in
                server | client | android) skipped[$2]=1 ;;
                *) die "unknown component '$2' (server, client or android)" ;;
            esac
            shift
            ;;
        -h | --help) usage ;;
        *) usage 2 >&2 ;;
    esac
    shift
done

cd "$(git rev-parse --show-toplevel)"

# --- target ---------------------------------------------------------------------

# Read only after the arguments, so --help works on a machine with no deploy.env.
source deploy/deploy-env.sh
load_deploy_env
# systemd: a unit runs the server from the checkout's venv, and the host's nginx
# serves the website from DEPLOY_WEB_ROOT. docker: docker-compose.yml in the
# checkout runs both, and the website is built into the web image, so there is
# no web root.
RUNTIME=${DEPLOY_RUNTIME:-systemd}
[[ $RUNTIME == systemd || $RUNTIME == docker ]] ||
    die "DEPLOY_RUNTIME must be systemd or docker, not '$RUNTIME'"
missing=()
for var in DEPLOY_HOST DEPLOY_REPO DEPLOY_WEB_ROOT DEPLOY_SITE_URL; do
    [[ $RUNTIME == docker && $var == DEPLOY_WEB_ROOT ]] && continue
    [[ -n ${!var:-} ]] || missing+=("$var")
done
((${#missing[@]} == 0)) || die "set ${missing[*]} in deploy/deploy.env (see deploy/deploy.env.example)"
HOST=$DEPLOY_HOST
REMOTE_REPO=$DEPLOY_REPO
[[ $RUNTIME == docker ]] || WEB_ROOT=$DEPLOY_WEB_ROOT
SITE_URL=$DEPLOY_SITE_URL
# The server's own default (config.py) and the unit template's name.
STORE_APK=${DEPLOY_STORE_APK:-/opt/patched/store.apk}
SERVICE=${DEPLOY_SERVICE:-patchingsite}
# Relative, so under the SSH user's home: every remote command starts there.
STATE_DIR=${DEPLOY_STATE_DIR:-.deploy-state}
# R8 renames every class in a release build, so a crash report from a published
# store app can only be decoded with that exact build's mapping. Kept outside the
# repo, one per versionCode.
MAPPING_DIR=${DEPLOY_MAPPING_DIR:-$HOME/.local/share/androidmod/mappings}

# --- preflight ------------------------------------------------------------------

step "Checking what to deploy"

# Untracked files count too: an uncommitted Kotlin file under android/ would be
# compiled into the APK while git never saw it.
[[ -z $(git status --porcelain) ]] || die "the working tree is not clean; commit or stash first"
git fetch -q origin
NEW=$(git rev-parse HEAD)
# The host pulls from GitHub, so it can only ever reach a pushed commit - and
# deploying anything but main would leave the live site on a branch.
[[ $NEW == "$(git rev-parse origin/main)" ]] ||
    die "HEAD ($(git rev-parse --short HEAD)) is not origin/main; push or pull first"
note "commit $(git log -1 --format='%h %s' "$NEW")"

# One round trip for the host's state: its checkout, and the commit each component
# was last deployed at. A component without a marker falls back to the checkout,
# which is what is live whenever the markers have not been written yet.
declare -A deployed=()
host_head='' host_dirty=''
while read -r key value; do
    case $key in
        head) host_head=$value ;;
        dirty) host_dirty=$value ;;
        *) deployed[$key]=$value ;;
    esac
done < <(remote "
    set -e
    echo head \$(git -C '$REMOTE_REPO' rev-parse HEAD)
    echo dirty \$(git -C '$REMOTE_REPO' status --porcelain --untracked-files=no | wc -l)
    for c in ${COMPONENTS[*]}; do echo \"\$c \$(cat '$STATE_DIR'/\$c 2>/dev/null || true)\"; done
")
[[ -n $host_head ]] || die "could not read the host's checkout at $HOST:$REMOTE_REPO"
# A hand edit on the host would make the fast-forward below fail halfway through.
[[ $host_dirty == 0 ]] || die "the host's checkout has local changes; resolve them on $HOST first"

# True when the component's files differ between its last deploy and NEW. A base
# this clone does not know (history rewritten, marker corrupted) counts as changed:
# redeploying is safe, silently skipping is not.
changed() {
    local base=$1
    shift
    git cat-file -e "$base^{commit}" 2>/dev/null || return 0
    ! git diff --quiet "$base" "$NEW" -- "$@"
}

declare -A needed=() base=()
for c in "${COMPONENTS[@]}"; do
    base[$c]=${deployed[$c]:-$host_head}
    component_paths "$c"
    [[ -n ${forced[$c]:-} && -n ${skipped[$c]:-} ]] && die "--force and --skip both name $c"
    if [[ -n ${skipped[$c]:-} ]]; then
        continue
    fi
    if [[ -n ${forced[$c]:-} ]] || changed "${base[$c]}" "${paths[@]}"; then
        needed[$c]=1
    fi
done

# Narrower triggers inside the server and client steps.
pip_install=0 db_backup=0 npm_install=0
if [[ -n ${needed[server]:-} ]]; then
    changed "${base[server]}" server/requirements.txt && pip_install=1
    # Schema changes live in database.py and run on startup, so back up first:
    # a migration that goes wrong is undone by restoring this copy.
    changed "${base[server]}" server/database.py && db_backup=1
fi
if [[ -n ${needed[client]:-} ]]; then
    changed "${base[client]}" client/package.json client/package-lock.json && npm_install=1
fi

note "host is at $(short "$host_head")"
for c in "${COMPONENTS[@]}"; do
    if [[ -n ${needed[$c]:-} ]]; then
        why=$([[ -n ${forced[$c]:-} ]] && echo forced || echo changed)
        note "$(printf '%-8s' "$c") deploy ($why since $(short "${base[$c]}"))"
    elif [[ -n ${skipped[$c]:-} ]]; then
        note "$(printf '%-8s' "$c") skipped (stays at $(short "${base[$c]}"))"
    else
        note "$(printf '%-8s' "$c") up to date"
    fi
done
if [[ $RUNTIME == docker ]]; then
    # The image builds install requirements.txt and run npm ci themselves.
    ((db_backup)) && note "server   database.py changed: back up the database first"
    [[ -n ${needed[server]:-} ]] && note "server   docker compose up -d --build app backup"
    [[ -n ${needed[client]:-} ]] && note "client   docker compose up -d --build web"
else
    ((pip_install)) && note "server   requirements.txt changed: pip install"
    ((db_backup)) && note "server   database.py changed: back up the database first"
    ((npm_install)) && note "client   package files changed: npm ci"
fi

if ((dry_run)); then
    step "Dry run: nothing was changed"
    exit 0
fi

# A skipped component keeps its old marker, so the next run still sees its
# changes and deploys them.
mark_deployed() {
    [[ -z ${skipped[$1]:-} ]] || return 0
    remote "mkdir -p '$STATE_DIR' && echo '$NEW' > '$STATE_DIR/$1'"
}

# --- android build (local) ------------------------------------------------------

# Built before anything on the host changes, so a failed build - most often
# missing signing secrets, which assembleRelease refuses to build without -
# leaves the live site exactly as it was.
if [[ -n ${needed[android]:-} ]]; then
    step "Building the store app"
    (cd android && ./gradlew -q :app:assembleRelease)
    [[ -f $APK ]] || die "the release build produced no $APK"
    apk_sha=$(sha256sum "$APK" | cut -d' ' -f1)
    note "$APK ($(du -h "$APK" | cut -f1))"
    version_code=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionCode"])' \
        "$(dirname "$APK")/output-metadata.json")
    [[ -s $MAPPING ]] || die "the release build produced no $MAPPING"
    mkdir -p "$MAPPING_DIR"
    cp "$MAPPING" "$MAPPING_DIR/mapping-$version_code.txt"
    note "R8 mapping kept as $MAPPING_DIR/mapping-$version_code.txt"
fi

# --- host checkout ---------------------------------------------------------------

step "Updating the host's checkout"
# docker: a component without a marker was taken to be at the checkout, but what
# runs is the images, built when they were. Once the checkout moves on, that
# fallback would point past them, and a deploy failing after this point would
# leave the next run seeing nothing to do. So pin the base the plan used first.
if [[ $RUNTIME == docker ]]; then
    unmarked=()
    for c in "${COMPONENTS[@]}"; do
        [[ -n ${deployed[$c]:-} ]] || unmarked+=("$c")
    done
    if ((${#unmarked[@]})); then
        remote "mkdir -p '$STATE_DIR' && for c in ${unmarked[*]}; do echo '$host_head' > '$STATE_DIR'/\$c; done"
        note "no marker for ${unmarked[*]}: marked as deployed at $(short "$host_head"), the checkout's commit"
    fi
fi
# -A only here: the host has no GitHub key of its own and fetches with ours.
ssh -A -o BatchMode=yes "$HOST" "
    set -e
    cd '$REMOTE_REPO'
    git fetch -q origin
    git merge -q --ff-only '$NEW'
    test \"\$(git rev-parse HEAD)\" = '$NEW'
"
note "now at $(git rev-parse --short "$NEW")"

# --- server --------------------------------------------------------------------------

# Copies the database beside itself and prints the copy's path. $1 is the remote
# command that runs Python, reading the script from stdin, where `import config`
# finds the server's settings. Python's backup API rather than cp: the database
# runs in WAL mode, so a plain copy can miss writes still in the -wal file. The
# host has no sqlite3 CLI.
back_up_database() {
    remote "$1" <<'PY'
import sqlite3, time, config
target = f"{config.DB_PATH}.{time.strftime('%Y-%m-%d-%H%M%S')}.bak"
source, copy = sqlite3.connect(config.DB_PATH), sqlite3.connect(target)
source.backup(copy)
copy.close()
source.close()
print(target)
PY
}

# Waits up to a minute for the site to answer /api/health. If it never does,
# shows the output of the remote command $1, the logs, and stops.
wait_healthy() {
    for _ in $(seq 1 60); do
        healthy && break
        sleep 1
    done
    if ! healthy; then
        remote "$1" >&2 || true
        die "$SITE_URL/api/health did not come up after the restart"
    fi
    note "healthy"
}

if [[ -n ${needed[server]:-} && $RUNTIME == docker ]]; then
    step "Deploying the server"
    if ((db_backup)); then
        # In the app container, so the copy lands in the data dir beside the
        # database. exec skips the entrypoint and its umask 077, so sh sets it
        # again: the copy must be 600 like the database.
        backup=$(back_up_database "cd '$REMOTE_REPO' && docker compose exec -T app sh -c 'umask 077 && exec python -'")
        note "database backed up to $backup in the app container (HOST_DATA_DIR on the host)"
    fi
    # compose builds the new image while the old container keeps serving, then
    # stops that container and gives in-flight APK signings up to its
    # stop_grace_period (22m) to finish. Queued downloads fail with "Server
    # restarted, please try again". web (nginx) is never recreated here, which
    # would drop every open connection; it is only started if it is not running,
    # as on a new stack, since the health check goes through it.
    note "rebuilding app and backup (waits for in-flight signings to finish)"
    remote "
        set -e
        cd '$REMOTE_REPO'
        docker compose up -d --build app backup
        APP_COMMIT=$(git rev-parse --short "$NEW") docker compose up -d --no-recreate web
    "
    # Checked through nginx, as a user reaches it. The entrypoint runs `manage.py
    # doctor` before the server, so a bad config shows up here: the container
    # restarts over and over, and its logs say why.
    wait_healthy "cd '$REMOTE_REPO' && docker compose logs --tail 30 app"
elif [[ -n ${needed[server]:-} ]]; then
    step "Deploying the server"
    if ((pip_install)); then
        remote "cd '$REMOTE_REPO/server' && .venv/bin/python -m pip install -q -r requirements.txt"
        note "requirements installed"
    fi
    if ((db_backup)); then
        backup=$(back_up_database "cd '$REMOTE_REPO/server' && .venv/bin/python -")
        note "database backed up to $backup"
    fi
    # systemctl blocks until the old process has stopped, and the unit gives
    # in-flight APK signings up to TimeoutStopSec=1320 to finish. Queued downloads
    # fail with "Server restarted, please try again".
    note "restarting $SERVICE (waits for in-flight signings to finish)"
    remote "sudo systemctl restart '$SERVICE'"
    # Checked through nginx, as a user reaches it. The unit's ExecStartPre runs
    # `manage.py doctor`, so a bad config fails the restart above, not this.
    wait_healthy "sudo journalctl -u '$SERVICE' -n 30 --no-pager"
fi
mark_deployed server

# --- client --------------------------------------------------------------------------

if [[ -n ${needed[client]:-} && $RUNTIME == docker ]]; then
    step "Deploying the website"
    # Built into the web image from the checkout just updated, so the served
    # files match the deployed commit exactly. --no-deps keeps app as it is:
    # without it compose would build app too, and recreate it when it has
    # changes not deployed yet (--skip server).
    note "rebuilding web (restarting nginx drops its open connections)"
    remote "cd '$REMOTE_REPO' && APP_COMMIT=$(git rev-parse --short "$NEW") docker compose up -d --build --no-deps web"
    # nginx was recreated, so the site must answer again.
    wait_healthy "cd '$REMOTE_REPO' && docker compose logs --tail 30 web"
elif [[ -n ${needed[client]:-} ]]; then
    step "Deploying the website"
    # Built on the host from the checkout just updated, so the served files match
    # the deployed commit exactly. nginx cannot read the SSH user's home, hence
    # the copy into the web root, which must stay world-readable.
    remote "
        set -e
        cd '$REMOTE_REPO/client'
        if [ $npm_install = 1 ] || [ ! -d node_modules ]; then npm ci --no-audit --no-fund --loglevel=error; fi
        npm run build --silent >/dev/null
        rsync -a --delete dist/ '$WEB_ROOT/'
        chmod -R a+rX '$WEB_ROOT'
    "
    note "built and copied to $WEB_ROOT"
fi
mark_deployed client

# --- android upload ------------------------------------------------------------------

if [[ -n ${needed[android]:-} ]]; then
    step "Publishing the store app"
    # Uploaded beside the live file and renamed over it: rename is atomic on one
    # filesystem, so a download in progress never gets a half-written APK.
    scp -q -o BatchMode=yes "$APK" "$HOST:$STORE_APK.tmp"
    remote "
        set -e
        test \"\$(sha256sum '$STORE_APK.tmp' | cut -d' ' -f1)\" = '$apk_sha'
        chmod 664 '$STORE_APK.tmp'
        mv '$STORE_APK.tmp' '$STORE_APK'
    "
    # The server reads the version out of the file itself; a null here means the
    # app will not offer this build as an update.
    info=$(curl -fsS -m 15 "$SITE_URL/api/store")
    note "$SITE_URL/api/store: $info"
    [[ $info == *'"version_code":'[0-9]* ]] ||
        die "the server could not read the new APK's version, so no app will offer it"
fi
mark_deployed android

step "Deployed $(git rev-parse --short "$NEW") to $HOST"
