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

# Do not inherit a different project's Compose or DB settings from the SSH session.
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES DB_NAME DB_USERNAME DB_PASSWORD DB_URL SCENTREV_API_KEY
export GIT_TERMINAL_PROMPT=0
compose() {
    docker compose --project-name perfume-dev --project-directory "$deploy_directory" \
        --env-file "$deploy_directory/.env" --file "$deploy_directory/compose.yaml" "$@"
}
env_hash="$(sha256sum .env)"
ready_hash="$(sha256sum .perfume-db-ready)"
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
    compose config --quiet
    compose config --format json | python3 scripts/validate-perfume-compose.py
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
    # Only a read query. Verify TCP authentication and restored Phase 1/2 tables.
    db_identity="$(printf '%s' "$PERFUME_EXPECTED_DB_PASSWORD" | docker exec -i "$db_id" sh -c '
        PGPASSWORD="$(cat)"; export PGPASSWORD
        exec psql -X -h 127.0.0.1 -U perfume_user -d perfume -At -v ON_ERROR_STOP=1 -c "
            SELECT current_database() || chr(124) || current_user || chr(124) ||
                (current_setting('\''server_version_num'\'')::integer / 10000)::text || chr(124) ||
                (to_regclass('\''public.brands'\'') IS NOT NULL)::text || chr(124) ||
                (to_regclass('\''public.perfumes'\'') IS NOT NULL)::text || chr(124) ||
                (to_regclass('\''public.perfume_provider_payloads'\'') IS NOT NULL)::text;"
    ')"
    test "$db_identity" = "perfume|perfume_user|17|true|true|true"
}
verify_configuration
verify_database
original_db_id="$db_id"

if [[ "$action" == check ]]; then
    echo "Perfume preflight passed: existing restored DB and server .env verified."
    exit 0
fi

git fetch --no-tags origin develop
git cat-file -e "$revision^{commit}"
git merge-base --is-ancestor "$revision" FETCH_HEAD
# Inspect incoming paths before reset can overwrite operator-managed files.
git ls-tree -r -z --name-only "$revision" | python3 -c '
import sys
paths = sys.stdin.buffer.read().split(b"\0")
for path in paths:
    name = path.rsplit(b"/", 1)[-1]
    if (path == b"backups" or path.startswith(b"backups/") or
        path in (b".perfume-db-ready", b".perfume-deploy.lock") or
        (name.startswith(b".env") and name != b".env.example") or
        name.endswith((b".dump", b".backup"))):
        print("Refusing deployment: incoming revision tracks protected operator files.", file=sys.stderr)
        sys.exit(1)
'
git reset --hard "$revision"
test "$(sha256sum .env)" = "$env_hash"
test "$(sha256sum .perfume-db-ready)" = "$ready_hash"
verify_configuration

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
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8088/healthz >/dev/null
curl --fail --silent --show-error --max-time 15 'http://127.0.0.1:8088/api/perfumes?size=1' >/dev/null
compose ps backend frontend
printf 'Perfume revision %s deployed; DB container, volume and .env preserved.\n' "$revision"
