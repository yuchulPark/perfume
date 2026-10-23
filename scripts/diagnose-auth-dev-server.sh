#!/usr/bin/env bash
# Read-only diagnostic for the user's existing Ubuntu portfolio development VM.
# No source/eval of .env, credential output, DDL, account writes, or model inference.
set +x
set -euo pipefail
cd /srv/perfume
test "$(pwd -P)" = /srv/perfume
test -f .env && test ! -L .env
unset COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES DB_URL DB_NAME DB_USERNAME DB_PASSWORD PERFUME_DB_IMAGE
unset PERFUME_AUTH_ENABLED PERFUME_AUTH_COOKIE_SECURE PERFUME_AUTH_DEV_HTTP_ENABLED PERFUME_AUTH_DEV_HTTP_ORIGIN SPRING_PROFILES_ACTIVE
compose() {
    docker compose --project-name perfume-dev --project-directory /srv/perfume \
        --env-file /srv/perfume/.env --file /srv/perfume/compose.yaml "$@"
}

python3 -B - <<'PY'
from pathlib import Path
import hashlib, json, re
p = Path('.env')
matches = []
http_matches = []
for line in p.read_text().splitlines():
    match = re.fullmatch(r'\s*(?:export\s+)?PERFUME_AUTH_ENABLED\s*=\s*(.*?)\s*', line)
    if match:
        value = match[1].split('#', 1)[0].strip().strip('\'"')
        matches.append(value if value in ('true', 'false') else '<invalid or nonliteral>')
    match = re.fullmatch(r'\s*(?:export\s+)?PERFUME_AUTH_DEV_HTTP_ENABLED\s*=\s*(.*?)\s*', line)
    if match:
        value = match[1].split('#', 1)[0].strip().strip('\'"')
        http_matches.append(value if value in ('true', 'false') else '<invalid or nonliteral>')
print(json.dumps({'env_sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
                  'env_auth_flag_entries': matches, 'env_http_flag_entries': http_matches}, sort_keys=True))
PY
git rev-parse --short HEAD
for service in db backend frontend ollama; do
    id="$(compose ps --all --quiet "$service")"
    [[ "$id" =~ ^[0-9a-f]{12,64}$ ]] || { echo "Expected one existing $service container; stopping." >&2; exit 1; }
    docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}} {{.Id}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}} {{.Image}}' "$id"
    docker inspect --format '{{json .Mounts}}' "$id" | python3 -B -c \
        'import json,sys; print(json.dumps([{"type":m["Type"],"name":m.get("Name"),"destination":m["Destination"]} for m in json.load(sys.stdin)]))'
done
backend_id="$(compose ps --quiet backend)"
db_id="$(compose ps --quiet db)"
ollama_id="$(compose ps --quiet ollama)"
test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$db_id")" = perfume-dev_pgdata
test "$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/root/.ollama"}}{{.Name}}{{end}}{{end}}' "$ollama_id")" = perfume-dev_ollama-data
docker inspect --format '{{json .Config.Env}}' "$backend_id" | python3 -B -c '
import json,sys
env = dict(item.split("=",1) for item in json.load(sys.stdin))
keys = ("PERFUME_AUTH_ENABLED","PERFUME_AUTH_COOKIE_SECURE","SPRING_PROFILES_ACTIVE",
        "PERFUME_AUTH_DEV_HTTP_ENABLED",
        "PERFUME_AI_SEARCH_ENABLED","PERFUME_AI_RECOMMEND_ENABLED")
print(json.dumps({key:env.get(key,"<unset>") for key in keys},sort_keys=True))
expected=env.get("DB_URL")=="jdbc:postgresql://db:5432/perfume"
print(json.dumps({"expected_catalog_db_url":expected}))
if not expected: sys.exit("Unexpected catalog DB URL; stopping before SQL")'

# Fixed expected database identity, never a URL supplied by a caller.
docker exec -i "$db_id" psql -X -U perfume_user -d perfume -v ON_ERROR_STOP=1 <<'SQL'
BEGIN READ ONLY;
SELECT current_database(), current_user, current_setting('server_version_num'), current_setting('data_directory');
SELECT system_identifier FROM pg_control_system();
SELECT to_regclass('auth.app_users') AS auth_table;
SELECT format('SELECT %L AS relation, count(*) AS rows FROM %I.%I;', table_schema || '.' || table_name, table_schema, table_name)
FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema IN ('public','ai') ORDER BY table_schema,table_name
\gexec
COMMIT;
SQL
has_auth="$(docker exec "$db_id" psql -X -U perfume_user -d perfume -At -v ON_ERROR_STOP=1 \
    -c "SELECT to_regclass('auth.app_users') IS NOT NULL")"
if [[ "$has_auth" == t ]]; then
    docker exec -i "$db_id" psql -X -U perfume_user -d perfume -v ON_ERROR_STOP=1 <<'SQL'
BEGIN READ ONLY;
SELECT count(*) AS accounts, count(*) FILTER (WHERE role='ADMIN' AND enabled) AS enabled_admins,
       count(*) FILTER (WHERE username='admin') AS requested_id_exists FROM auth.app_users;
SELECT username='admin' AS requested_id, role, enabled,
       password_hash ~ '^\$2[aby]\$1[0-6]\$[./A-Za-z0-9]{53}$' AS supported_bcrypt
FROM auth.app_users;
SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='auth' AND tablename='app_users';
COMMIT;
SQL
fi
if [[ -e /var/lib/perfume-embedding/state.json ]]; then
    stat --format='checkpoint size=%s modified=%y' /var/lib/perfume-embedding/state.json
    sha256sum /var/lib/perfume-embedding/state.json
else
    echo 'checkpoint not visible or absent; no file was changed'
fi
if [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$ollama_id")" == healthy ]]; then
    timeout --signal=KILL 8 docker exec "$ollama_id" ollama list || echo 'Ollama model list unavailable; no inference was requested'
else
    echo 'Ollama is unhealthy; model list query skipped; no container was restarted'
fi
# The private HTTP authentication profile deliberately rejects a different Host.
curl --noproxy '*' --fail --silent --show-error --max-time 10 http://192.168.0.19:8088/api/auth/me
printf '\n'
curl --noproxy '*' --silent --show-error --max-time 10 --output /dev/null --write-out 'healthz HTTP %{http_code}\n' http://127.0.0.1:8088/healthz
