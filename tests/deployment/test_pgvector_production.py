"""Production image boundaries; metadata and command mocks only, never a real DB."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_compose_guard import deployment_config, PASSWORD

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("production_verifier", ROOT / "scripts/verify-pgvector-production-image.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
guard = verifier.guard
BASE_ID, IMAGE_ID, DB_ID = "sha256:" + "a" * 64, "sha256:" + "b" * 64, "c" * 64
PACKAGE = "17.11-1.pgdg12+1"


def approval():
    return {"format": 1, "image_id": IMAGE_ID, "base_image": guard.BASE_IMAGE, "base_image_id": BASE_ID,
            "postgres_version": "17.11", "postgres_package_version": PACKAGE, "pgvector_version": "0.8.7",
            "platform": "linux/amd64", "postgres_binary_sha256": "d" * 64,
            "vector_binary_sha256": "e" * 64, "vector_control_sha256": "f" * 64}


def image(vector=False):
    labels = {"perfume.pgvector.base-image": guard.BASE_IMAGE, "perfume.pgvector.postgres-version": "17.11",
              "perfume.pgvector.extension-version": "0.8.7"} if vector else {}
    return {"Id": IMAGE_ID if vector else BASE_ID, "Os": "linux", "Architecture": "amd64",
            "RepoDigests": [] if vector else ["postgres@" + guard.BASE_DIGEST],
            "Config": {"Labels": labels, "Entrypoint": ["docker-entrypoint.sh"], "Cmd": ["postgres"],
                       "Env": ["PG_MAJOR=17", "PG_VERSION=" + PACKAGE, "PGDATA=/var/lib/postgresql/data"],
                       "Volumes": {"/var/lib/postgresql/data": {}}, "User": "", "StopSignal": "SIGINT"}}


def container(vector=False):
    data = image(vector)
    return {"Id": DB_ID, "Image": data["Id"],
            "Config": {"Labels": {"com.docker.compose.project": "perfume-dev", "com.docker.compose.service": "db"},
                       "Entrypoint": data["Config"]["Entrypoint"], "Cmd": data["Config"]["Cmd"],
                       "Env": data["Config"]["Env"] + ["POSTGRES_DB=perfume", "POSTGRES_USER=perfume_user", "POSTGRES_PASSWORD=" + PASSWORD]},
            "State": {"Running": True, "Health": {"Status": "healthy"}},
            "Mounts": [{"Type": "volume", "Name": "perfume-dev_pgdata", "Destination": "/var/lib/postgresql/data", "RW": True}],
            "NetworkSettings": {"Networks": {"perfume-dev_data": {}, "perfume-dev_db-access": {}}},
            "HostConfig": {"PortBindings": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "15432"}]}}}


def probe(vector=False):
    values = ["postgres (PostgreSQL) 17.11 (Debian " + PACKAGE + ")", "bookworm", "17", PACKAGE, "d" * 64]
    if vector:
        values += ["e" * 64, "f" * 64, "0.8.7"]
    return "\n".join(values) + "\n"


def fake_docker(arguments):
    if arguments[:2] == ["image", "inspect"]:
        return json.dumps([image(arguments[2] != guard.BASE_IMAGE and arguments[2] != BASE_ID)])
    if arguments[0] == "run":
        return probe(arguments[-3] == IMAGE_ID)
    if arguments[0] == "ps":
        return DB_ID + "\n"
    if arguments[0] == "inspect":
        return json.dumps([container()])
    if arguments[:2] == ["exec", DB_ID]:
        return "perfume|perfume_user|170011\n" if "psql" in arguments else probe()
    raise AssertionError("Unexpected Docker command: " + repr(arguments))


class ProductionImageTests(unittest.TestCase):
    def test_default_and_exact_rollback_base_remain_allowed_without_approval(self):
        for reference in ("postgres:17-bookworm", guard.BASE_IMAGE):
            config = deployment_config()
            config["services"]["db"]["image"] = reference
            guard.validate_config(config, PASSWORD)
            guard.validate_db_container(container(), image(), reference)

    def test_rollback_remains_allowed_with_saved_pgvector_approval_and_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / guard.APPROVAL_NAME
            verifier.write_approval(approval(), path)
            for reference in ("postgres:17-bookworm", guard.BASE_IMAGE):
                config = deployment_config()
                config["services"]["db"]["image"] = reference
                guard.validate_config(config, PASSWORD, db_approval=approval())
                guard.validate_db_container(container(), image(), reference, approval())
                environment = dict(os.environ, PERFUME_EXPECTED_DB_PASSWORD=PASSWORD, PYTHONDONTWRITEBYTECODE="1")
                result = subprocess.run([sys.executable, str(ROOT / "scripts/validate-perfume-compose.py"),
                                         "--approval-file", str(path)], input=json.dumps(config), env=environment,
                                        text=True, capture_output=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn(PASSWORD, result.stdout + result.stderr)
                self.assertEqual(approval(), guard.load_approval(path))

    def test_readonly_preflight_uses_only_inspection_and_compose_dry_run(self):
        def readonly_docker(arguments, environment=None):
            if arguments[0] == "compose":
                self.assertNotIn("DB_PASSWORD", environment)
                self.assertNotIn("SCENTREV_API_KEY", environment)
                if "config" in arguments:
                    config = deployment_config()
                    config["services"]["backend"]["build"]["context"] = str(ROOT)
                    config["services"]["frontend"]["build"]["context"] = str(ROOT / "frontend")
                    config["services"]["db"]["image"] = environment.get("PERFUME_DB_IMAGE", "postgres:17-bookworm")
                    return json.dumps(config)
                self.assertIn("--dry-run", arguments)
                self.assertEqual(["up", "--detach", "--no-deps", "--no-build", "--pull", "never", "db"],
                                 arguments[arguments.index("up"):])
                self.assertEqual(IMAGE_ID, environment["PERFUME_DB_IMAGE"])
                return "DRY-RUN: db would be recreated\n"
            if arguments[0] == "exec" and "psql" in arguments:
                self.assertIn("BEGIN READ ONLY", arguments[-1])
                return "BEGIN\nperfume|perfume_user|170011|none\nCOMMIT\n"
            return fake_docker(arguments)
        with patch.object(guard, "load_approval", return_value=approval()), \
             patch.object(guard, "docker_output", side_effect=readonly_docker) as calls, \
             patch.object(verifier, "probe_image", side_effect=AssertionError("No new probe containers")), \
             patch.object(verifier, "write_approval", side_effect=AssertionError("No approval write")):
            self.assertEqual(IMAGE_ID, verifier.readonly_preflight())
        commands = [call.args[0] for call in calls.call_args_list]
        self.assertFalse(any(cmd[0] in ("run", "create", "pull", "tag", "push", "stop", "restart", "rm", "volume", "network") for cmd in commands))
        self.assertEqual(1, sum(cmd[0] == "compose" and "--dry-run" in cmd for cmd in commands))

    def test_readonly_preflight_fails_without_approval_or_supported_dry_run(self):
        with patch.object(guard, "load_approval", return_value=None), patch.object(guard, "docker_output") as calls:
            with self.assertRaises(ValueError):
                verifier.readonly_preflight()
            calls.assert_not_called()
        def unsupported(arguments, environment=None):
            if arguments[0] == "compose":
                if "--dry-run" in arguments:
                    raise ValueError("Unsupported --dry-run")
                config = deployment_config()
                config["services"]["backend"]["build"]["context"] = str(ROOT)
                config["services"]["frontend"]["build"]["context"] = str(ROOT / "frontend")
                config["services"]["db"]["image"] = environment.get("PERFUME_DB_IMAGE", "postgres:17-bookworm")
                return json.dumps(config)
            if arguments[0] == "exec" and "psql" in arguments:
                return "perfume|perfume_user|170011|none\n"
            return fake_docker(arguments)
        with patch.object(guard, "load_approval", return_value=approval()), \
             patch.object(guard, "docker_output", side_effect=unsupported) as calls:
            with self.assertRaises(ValueError):
                verifier.readonly_preflight()
        self.assertFalse(any("up" in call.args[0] and "--dry-run" not in call.args[0] for call in calls.call_args_list))

    def test_pgvector_requires_manual_approval_and_full_image_id(self):
        config = deployment_config()
        config["services"]["db"]["image"] = IMAGE_ID
        with self.assertRaises(ValueError):
            guard.validate_config(config, PASSWORD)
        guard.validate_config(config, PASSWORD, db_approval=approval())
        for reference in ("postgres:latest", "postgres:17.11", verifier.REHEARSAL_IMAGE,
                          "perfume-postgres:pg17.11-vector0.8.7", "sha256:" + "1" * 64,
                          "pgvector/pgvector:pg17", "postgres:17-bookworm@sha256:" + "2" * 64):
            with self.subTest(reference=reference):
                config["services"]["db"]["image"] = reference
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD, db_approval=approval())

    def test_approval_cannot_change_the_base_pg_or_vector_version(self):
        for key, value in (("base_image", "postgres:17-bookworm"), ("postgres_version", "17.12"),
                           ("pgvector_version", "0.8.8"), ("image_id", "sha256:abcd"),
                           ("postgres_package_version", "18.1-1"), ("postgres_binary_sha256", "bad")):
            with self.subTest(key=key):
                invalid = approval()
                invalid[key] = value
                with self.assertRaises(ValueError):
                    guard.validate_approval(invalid)

    def test_manual_approval_file_is_exclusive_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / guard.APPROVAL_NAME
            self.assertIsNone(guard.load_approval(path))
            verifier.write_approval(approval(), path)
            self.assertEqual(approval(), guard.load_approval(path))
            with self.assertRaises(FileExistsError):
                verifier.write_approval(approval(), path)
            with patch.object(Path, "is_symlink", return_value=True):
                with self.assertRaises(ValueError):
                    guard.load_approval(path)

    def test_approved_running_image_is_checked_by_id_not_by_tag(self):
        guard.validate_db_container(container(True), image(True), IMAGE_ID, approval())
        for kind in ("id", "base_label", "platform", "package", "entrypoint"):
            with self.subTest(kind=kind):
                data = image(True)
                if kind == "id":
                    data["Id"] = "sha256:" + "1" * 64
                elif kind == "base_label":
                    data["Config"]["Labels"]["perfume.pgvector.base-image"] = "postgres:17-bookworm"
                elif kind == "platform":
                    data["Architecture"] = "arm64"
                elif kind == "package":
                    data["Config"]["Env"][1] = "PG_VERSION=17.12-1"
                else:
                    data["Config"]["Entrypoint"] = ["sh", "-c"]
                with self.assertRaises(ValueError):
                    guard.validate_db_container(container(True), data, IMAGE_ID, approval())

    def test_running_db_volume_network_and_pgdata_cannot_change(self):
        for kind in ("volume", "network", "pgdata", "role", "port", "project"):
            with self.subTest(kind=kind):
                data = container(True)
                if kind == "volume":
                    data["Mounts"][0]["Name"] = "stock-db-data"
                elif kind == "network":
                    data["NetworkSettings"]["Networks"]["stock-network"] = {}
                elif kind == "pgdata":
                    data["Config"]["Env"] += ["PGDATA=/other"]
                elif kind == "role":
                    data["Config"]["Env"] += ["POSTGRES_USER=other"]
                elif kind == "port":
                    data["HostConfig"]["PortBindings"]["5432/tcp"][0]["HostIp"] = "0.0.0.0"
                else:
                    data["Config"]["Labels"]["com.docker.compose.project"] = "stock-dividend"
                with self.assertRaises(ValueError):
                    guard.validate_db_container(data, image(True), IMAGE_ID, approval())

    def test_different_running_base_digest_or_minor_is_rejected(self):
        data = image()
        data["RepoDigests"] = ["postgres@sha256:" + "1" * 64]
        with self.assertRaises(ValueError):
            guard.validate_db_container(container(), data, "postgres:17-bookworm")
        for bad in (probe().replace("17.11", "17.12"), "", probe(True).replace("0.8.7", "0.8.8"),
                    probe(True).replace("e" * 64, "1" * 64)):
            with self.assertRaises(ValueError):
                guard.validate_probe(bad, len(bad.splitlines()) == 8, approval())

    def test_db_build_hooks_and_environment_overrides_are_rejected(self):
        for change in ({"build": {"context": "docker/pgvector"}}, {"pull_policy": "always"},
                       {"post_start": [{"command": "psql"}]}):
            config = deployment_config()
            config["services"]["db"].update(change)
            with self.assertRaises(ValueError):
                guard.validate_config(config, PASSWORD)
        config = deployment_config()
        config["services"]["db"]["environment"]["PGDATA"] = "/other"
        with self.assertRaises(ValueError):
            guard.validate_config(config, PASSWORD)

    def test_verifier_resolves_once_and_probes_immutable_ids_without_pgdata_mounts(self):
        with patch.object(guard, "docker_output", side_effect=fake_docker) as calls:
            self.assertEqual(approval(), verifier.verify_candidate())
        commands = [call.args[0] for call in calls.call_args_list]
        runs = [cmd for cmd in commands if cmd[0] == "run"]
        self.assertEqual(2, len(runs))
        self.assertEqual({BASE_ID, IMAGE_ID}, {cmd[-3] for cmd in runs})
        for command in runs:
            self.assertIn("--read-only", command)
            self.assertIn("none", command)
            self.assertNotIn("--volume", command)
            self.assertNotIn("-v", command)
            self.assertEqual("sh", command[command.index("--entrypoint") + 1])
        self.assertFalse(any(cmd[0] in ("pull", "tag", "push", "stop", "restart", "rm") for cmd in commands))

    def test_candidate_with_modified_postgres_binary_or_runtime_defaults_is_rejected(self):
        for kind in ("binary", "entrypoint", "label"):
            with self.subTest(kind=kind):
                def changed(arguments):
                    value = fake_docker(arguments)
                    if kind == "binary" and arguments[0] == "run" and arguments[-3] == IMAGE_ID:
                        return value.replace("d" * 64, "1" * 64)
                    if kind in ("entrypoint", "label") and arguments[:2] == ["image", "inspect"] and arguments[2] == verifier.REHEARSAL_IMAGE:
                        data = json.loads(value)
                        if kind == "entrypoint":
                            data[0]["Config"]["Entrypoint"] = ["sh"]
                        else:
                            data[0]["Config"]["Labels"]["perfume.pgvector.extension-version"] = "0.8.8"
                        return json.dumps(data)
                    return value
                with patch.object(guard, "docker_output", side_effect=changed):
                    with self.assertRaises(ValueError):
                        verifier.verify_candidate()

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI required; no daemon used.")
    def test_real_compose_default_and_image_id_override_without_engine(self):
        env = dict(os.environ)
        for key in ("PERFUME_DB_IMAGE", "DB_NAME", "DB_USERNAME", "COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES"):
            env.pop(key, None)
        env["DB_PASSWORD"] = PASSWORD
        for reference in (None, IMAGE_ID, guard.BASE_IMAGE):
            if reference:
                env["PERFUME_DB_IMAGE"] = reference
            command = ["docker", "compose", "--project-name", "perfume-dev", "--project-directory", str(ROOT),
                       "--env-file", str(ROOT / ".env.example"), "--file", str(ROOT / "compose.yaml"), "config", "--format", "json"]
            result = subprocess.run(command, env=env, capture_output=True, text=True, encoding="utf-8", check=True, timeout=30)
            config = json.loads(result.stdout)
            self.assertEqual(reference or "postgres:17-bookworm", config["services"]["db"]["image"])
            self.assertEqual("perfume-dev_pgdata", config["volumes"]["pgdata"]["name"])
            guard.validate_config(config, PASSWORD, str(ROOT), approval() if reference else None)


if __name__ == "__main__":
    unittest.main()
