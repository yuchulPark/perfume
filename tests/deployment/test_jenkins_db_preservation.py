"""Run the real deploy shell in a temporary checkout with every external action mocked."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_compose_guard import deployment_config, PASSWORD, ai_environment
from test_pgvector_production import ROOT, DB_ID, IMAGE_ID, approval, container, image, probe

BASH = str(Path("C:/Program Files/Git/bin/bash.exe")) if os.name == "nt" else shutil.which("bash")

# Executed only in a disposable test directory. No real Docker, Git, curl or SSH.
FAKE_TOOLS = r'''
import json, os, shutil, sys
from pathlib import Path
sys.stdout.reconfigure(newline='\n')
tool, args = sys.argv[1], sys.argv[2:]
root = Path(os.environ['PERFUME_TEST_DIRECTORY'])
with (root / 'commands.jsonl').open('a', encoding='utf-8') as log:
    log.write(json.dumps([tool] + args) + '\n')
data = json.loads((root / 'fixture.json').read_text())
if tool == 'git':
    if args[:2] == ['rev-parse', '--show-toplevel']:
        print(os.environ['PERFUME_TEST_POSIX_DIRECTORY'])
    elif args[:3] == ['remote', 'get-url', 'origin']:
        print('https://github.com/yuchulPark/perfume.git')
    elif args[:2] == ['ls-files', '--error-unmatch']:
        sys.exit(1)
    elif args[:2] == ['reset', '--hard']:
        shutil.copyfile(os.environ['PERFUME_TEST_GUARD_SOURCE'], root / 'scripts/validate-perfume-compose.py')
    elif args and args[0] == 'ls-tree' and data['protected_incoming']:
        sys.stdout.buffer.write(b'.env.pgvector-approval.json\0')
    sys.exit(0)
if tool in ('curl', 'flock'):
    sys.exit(0)
assert tool == 'docker', tool
changed = (root / 'changed-db').exists()
db_id = '9' * 64 if changed else data['container']['Id']
if args[0] == 'compose':
    assert not any(key in os.environ for key in (
        'PERFUME_AI_SEARCH_ENABLED', 'PERFUME_AI_SEARCH_MODEL_DIGEST',
        'PERFUME_AI_RECOMMEND_ENABLED', 'PERFUME_AI_RECOMMEND_MODEL_DIGEST'))
    command = next(x for x in args[1:] if x in ('config', 'build', 'up', 'ps'))
    if command == 'config' and '--quiet' not in args:
        print(json.dumps(data['compose']))
    if command == 'build':
        assert args[-2:] == ['backend', 'frontend']
    if command == 'up':
        assert '--no-deps' in args and '--no-build' in args
        assert args[-1] in ('backend', 'frontend')
        if data['change_id']:
            (root / 'changed-db').touch()
elif args[0] == 'volume':
    assert args == ['volume', 'inspect', 'perfume-dev_pgdata']
elif args[0] == 'ps':
    assert 'label=com.docker.compose.project=perfume-dev' in args
    assert 'label=com.docker.compose.service=db' in args
    print(db_id)
elif args[0] == 'inspect':
    template = args[args.index('--format') + 1]
    if template == '{{.State.Running}}':
        print('true')
    elif template == '{{.State.Health.Status}}':
        print('healthy')
    elif template == '{{.Image}}':
        print(data['image']['Id'])
    elif template == '{{json .}}':
        metadata = dict(data['container'], Id=db_id)
        print(json.dumps(metadata))
    else:
        assert '.Mounts' in template
        print('volume perfume-dev_pgdata')
elif args[0] == 'port':
    print('127.0.0.1:15432')
elif args[:2] == ['image', 'inspect']:
    if '--format' not in args:
        print(json.dumps([data['image']]))
    elif args[args.index('--format') + 1] == '{{json .RepoDigests}}':
        print(json.dumps(data['image']['RepoDigests']))
    else:
        assert args[-1] == 'perfume-dev-backend'
        print(json.dumps({'Entrypoint': ['java', '-jar', '/app/app.jar'], 'Cmd': [
            '--server.port=8081', '--scentrev.brand-import=false', '--spring.jpa.hibernate.ddl-auto=validate',
            '--spring.sql.init.mode=never', '--spring.jpa.show-sql=false']}))
elif args[0] == 'exec':
    if 'PGPASSWORD=' in args[-1]:
        assert sys.stdin.read() == os.environ['PERFUME_EXPECTED_DB_PASSWORD']
        print('perfume|perfume_user|170011|true|true|true|' + data['extension'])
    else:
        print(data['probe'], end='')
else:
    raise AssertionError('Forbidden/unexpected Docker operation: ' + repr(args))
'''

PYTHON_DRIVER = r'''
import os, runpy, subprocess, sys
sys.stdout.reconfigure(newline='\n')
args = sys.argv[1:]
if args and args[0] == 'scripts/validate-perfume-compose.py':
    # Native Windows Python does not interpret Git Bash /c/... file paths.
    if os.name == 'nt' and '--approval-file' in args:
        index = args.index('--approval-file') + 1
        args[index] = os.path.join(os.environ['PERFUME_TEST_DIRECTORY'], '.env.pgvector-approval.json')
    original_run = subprocess.run
    def run(command, **kwargs):
        if command[0] == 'docker':
            command = [sys.executable, os.environ['PERFUME_TEST_FAKE_TOOLS'], 'docker'] + command[1:]
        return original_run(command, **kwargs)
    subprocess.run = run
    sys.argv = args
    runpy.run_path(args[0], run_name='__main__')
else:
    sys.exit(subprocess.call([sys.executable] + args))
'''

LEGACY_GUARD = '''import argparse, json, sys
parser = argparse.ArgumentParser()
parser.add_argument('--deploy-directory')
parser.parse_args()
config = json.load(sys.stdin)
assert config['services']['db']['image'] == 'postgres:17-bookworm'
print('Legacy default-image validation passed.')
'''


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash is required for mocked deploy-script execution.")
class JenkinsDbPreservationTests(unittest.TestCase):
    def run_deploy(self, vector=False, legacy=False, change_id=False, protected_incoming=False, extension="none", reference=None, approved=False, embedding_marker=False, ai_inputs=None):
        with tempfile.TemporaryDirectory(prefix="perfume-deploy-mock-") as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "fake-bin").mkdir()
            # Git Bash paths are needed only by the shell; all native Python paths stay absolute.
            env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*", MSYS2_ENV_CONV_EXCL="PERFUME_TEST_POSIX_DIRECTORY",
                       PYTHONDONTWRITEBYTECODE="1")
            posix_directory = subprocess.run([BASH, "--noprofile", "--norc", "-c", 'cd "$1" && pwd -P', "test", directory],
                                             env=env, text=True, capture_output=True, check=True).stdout.strip()
            config = deployment_config(ai_environment(ai_inputs) if ai_inputs is not None else None)
            config["services"]["backend"]["build"]["context"] = posix_directory
            config["services"]["frontend"]["build"]["context"] = posix_directory + "/frontend"
            config["services"]["db"]["image"] = reference or (IMAGE_ID if vector else "postgres:17-bookworm")
            fixture = {"compose": config, "container": container(vector), "image": image(vector), "probe": probe(vector),
                       "change_id": change_id, "protected_incoming": protected_incoming, "extension": extension}
            (root / "fixture.json").write_text(json.dumps(fixture), encoding="utf-8")
            (root / ".env").write_text("DB_PASSWORD='offline-test-password'\n", encoding="utf-8")
            (root / ".perfume-db-ready").write_text("perfume-dev_pgdata\n", encoding="utf-8", newline="\n")
            if embedding_marker:
                (root / ".perfume-embedding-active").write_text("offline-owner\n", encoding="utf-8")
            if vector or approved:
                from test_pgvector_production import verifier
                verifier.write_approval(approval(), root / ".env.pgvector-approval.json")
            guard_source = ROOT / "scripts/validate-perfume-compose.py"
            (root / "scripts/validate-perfume-compose.py").write_text(
                LEGACY_GUARD if legacy else guard_source.read_text(encoding="utf-8"), encoding="utf-8")
            script = (ROOT / "scripts/jenkins-deploy.sh").read_text(encoding="utf-8").replace(
                "deploy_directory=/srv/perfume", "deploy_directory='" + posix_directory + "'")
            # Git Bash adjusts inherited Windows PATH; prepend mocks inside Bash itself.
            script = "export PATH='" + posix_directory + "/fake-bin':\"$PATH\"\n" + script
            script = "set -E\ntrap 'echo Mock-deploy-failure-at-line-$LINENO >&2' ERR\n" + script
            (root / "deploy.sh").write_text(script, encoding="utf-8", newline="\n")
            (root / "fake-tools.py").write_text(FAKE_TOOLS, encoding="utf-8")
            (root / "python-driver.py").write_text(PYTHON_DRIVER, encoding="utf-8")
            env.update(PERFUME_TEST_DIRECTORY=directory, PERFUME_TEST_POSIX_DIRECTORY=posix_directory,
                       PERFUME_TEST_PYTHON=sys.executable, PERFUME_TEST_FAKE_TOOLS=str(root / "fake-tools.py"),
                       PERFUME_TEST_PYTHON_DRIVER=str(root / "python-driver.py"), PERFUME_TEST_GUARD_SOURCE=str(guard_source),
                       PERFUME_EXPECTED_DB_PASSWORD=PASSWORD)
            # SSH session values must never override the operator-managed Compose .env inputs.
            env.update(PERFUME_AI_SEARCH_ENABLED="true", PERFUME_AI_SEARCH_MODEL_DIGEST="invalid-inherited",
                       PERFUME_AI_RECOMMEND_ENABLED="true", PERFUME_AI_RECOMMEND_MODEL_DIGEST="invalid-inherited")
            env["PATH"] = str(root / "fake-bin") + os.pathsep + env["PATH"]
            for name in ("docker", "git", "curl", "flock", "python3"):
                body = ('exec "$PERFUME_TEST_PYTHON" "$PERFUME_TEST_PYTHON_DRIVER" "$@"' if name == "python3" else
                        'exec "$PERFUME_TEST_PYTHON" "$PERFUME_TEST_FAKE_TOOLS" ' + name + ' "$@"')
                path = root / "fake-bin" / name
                path.write_text("#!/usr/bin/env bash\n" + body + "\n", encoding="utf-8", newline="\n")
                path.chmod(0o755)
            result = subprocess.run([BASH, "--noprofile", "--norc", str(root / "deploy.sh"), "deploy", "1" * 40],
                                    env=env, text=True, capture_output=True, timeout=60)
            log = root / "commands.jsonl"
            commands = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines()] if log.exists() else []
            self.assertNotIn(PASSWORD, result.stdout + result.stderr)
            self.assertEqual("perfume-dev_pgdata\n", (root / ".perfume-db-ready").read_text())
            self.assertEqual("DB_PASSWORD='offline-test-password'\n", (root / ".env").read_text())
            if vector or approved:
                self.assertEqual(approval(), json.loads((root / ".env.pgvector-approval.json").read_text()))
            return result, commands

    def test_orphan_embedding_marker_refuses_deploy_before_git_or_docker(self):
        result, commands = self.run_deploy(embedding_marker=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Embedding worker recovery is required", result.stderr)
        self.assertFalse(any(cmd[0] in ('git', 'docker') for cmd in commands))

    def test_default_pgvector_and_first_upgrade_deploy_only_the_apps(self):
        for vector, legacy, extension in ((False, False, "none"), (False, True, "none"),
                                          (True, False, "none"), (True, False, "0.8.7")):
            with self.subTest(vector=vector, legacy=legacy, extension=extension):
                result, commands = self.run_deploy(vector=vector, legacy=legacy, extension=extension)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                up = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"] and "up" in cmd]
                self.assertEqual(["backend", "frontend"], [cmd[-1] for cmd in up])
                self.assertTrue(all("--no-deps" in cmd and "--no-build" in cmd for cmd in up))
                self.assertFalse(any(cmd[:2] in (["docker", "run"], ["docker", "pull"], ["docker", "stop"],
                                                 ["docker", "restart"], ["docker", "rm"]) for cmd in commands))
                self.assertIn("DB container, volume and .env preserved", result.stdout)

    def test_unapproved_image_and_incoming_approval_file_abort_before_app_deploy(self):
        for options in ({"reference": "perfume-postgres:latest"}, {"protected_incoming": True}, {"vector": True, "extension": "0.8.8"}):
            with self.subTest(options=options):
                result, commands = self.run_deploy(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(cmd[:2] == ["docker", "compose"] and "up" in cmd for cmd in commands))

    def test_rollback_with_saved_approval_still_deploys_only_apps(self):
        from test_pgvector_production import guard
        result, commands = self.run_deploy(reference=guard.BASE_IMAGE, approved=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        up = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"] and "up" in cmd]
        self.assertEqual(["backend", "frontend"], [cmd[-1] for cmd in up])
        self.assertTrue(all("--no-deps" in cmd and "--no-build" in cmd for cmd in up))
        self.assertIn("DB container, volume and .env preserved", result.stdout)

    def test_changed_db_id_causes_deployment_failure(self):
        result, commands = self.run_deploy(change_id=True)
        self.assertNotEqual(0, result.returncode)
        up = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"] and "up" in cmd]
        self.assertEqual(2, len(up))
        self.assertTrue(all(cmd[-1] in ("backend", "frontend") for cmd in up))

    def test_ai_disabled_and_enabled_deploys_preserve_db_and_only_replace_apps(self):
        for enabled in ("false", "true"):
            with self.subTest(enabled=enabled):
                inputs = {"PERFUME_AI_SEARCH_ENABLED": enabled, "PERFUME_AI_SEARCH_MODEL_DIGEST": "a" * 64 if enabled == "true" else "",
                          "PERFUME_AI_RECOMMEND_ENABLED": enabled, "PERFUME_AI_RECOMMEND_MODEL_DIGEST": "b" * 64 if enabled == "true" else ""}
                result, commands = self.run_deploy(vector=True, extension="0.8.7", ai_inputs=inputs)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                up = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"] and "up" in cmd]
                self.assertEqual(["backend", "frontend"], [cmd[-1] for cmd in up])
                self.assertTrue(all("--no-deps" in cmd and "--no-build" in cmd for cmd in up))
                self.assertIn("DB container, volume and .env preserved", result.stdout)

    def test_invalid_enabled_ai_config_stops_before_fetch_build_or_app_replacement(self):
        for digest in ("", "invalid-digest"):
            with self.subTest(digest=digest):
                result, commands = self.run_deploy(ai_inputs={"PERFUME_AI_SEARCH_ENABLED": "true", "PERFUME_AI_SEARCH_MODEL_DIGEST": digest})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(cmd[:2] == ["git", "fetch"] for cmd in commands))
                self.assertFalse(any(cmd[:2] == ["docker", "compose"] and any(action in cmd for action in ("build", "up")) for cmd in commands))
