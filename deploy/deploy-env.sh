# Sourced by deploy/deploy.sh, deploy/launch-setup.sh and
# deploy/backup/pull-backups.sh, never run.
#
# load_deploy_env reads deploy/deploy.env, which names the live host and stays out
# of git (deploy/deploy.env.example is the template). That keeps the scripts free
# of any real machine, so the same files work for whoever deploys.
#
# A variable already set in the environment wins over the file, the precedence the
# server gives its own env files, so a one-off `DEPLOY_HOST=... deploy/deploy.sh`
# can rehearse against a staging box without editing anything.
#
# The file is parsed, not sourced: it is data, and a stray `$(...)` in it must not
# run. Each line is KEY=value; blank lines, comments and an inline ` # comment`
# are ignored, and one pair of surrounding quotes is stripped, as dotenv does.

load_deploy_env() {
    local file key value
    file="$(dirname "${BASH_SOURCE[0]}")/deploy.env"
    [[ -f $file ]] || return 0
    # The `|| -n $key` keeps a last line that has no trailing newline.
    while IFS='=' read -r key value || [[ -n $key ]]; do
        [[ $key =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
        value=${value%%[[:space:]]#*}
        value=${value%"${value##*[![:space:]]}"}
        if [[ $value =~ ^\"(.*)\"$ || $value =~ ^\'(.*)\'$ ]]; then
            value=${BASH_REMATCH[1]}
        fi
        [[ -v $key ]] || printf -v "$key" '%s' "$value"
    done <"$file"
}
