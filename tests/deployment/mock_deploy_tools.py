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
        'reset --hard') cp -- "$PERFUME_TEST_GUARD_SOURCE" "$root/scripts/validate-perfume-compose.py" ;;
        'fetch --no-tags'|'cat-file -e'|'merge-base --is-ancestor') : ;;
        'ls-tree -r') if [[ "$mock_protected_incoming" == 1 ]]; then printf '.env.pgvector-approval.json\0'; fi ;;
        *) reject ;;
    esac ;;
curl|flock) : ;;
docker)
    case "$1" in
    compose)
        for key in PERFUME_AI_SEARCH_ENABLED PERFUME_AI_SEARCH_MODEL_DIGEST PERFUME_AI_RECOMMEND_ENABLED PERFUME_AI_RECOMMEND_MODEL_DIGEST PERFUME_AUTH_ENABLED PERFUME_AUTH_COOKIE_SECURE PERFUME_AUTH_DEV_HTTP_ENABLED PERFUME_AUTH_DEV_HTTP_ORIGIN SPRING_PROFILES_ACTIVE; do
            [[ ! -v "$key" ]] || reject
        done
        http_override=0; command=''; quiet=0
        for arg in "$@"; do
            case "$arg" in
                */compose.auth-dev-http.yaml) http_override=1 ;;
                config|build|up|ps) command="$arg" ;;
                --quiet) quiet=1 ;;
            esac
        done
        [[ "$http_override" == "$mock_http" ]] || reject
        case "$command" in
        config) if [[ "$quiet" == 0 ]]; then emit compose.json; fi ;;
        build) [[ "${@: -2}" == 'backend frontend' ]] || reject ;;
        up)
            [[ " $* " == *' --no-deps '* && " $* " == *' --no-build '* ]] || reject
            [[ "${!#}" == backend || "${!#}" == frontend ]] || reject
            if [[ "$mock_change_id" == 1 ]]; then : >"$root/changed-db"; fi ;;
        ps) : ;;
        *) reject ;;
        esac ;;
    volume) [[ "$*" == 'volume inspect perfume-dev_pgdata' ]] || reject ;;
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
               "mock_http": str(int(fixture["compose"]["services"]["backend"]["environment"].get("PERFUME_AUTH_DEV_HTTP_ENABLED") == "true"))}
    (root / "fixture.sh").write_text("\n".join(f"{key}={shlex.quote(value)}" for key, value in scalars.items()) + "\n",
                                     encoding="utf-8", newline="\n")


def read_commands(root):
    rows = []
    for path in (root / "commands").iterdir():
        fields = path.read_bytes().split(b"\0")[:-1]
        rows.append((float(fields[0]), [field.decode("utf-8") for field in fields[1:]]))
    return [command for _, command in sorted(rows, key=lambda row: row[0])]
