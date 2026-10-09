#!/usr/bin/env bash
set +x
set -euo pipefail

test "$#" -eq 2
action="$1"
revision="$2"
[[ "$action" == check || "$action" == deploy ]] || { echo "Invalid deployment action." >&2; exit 1; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid Git revision." >&2; exit 1; }
test -n "$PERFUME_DEPLOY_HOST"
test -n "$PERFUME_SSH_USER"
test -n "$PERFUME_DB_PASSWORD"
[[ "$PERFUME_DEPLOY_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9._:-]*$ ]] || { echo "Invalid deployment host." >&2; exit 1; }
[[ "$PERFUME_SSH_USER" =~ ^[a-zA-Z_][a-zA-Z0-9_-]*$ ]] || { echo "Invalid SSH username." >&2; exit 1; }
test -f "$PERFUME_SSH_KEY"
test -f "$PERFUME_KNOWN_HOSTS"

# Send the password over encrypted stdin, never in SSH arguments or a generated .env.
# The rest of stdin is the reviewed deployment script from the tested checkout.
{
    printf '%s' "$PERFUME_DB_PASSWORD" | base64 | tr -d '\r\n'
    printf '\n'
    cat scripts/jenkins-deploy.sh
} | ssh -T -i "$PERFUME_SSH_KEY" \
    -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile="$PERFUME_KNOWN_HOSTS" -o ConnectTimeout=15 \
    -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
    "$PERFUME_SSH_USER@$PERFUME_DEPLOY_HOST" \
    "IFS= read -r password_line; export PERFUME_EXPECTED_DB_PASSWORD=\$(printf '%s' \"\$password_line\" | base64 --decode); unset password_line; exec bash -s -- '$action' '$revision'"
