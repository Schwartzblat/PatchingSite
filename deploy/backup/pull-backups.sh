#!/usr/bin/env bash
#
# Copies the host's nightly database snapshots to this machine, so losing the
# server does not lose the accounts. Run by the androidmod-backup-pull user timer
# (its unit file says how to install it), or by hand. The host is DEPLOY_HOST from
# deploy/deploy.env, the one deploy.sh deploys to, unless BACKUP_HOST names another.
#
# The host keeps 14 days; this keeps the newest BACKUP_KEEP snapshots (default 60).
# Exits non-zero when the newest local copy is over two days old, so a host whose
# nightly snapshot has stopped shows up as a failed unit here.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/../deploy-env.sh"
load_deploy_env
HOST=${BACKUP_HOST:-${DEPLOY_HOST:-}}
[[ -n $HOST ]] || { echo "set DEPLOY_HOST in deploy/deploy.env (see deploy/deploy.env.example)" >&2; exit 2; }
REMOTE_DIR=${BACKUP_REMOTE_DIR:-/var/lib/patchingsite-backups}
LOCAL_DIR=${BACKUP_LOCAL_DIR:-$HOME/Backups/androidmod}
KEEP=${BACKUP_KEEP:-60}

mkdir -p "$LOCAL_DIR"
chmod 700 "$LOCAL_DIR"

# BatchMode: under the timer there is nobody to type a passphrase. The key comes
# from the session's ssh-agent (GNOME keyring here), so pulls only succeed while
# you are logged in; a missed day is caught up by the next run.
# Only finished snapshots: the host writes .users-*.partial first and renames.
rsync -a --chmod=D700,F600 --include='users-*.db' --exclude='*' \
    -e 'ssh -o BatchMode=yes' "$HOST:$REMOTE_DIR/" "$LOCAL_DIR/"

mapfile -t snapshots < <(find "$LOCAL_DIR" -maxdepth 1 -name 'users-*.db' | sort)
if ((${#snapshots[@]} > KEEP)); then
    rm -- "${snapshots[@]:0:${#snapshots[@]}-KEEP}"
    snapshots=("${snapshots[@]: -KEEP}")
fi

((${#snapshots[@]})) || { echo "no snapshots in $LOCAL_DIR" >&2; exit 1; }
newest=${snapshots[-1]}
echo "newest snapshot: $newest"
if [[ -n $(find "$newest" -mmin +2880) ]]; then
    echo "the newest snapshot is over two days old; is patchingsite-backup.timer running?" >&2
    exit 1
fi
