#!/usr/bin/env bash
# Invoked through SSH by Jenkins. DB creation/restoration/removal is always manual.
set +x
set -euo pipefail

test "$#" -eq 2
action="$1"
revision="$2"
[[ "$action" == check || "$action" == deploy ]] || { echo "Invalid deployment action." >&2; exit 1; }
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo "Invalid Git revision." >&2; exit 1; }
test -n "$PERFUME_EXPECTED_DB_PASSWORD"
deploy_directory=/srv/perfume
cd "$deploy_directory"
test "$(pwd -P)" = "$deploy_directory"
test -f .env && test ! -L .env
test -f .perfume-db-ready && test ! -L .perfume-db-ready
test "$(cat .perfume-db-ready)" = perfume-dev_pgdata
test ! -L .perfume-deploy.lock
for tool in docker git python3 flock sha256sum curl; do command -v "$tool" >/dev/null; done
exec 9>.perfume-deploy.lock
flock -n 9 || { echo "Another perfume deployment holds the lock." >&2; exit 1; }
# A killed supervisor can leave a daemon-owned one-off worker. Its marker is cleared
# only by the batch supervisor after proving absence; never bypass it during deploy.
test ! -e .perfume-embedding-active && test ! -L .perfume-embedding-active || {
    echo "Embedding worker recovery is required before deployment." >&2; exit 1;
}

# Do not inherit a different project's Compose or DB settings from the SSH session.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY PERFUME_DB_IMAGE
# ES is intentionally outside this Compose model. Never remove it as an orphan, even via .env/SSH defaults.
export COMPOSE_REMOVE_ORPHANS=false
# AI activation and model identities must come only from the operator-owned server .env.
unset PERFUME_AI_SEARCH_ENABLED PERFUME_AI_SEARCH_MODEL_DIGEST PERFUME_AI_RECOMMEND_ENABLED PERFUME_AI_RECOMMEND_MODEL_DIGEST
# Authentication activation also comes only from the operator-owned server .env.
unset PERFUME_AUTH_ENABLED PERFUME_AUTH_COOKIE_SECURE PERFUME_AUTH_DEV_HTTP_ENABLED PERFUME_AUTH_DEV_HTTP_ORIGIN SPRING_PROFILES_ACTIVE
# Support is a separate opt-in, never inherited from a Jenkins/SSH environment.
unset PERFUME_SUPPORT_ENABLED PERFUME_SUPPORT_ALLOWED_ORIGINS
unset PERFUME_ELASTICSEARCH_ENABLED PERFUME_ELASTICSEARCH_URL PERFUME_ELASTICSEARCH_ALIAS PERFUME_ELASTICSEARCH_CA_FILE PERFUME_ELASTICSEARCH_API_KEY_FILE PERFUME_ELASTICSEARCH_INDEX_API_KEY_FILE
env_hash="$(sha256sum .env)"
activation_flags="$(python3 - <<'PY'
from pathlib import Path
import re
flags = []
lines = Path('.env').read_text().splitlines()
for name in ('PERFUME_AUTH_DEV_HTTP_ENABLED', 'PERFUME_ELASTICSEARCH_ENABLED'):
    values = []
    for line in lines:
        match = re.match(r'^\s*(?:export\s+)?' + name + r'\s*=(.*)$', line)
        if match:
            value = re.fullmatch(r'''\s*(true|false|'true'|'false'|"true"|"false")\s*(?:#.*)?''', match[1])
            if value is None:
                raise SystemExit('Invalid ' + name + ' flag in server .env')
            values.append(value[1].strip('\'"'))
    if len(values) > 1:
        raise SystemExit('Duplicate ' + name + ' flag in server .env')
    flags.append(values[0] if values else 'false')
print(' '.join(flags))
PY
)"
read -r auth_dev_http elasticsearch_enabled <<< "$activation_flags"
elasticsearch_compose=false
elasticsearch_files_hash=''
export GIT_TERMINAL_PROMPT=0
compose() {
    local overrides=()
    if [[ "$auth_dev_http" == true ]]; then
        test -f "$deploy_directory/compose.auth-dev-http.yaml"
        test ! -L "$deploy_directory/compose.auth-dev-http.yaml"
        overrides+=(--file "$deploy_directory/compose.auth-dev-http.yaml")
    fi
    if [[ "$elasticsearch_compose" == true ]]; then
        for file in compose.elasticsearch-app.yaml compose.elasticsearch-jenkins.yaml; do
            test -f "$deploy_directory/$file" && test ! -L "$deploy_directory/$file"
            overrides+=(--file "$deploy_directory/$file")
        done
    fi
    docker compose --project-name perfume-dev --project-directory "$deploy_directory" \
        --env-file "$deploy_directory/.env" --file "$deploy_directory/compose.yaml" "${overrides[@]}" "$@"
}
ready_hash="$(sha256sum .perfume-db-ready)"
approval_file="$deploy_directory/.env.pgvector-approval.json"
approval_hash_now() {
    test ! -L "$approval_file"
    if [[ -e "$approval_file" ]]; then
        test -f "$approval_file"
        sha256sum "$approval_file"
    else
        printf 'absent\n'
    fi
}
approval_hash="$(approval_hash_now)"
test "$(git rev-parse --show-toplevel)" = "$deploy_directory"
if git ls-files --error-unmatch .env >/dev/null 2>&1; then
    echo "Refusing deployment: server .env is tracked by Git." >&2
    exit 1
fi
case "$(git remote get-url origin)" in
    https://github.com/yuchulPark/perfume.git|git@github.com:yuchulPark/perfume.git) ;;
    *) echo "Unexpected deployment repository." >&2; exit 1 ;;
esac

verify_configuration() {
    local guard_help elastic_args=() fingerprint
    guard_help="$(python3 scripts/validate-perfume-compose.py --help)"
    elasticsearch_compose=false
    if [[ "$guard_help" == *--elasticsearch-enabled* ]]; then
        elastic_args=(--elasticsearch-enabled "$elasticsearch_enabled")
        elasticsearch_compose="$elasticsearch_enabled"
    elif [[ "$elasticsearch_enabled" == true ]]; then
        # Incoming shell runs before git reset; the old validator cannot accept the new connection metadata.
        # This preflight changes no container. After checkout the new validation is mandatory.
        echo "Elasticsearch connection validation deferred until the reviewed checkout is available."
    fi
    compose config --quiet
    # The first deployment receives this script over SSH BEFORE the server checkout
    # has the new validator. Keep that initial preflight compatible with the old CLI.
    if [[ "$guard_help" == *--db-container* ]]; then
        configured_db_image="$(compose config --format json | python3 scripts/validate-perfume-compose.py \
            --deploy-directory "$deploy_directory" --approval-file "$approval_file" --print-db-image "${elastic_args[@]}")"
        pgvector_guard=true
    else
        compose config --format json | python3 scripts/validate-perfume-compose.py --deploy-directory "$deploy_directory"
        configured_db_image=postgres:17-bookworm
        pgvector_guard=false
    fi
    if [[ "$elasticsearch_compose" == true ]]; then
        fingerprint="$(python3 scripts/validate-perfume-compose.py --elasticsearch-files --deploy-directory "$deploy_directory")"
        if [[ -n "$elasticsearch_files_hash" ]]; then
            test "$fingerprint" = "$elasticsearch_files_hash" || { echo "Search secret files changed during deployment." >&2; exit 1; }
        else
            elasticsearch_files_hash="$fingerprint"
        fi
        # Read-only metadata check. Do not require a running/healthy ES node: queries can fall back to SQL.
        docker network inspect --format '{{json .}}' perfume-dev_search \
            | python3 scripts/validate-perfume-compose.py --elasticsearch-network
    fi
}
verify_database() {
    docker volume inspect perfume-dev_pgdata >/dev/null
    db_id="$(docker ps --all --quiet \
        --filter label=com.docker.compose.project=perfume-dev \
        --filter label=com.docker.compose.service=db)"
    [[ "$db_id" =~ ^[0-9a-f]+$ ]] || { echo "Expected exactly one existing perfume DB container." >&2; exit 1; }
    test "$(docker inspect --format '{{.State.Running}}' "$db_id")" = true
    test "$(docker inspect --format '{{.State.Health.Status}}' "$db_id")" = healthy
    test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Type}} {{.Name}}{{end}}{{end}}' "$db_id")" = "volume perfume-dev_pgdata"
    test "$(docker port "$db_id" 5432/tcp)" = "127.0.0.1:15432"
    # Read-only image/file inspection: no pull, Docker run, or DB lifecycle action.
    if [[ "$pgvector_guard" == true ]]; then
        docker inspect --format '{{json .}}' "$db_id" | python3 scripts/validate-perfume-compose.py \
            --db-container --expected-db-image "$configured_db_image" --approval-file "$approval_file"
    else
        # Upgrade preflight permits only the known original digest, never pgvector.
        running_image_id="$(docker inspect --format '{{.Image}}' "$db_id")"
        docker image inspect --format '{{json .RepoDigests}}' "$running_image_id" | python3 -c '
import json, sys
expected = "sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826"
assert any(value in ("postgres@" + expected, "docker.io/library/postgres@" + expected) for value in json.load(sys.stdin))
'
    fi
    # Only a read query. Verify TCP authentication and restored Phase 1/2 tables.
    db_identity="$(printf '%s' "$PERFUME_EXPECTED_DB_PASSWORD" | docker exec -i "$db_id" sh -c '
        PGPASSWORD="$(cat)"; export PGPASSWORD
        exec psql -X -h 127.0.0.1 -U perfume_user -d perfume -At -v ON_ERROR_STOP=1 -c "
            SELECT current_database() || chr(124) || current_user || chr(124) ||
                current_setting('\''server_version_num'\'') || chr(124) ||
                (to_regclass('\''public.brands'\'') IS NOT NULL)::text || chr(124) ||
                (to_regclass('\''public.perfumes'\'') IS NOT NULL)::text || chr(124) ||
                (to_regclass('\''public.perfume_provider_payloads'\'') IS NOT NULL)::text || chr(124) ||
                coalesce((SELECT extversion FROM pg_extension WHERE extname = '\''vector'\''), '\''none'\'');"
    ')"
    expected_identity='perfume|perfume_user|170011|true|true|true'
    if [[ "$configured_db_image" == postgres:17-bookworm || "$configured_db_image" == postgres:17-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826 ]]; then
        test "$db_identity" = "$expected_identity|none"
    else
        [[ "$db_identity" == "$expected_identity|none" || "$db_identity" == "$expected_identity|0.8.7" ]]
    fi
}
verify_configuration
verify_database
original_db_id="$db_id"

if [[ "$action" == check ]]; then
    echo "Perfume preflight passed: existing restored DB and server .env verified."
    exit 0
fi

git fetch --no-tags origin main
git cat-file -e "$revision^{commit}"
git merge-base --is-ancestor "$revision" FETCH_HEAD
# Inspect incoming paths before reset can overwrite operator-managed files.
git ls-tree -r -z --name-only "$revision" | python3 -c '
import sys
paths = sys.stdin.buffer.read().split(b"\0")
for path in paths:
    name = path.rsplit(b"/", 1)[-1]
    if (path == b"backups" or path.startswith(b"backups/") or
        path == b".secrets" or path.startswith(b".secrets/") or
        path in (b".perfume-db-ready", b".perfume-deploy.lock", b".perfume-embedding-active") or
        (name.startswith(b".env") and name != b".env.example") or
        name.endswith((b".dump", b".backup"))):
        print("Refusing deployment: incoming revision tracks protected operator files.", file=sys.stderr)
        sys.exit(1)
'
git reset --hard "$revision"
test "$(sha256sum .env)" = "$env_hash"
test "$(sha256sum .perfume-db-ready)" = "$ready_hash"
test "$(approval_hash_now)" = "$approval_hash"
verify_configuration
test "$pgvector_guard" = true
[[ "$elasticsearch_enabled" != true || "$elasticsearch_compose" == true ]] || {
    echo "Reviewed Elasticsearch deployment validation is required before application replacement." >&2; exit 1;
}

compose build backend frontend
docker image inspect --format '{{json .Config}}' perfume-dev-backend \
    | python3 scripts/validate-perfume-compose.py --backend-image
verify_database
test "$db_id" = "$original_db_id"
# Compose must not start/recreate the DB through service dependencies.
compose up --detach --no-deps --no-build --wait --wait-timeout 180 backend
compose up --detach --no-deps --no-build --wait --wait-timeout 180 frontend
verify_database
test "$db_id" = "$original_db_id"
test "$(sha256sum .env)" = "$env_hash"
test "$(sha256sum .perfume-db-ready)" = "$ready_hash"
test "$(approval_hash_now)" = "$approval_hash"
if [[ "$elasticsearch_enabled" == true ]]; then
    test "$(python3 scripts/validate-perfume-compose.py --elasticsearch-files --deploy-directory "$deploy_directory")" = "$elasticsearch_files_hash"
fi
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8088/healthz >/dev/null
curl --fail --silent --show-error --max-time 15 'http://127.0.0.1:8088/api/perfumes?size=1' >/dev/null
compose ps backend frontend
printf 'Catalog Elasticsearch opt-in from server .env: %s.\n' "$elasticsearch_enabled"
printf 'Perfume revision %s deployed; DB container, volume and .env preserved.\n' "$revision"
