"""Fast, fail-closed command doubles. Only the actual validator needs Python processes."""
import json
from pathlib import Path
import shlex


FAKE_TOOLS = r'''trap - DEBUG
set -euo pipefail
root="$PERFUME_TEST_POSIX_DIRECTORY"
tool="$1"; shift
# One file per process avoids interleaved writes from shell pipelines. No argv is
# copied into timeout diagnostics; these NUL-delimited records stay in the fixture.
printf '%s\0' "$EPOCHREALTIME" "$tool" "$@" >"$root/commands/${EPOCHREALTIME//./_}-$BASHPID"
source "$root/fixture.sh"
reject() { printf 'Unexpected mock command or unsafe mock environment\n' >&2; exit 97; }
emit() { printf '%s\n' "$(<"$root/$1")"; }
db_id="$mock_db_id"
if [[ -e "$root/changed-db" ]]; then db_id="$mock_changed_db_id"; fi
case "$tool" in
git)
    case "$1 $2" in
        'rev-parse --show-toplevel') printf '%s\n' "$root" ;;
        'remote get-url') [[ "$3" == origin ]] || reject; printf 'https://github.com/yuchulPark/perfume.git\n' ;;
        'ls-files --error-unmatch') exit 1 ;;
        'reset --hard')
            if [[ "$mock_keep_legacy_guard" != 1 ]]; then cp -- "$PERFUME_TEST_GUARD_SOURCE" "$root/scripts/validate-perfume-compose.py"; fi
            : >"$root/checkout-updated"
            if [[ "$mock_change_secret" == 1 ]]; then
                chmod u+w "$root/.secrets/elasticsearch/search-api-key"
                printf 'changed-offline-key' >"$root/.secrets/elasticsearch/search-api-key"
                chmod 400 "$root/.secrets/elasticsearch/search-api-key"
            fi ;;
        'fetch --no-tags'|'cat-file -e'|'merge-base --is-ancestor') : ;;
        'ls-tree -r') if [[ "$mock_protected_incoming" == 1 ]]; then printf '%s\0' "$mock_protected_path"; fi ;;
        *) reject ;;
    esac ;;
curl|flock) : ;;
docker)
    case "$1" in
    compose)
        [[ "$COMPOSE_REMOVE_ORPHANS" == false ]] || reject
        for key in PERFUME_AI_SEARCH_ENABLED PERFUME_AI_SEARCH_MODEL_DIGEST PERFUME_AI_RECOMMEND_ENABLED PERFUME_AI_RECOMMEND_MODEL_DIGEST PERFUME_AUTH_ENABLED PERFUME_AUTH_COOKIE_SECURE PERFUME_AUTH_DEV_HTTP_ENABLED PERFUME_AUTH_DEV_HTTP_ORIGIN SPRING_PROFILES_ACTIVE PERFUME_ELASTICSEARCH_ENABLED PERFUME_ELASTICSEARCH_URL PERFUME_ELASTICSEARCH_ALIAS PERFUME_ELASTICSEARCH_CA_FILE PERFUME_ELASTICSEARCH_API_KEY_FILE PERFUME_ELASTICSEARCH_INDEX_API_KEY_FILE; do
            [[ ! -v "$key" ]] || reject
        done
        http_override=0; elastic_override=0; elastic_network=0; command=''; quiet=0
        for arg in "$@"; do
            case "$arg" in
                */compose.auth-dev-http.yaml) http_override=1 ;;
                */compose.elasticsearch-app.yaml) elastic_override=1 ;;
                */compose.elasticsearch-jenkins.yaml) elastic_network=1 ;;
                */compose.elasticsearch.yaml|*/compose.elasticsearch-reindex.yaml) reject ;;
                config|build|up|ps) command="$arg" ;;
                --quiet) quiet=1 ;;
            esac
        done
        [[ "$http_override" == "$mock_http" ]] || reject
        expected_elastic="$mock_elastic"
        if [[ "$mock_legacy" == 1 && ( ! -e "$root/checkout-updated" || "$mock_keep_legacy_guard" == 1 ) ]]; then expected_elastic=0; fi
        [[ "$elastic_override" == "$expected_elastic" && "$elastic_network" == "$expected_elastic" ]] || reject
        case "$command" in
        config) if [[ "$quiet" == 0 ]]; then
            if [[ "$expected_elastic" == 0 ]]; then emit compose-base.json; else emit compose.json; fi
        fi ;;
        build) [[ "${@: -2}" == 'backend frontend' ]] || reject ;;
        up)
            [[ " $* " == *' --no-deps '* && " $* " == *' --no-build '* ]] || reject
            [[ "${!#}" == backend || "${!#}" == frontend ]] || reject
            if [[ "$mock_change_id" == 1 ]]; then : >"$root/changed-db"; fi ;;
        ps) : ;;
        *) reject ;;
        esac ;;
    volume) [[ "$*" == 'volume inspect perfume-dev_pgdata' ]] || reject ;;
    network) [[ "$*" == "network inspect --format {{json .}} perfume-dev_search" ]] || reject; emit search-network.json ;;
    ps)
        [[ " $* " == *' label=com.docker.compose.project=perfume-dev '* && " $* " == *' label=com.docker.compose.service=db '* ]] || reject
        printf '%s\n' "$db_id" ;;
    inspect)
        [[ "$2" == --format ]] || reject
        case "$3" in
        '{{.State.Running}}') printf 'true\n' ;;
        '{{.State.Health.Status}}') printf 'healthy\n' ;;
        '{{.Image}}') printf '%s\n' "$mock_image_id" ;;
        '{{json .}}') if [[ -e "$root/changed-db" ]]; then emit changed-container.json; else emit container.json; fi ;;
        *'.Mounts'*) printf 'volume perfume-dev_pgdata\n' ;;
        *) reject ;;
        esac ;;
    port) printf '127.0.0.1:15432\n' ;;
    image)
        [[ "$2" == inspect ]] || reject
        if [[ " $* " != *' --format '* ]]; then emit image.json
        elif [[ "$4" == '{{json .RepoDigests}}' ]]; then emit digests.json
        else
            [[ "$4" == '{{json .Config}}' && "${!#}" == perfume-dev-backend ]] || reject
            emit backend.json
        fi ;;
    exec)
        if [[ "${!#}" == *PGPASSWORD=* ]]; then
            password=''; IFS= read -r -d '' password || :
            [[ "$password" == "$PERFUME_EXPECTED_DB_PASSWORD" ]] || reject
            printf 'perfume|perfume_user|170011|true|true|true|%s\n' "$mock_extension"
        else printf '%s' "$(<"$root/probe.txt")"; printf '\n'
        fi ;;
    *) reject ;;
    esac ;;
*) reject ;;
esac
'''


PYTHON_DRIVER = r'''import os, sys
sys.stdout.reconfigure(newline='\n')
args = sys.argv[1:]
with open(os.path.join(os.environ['PERFUME_TEST_DIRECTORY'], 'python-starts.log'), 'a', encoding='utf-8') as log:
    log.write(('validator' if args and args[0] == 'scripts/validate-perfume-compose.py' else 'inline') + '\n')
if args and args[0] == 'scripts/validate-perfume-compose.py':
    import runpy, subprocess
    # Run the real validator and retain every validation/CLI/exit-code path.
    if os.name == 'nt' and '--approval-file' in args:
        args[args.index('--approval-file') + 1] = os.path.join(os.environ['PERFUME_TEST_DIRECTORY'], '.env.pgvector-approval.json')
    if os.name == 'nt' and '--elasticsearch-files' in args:
        args[args.index('--deploy-directory') + 1] = os.environ['PERFUME_TEST_DIRECTORY']
    original_run = subprocess.run
    def run(command, **kwargs):
        if command[0] == 'docker':
            command = [os.environ['PERFUME_TEST_BASH'], os.environ['PERFUME_TEST_FAKE_TOOLS'], 'docker'] + command[1:]
        return original_run(command, **kwargs)
    subprocess.run = run
    sys.argv = args
    runpy.run_path(args[0], run_name='__main__')
elif args and args[0] in ('-', '-c'):
    # The previous relay started a second interpreter for every inline program.
    # Execute with ordinary Python CLI globals/argv and the existing stdin instead.
    inline = args[0] == '-c'
    code = args[1] if inline else sys.stdin.read()
    sys.argv = ['-c'] + args[2:] if inline else args
    name = '<string>' if inline else '<stdin>'
    namespace = {'__name__': '__main__', '__package__': None, '__spec__': None}
    if not inline:
        namespace['__file__'] = name
    exec(compile(code, name, 'exec'), namespace)
else:
    raise SystemExit('Unexpected mock Python invocation')
'''


def prepare(root, fixture):
    (root / "commands").mkdir()
    config = {
        "compose.json": fixture["compose"], "container.json": fixture["container"],
        "compose-base.json": fixture.get("compose_base", fixture["compose"]),
        "search-network.json": fixture.get("search_network", {}),
        "changed-container.json": dict(fixture["container"], Id="9" * 64),
        "image.json": [fixture["image"]], "digests.json": fixture["image"]["RepoDigests"],
        "backend.json": {"Entrypoint": ["java", "-jar", "/app/app.jar"], "Cmd": [
            "--server.port=8081", "--scentrev.brand-import=false", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.sql.init.mode=never", "--spring.jpa.show-sql=false"]},
    }
    for name, value in config.items():
        (root / name).write_text(json.dumps(value), encoding="utf-8")
    (root / "probe.txt").write_text(fixture["probe"], encoding="utf-8")
    scalars = {"mock_db_id": fixture["container"]["Id"], "mock_changed_db_id": "9" * 64,
               "mock_image_id": fixture["image"]["Id"], "mock_extension": fixture["extension"],
               "mock_change_id": str(int(fixture["change_id"])),
               "mock_protected_incoming": str(int(fixture["protected_incoming"])),
               "mock_protected_path": fixture.get("protected_path", ".env.pgvector-approval.json"),
               "mock_change_secret": str(int(fixture.get("change_secret", False))),
               "mock_legacy": str(int(fixture.get("legacy", False))),
               "mock_keep_legacy_guard": str(int(fixture.get("keep_legacy_guard", False))),
               "mock_elastic": str(int(fixture["compose"]["services"]["backend"]["environment"].get("PERFUME_ELASTICSEARCH_ENABLED") == "true")),
               "mock_http": str(int(fixture["compose"]["services"]["backend"]["environment"].get("PERFUME_AUTH_DEV_HTTP_ENABLED") == "true"))}
    (root / "fixture.sh").write_text("\n".join(f"{key}={shlex.quote(value)}" for key, value in scalars.items()) + "\n",
                                     encoding="utf-8", newline="\n")


def read_commands(root):
    rows = []
    for path in (root / "commands").iterdir():
        fields = path.read_bytes().split(b"\0")[:-1]
        rows.append((float(fields[0]), [field.decode("utf-8") for field in fields[1:]]))
    return [command for _, command in sorted(rows, key=lambda row: row[0])]
