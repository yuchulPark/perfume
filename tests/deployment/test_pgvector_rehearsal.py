"""Offline boundary tests: no Docker engine, server connection or real volume operations."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("pgvector_rehearsal", ROOT / "scripts/pgvector-rehearsal.py")
rehearsal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rehearsal)
BASE = "postgres:17-bookworm@sha256:" + "a" * 64
PASSWORD = "offline-only-password-never-print"


def config():
    return {
        "name": rehearsal.PROJECT,
        "volumes": {"rehearsal-pgdata": {"name": rehearsal.VOLUME}},
        "networks": {"rehearsal": {"name": rehearsal.NETWORK, "internal": True}},
        "services": {rehearsal.SERVICE: {
            "image": rehearsal.PROJECT + "-db:pg17.6-vector0.8.7",
            "build": {"context": str(ROOT / "docker/pgvector"), "dockerfile": "Dockerfile",
                      "args": {"POSTGRES_BASE_IMAGE": BASE, "POSTGRES_SERVER_VERSION": "17.6"}},
            "restart": "no", "cpus": 1.0, "mem_limit": 2 * 1024**3,
            "environment": {"POSTGRES_DB": rehearsal.DATABASE, "POSTGRES_USER": rehearsal.ADMIN,
                            "POSTGRES_PASSWORD": PASSWORD, "POSTGRES_INITDB_ARGS": "--encoding=UTF8 --locale=en_US.utf8",
                            "REHEARSAL_EXPECTED_SERVER_VERSION_NUM": "170006"},
            "volumes": [{"type": "volume", "source": "rehearsal-pgdata", "target": "/var/lib/postgresql/data"}],
            "networks": {"rehearsal": None},
        }},
    }


def container():
    return {"Id": "b" * 64, "Image": "sha256:" + "c" * 64,
            "Config": {"Labels": {"com.docker.compose.project": rehearsal.PROJECT, "com.docker.compose.service": rehearsal.SERVICE}},
            "Mounts": [{"Type": "volume", "Name": rehearsal.VOLUME, "Destination": "/var/lib/postgresql/data"}],
            "NetworkSettings": {"Networks": {rehearsal.NETWORK: {}}}, "HostConfig": {}}


def resource(kind):
    return {"Name": rehearsal.VOLUME if kind == "volume" else rehearsal.NETWORK,
            "Id": "test-network-id", "Driver": "local" if kind == "volume" else "bridge", "Internal": True,
            "Labels": {"com.docker.compose.project": rehearsal.PROJECT,
                       "com.docker.compose." + kind: "rehearsal-pgdata" if kind == "volume" else "rehearsal"}}


def manifest():
    return {("manifest", "complete"): 1, ("server", "version"): "170006", ("inventory", "tables"): 2,
            ("database", "properties"): {"owner": "perfume_user", "acl": ["perfume_user=CTc/perfume_user"]},
            ("data", "public.perfumes"): {"rows": 3, "sha256": "perfume-hash"},
            ("data", "public.brands"): {"rows": 1, "sha256": "brand-hash"},
            ("sequence_state", "public.perfumes_id_seq"): {"last_value": 10, "is_called": True},
            ("relation", "public.perfumes"): {"owner": "perfume_user", "acl": ["reader=r/perfume_user"]}}


class RehearsalBoundaryTests(unittest.TestCase):
    def test_valid_fixed_config(self):
        rehearsal.validate_config(config())

    def test_production_or_stock_volume_and_external_storage_are_rejected(self):
        for change in ({"name": "perfume-dev_pgdata"}, {"name": "stock_pgdata"}, {"external": True},
                       {"driver_opts": {"device": "/srv/postgres", "type": "none", "o": "bind"}}):
            with self.subTest(change=change):
                value = config()
                value["volumes"]["rehearsal-pgdata"].update(change)
                with self.assertRaises(ValueError):
                    rehearsal.validate_config(value)

    def test_shared_network_ports_mounts_and_extra_services_are_rejected(self):
        for key, change in (("ports", [{"target": 5432, "published": "15432"}]),
                            ("network_mode", "host"), ("networks", {"data": None}),
                            ("volumes", [{"type": "bind", "source": "/var/lib/docker/volumes/perfume-dev_pgdata", "target": "/var/lib/postgresql/data"}]),
                            ("command", ["postgres"]), ("depends_on", {"db": {"condition": "service_started"}})):
            with self.subTest(key=key):
                value = config()
                value["services"][rehearsal.SERVICE][key] = change
                with self.assertRaises(ValueError):
                    rehearsal.validate_config(value)
        value = config()
        value["services"]["backend"] = {}
        with self.assertRaises(ValueError):
            rehearsal.validate_config(value)

    def test_floating_images_wrong_major_or_minor_and_placeholders_are_rejected(self):
        for base, version, number in (("postgres:17-bookworm", "17.6", "170006"),
                                     ("postgres:18-bookworm@sha256:" + "a" * 64, "18.1", "180001"),
                                     (BASE, "17.6", "170007"), (BASE, "17.REPLACE", "REPLACE")):
            with self.subTest(base=base, version=version, number=number):
                value = config()
                db = value["services"][rehearsal.SERVICE]
                db["build"]["args"].update(POSTGRES_BASE_IMAGE=base, POSTGRES_SERVER_VERSION=version)
                db["environment"]["REHEARSAL_EXPECTED_SERVER_VERSION_NUM"] = number
                with self.assertRaises(ValueError):
                    rehearsal.validate_config(value)

    def test_production_database_role_or_init_overrides_are_rejected(self):
        for change in ({"POSTGRES_DB": "perfume"}, {"POSTGRES_USER": "perfume_user"},
                       {"PGDATA": "/other"}, {"POSTGRES_HOST_AUTH_METHOD": "trust"},
                       {"POSTGRES_INITDB_ARGS": "--locale=en_US.utf8 --auth=trust"}):
            with self.subTest(change=change):
                value = config()
                value["services"][rehearsal.SERVICE]["environment"].update(change)
                with self.assertRaises(ValueError):
                    rehearsal.validate_config(value)

    def test_resource_ownership_is_checked_not_just_its_name(self):
        for kind in ("volume", "network"):
            metadata = resource(kind)
            name = metadata["Name"]
            rehearsal.validate_resource(metadata, kind, name)
            metadata["Labels"]["com.docker.compose.project"] = "perfume-dev"
            with self.assertRaises(ValueError):
                rehearsal.validate_resource(metadata, kind, name)

    def test_real_container_production_mount_or_network_is_rejected(self):
        rehearsal.validate_container(container())
        for change in ("project", "mount", "network", "port"):
            with self.subTest(change=change):
                metadata = container()
                if change == "project":
                    metadata["Config"]["Labels"]["com.docker.compose.project"] = "perfume-dev"
                elif change == "mount":
                    metadata["Mounts"][0]["Name"] = "perfume-dev_pgdata"
                elif change == "network":
                    metadata["NetworkSettings"]["Networks"]["stock_data"] = {}
                else:
                    metadata["HostConfig"]["PortBindings"] = {"5432/tcp": [{"HostPort": "15432"}]}
                with self.assertRaises(ValueError):
                    rehearsal.validate_container(metadata)

    def test_image_provenance_must_match_the_confirmed_base_digest_and_version(self):
        instance = rehearsal.Rehearsal.__new__(rehearsal.Rehearsal)
        instance.db = config()["services"][rehearsal.SERVICE]
        labels = {"perfume.pgvector.base-image": BASE, "perfume.pgvector.postgres-version": "17.6",
                  "perfume.pgvector.extension-version": "0.8.7"}
        instance.run = Mock(return_value=json.dumps([{"Config": {"Labels": labels}}]))
        instance.check_image("test-image")
        for key in labels:
            invalid = dict(labels, **{key: "incorrect"})
            instance.run = Mock(return_value=json.dumps([{"Config": {"Labels": invalid}}]))
            with self.assertRaises(ValueError):
                instance.check_image("test-image")

    def test_cleanup_aborts_before_removal_if_another_container_uses_test_resources(self):
        instance = rehearsal.Rehearsal.__new__(rehearsal.Rehearsal)
        instance.container = Mock(return_value=container())
        instance.resource = Mock(side_effect=lambda kind, name: resource(kind))
        instance.run = Mock(return_value="foreign-container-id\n")
        with self.assertRaises(ValueError):
            instance.cleanup(True)
        self.assertFalse(any("rm" in call.args[0] for call in instance.run.call_args_list))

    def test_cleanup_removes_only_exact_test_resources_and_preserves_volume_by_default(self):
        for delete in (False, True):
            with self.subTest(delete=delete):
                instance = rehearsal.Rehearsal.__new__(rehearsal.Rehearsal)
                instance.container = Mock(return_value=container())
                instance.resource = Mock(side_effect=lambda kind, name: resource(kind))
                instance.run = Mock(return_value=container()["Id"] + "\n")
                instance.cleanup(delete)
                removals = [call.args[0] for call in instance.run.call_args_list if "rm" in call.args[0]]
                expected = [["docker", "rm", "--force", container()["Id"]], ["docker", "network", "rm", "test-network-id"]]
                if delete:
                    expected.append(["docker", "volume", "rm", rehearsal.VOLUME])
                self.assertEqual(expected, removals)

    def test_equal_counts_but_changed_data_permissions_or_sequence_state_fail(self):
        self.assertEqual(2, rehearsal.compare_manifests(manifest(), copy.deepcopy(manifest())))
        for key, field, value in ((("data", "public.perfumes"), "sha256", "other-hash"),
                                  (("sequence_state", "public.perfumes_id_seq"), "is_called", False),
                                  (("relation", "public.perfumes"), "owner", "other-owner"),
                                  (("relation", "public.perfumes"), "acl", [])):
            with self.subTest(key=key, field=field):
                clone = manifest()
                clone[key][field] = value
                with self.assertRaises(ValueError):
                    rehearsal.compare_manifests(manifest(), clone)
        clone = manifest()
        clone[("inventory", "tables")] = 3
        with self.assertRaises(ValueError):
            rehearsal.compare_manifests(manifest(), clone)

    def test_manifest_duplicate_or_incomplete_exports_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.jsonl"
            lines = [json.dumps({"kind": kind, "key": key, "value": value}) for (kind, key), value in manifest().items()]
            path.write_text("\n".join(lines), encoding="utf-8")
            rehearsal.read_manifest(path)
            path.write_text("\n".join(lines[1:]), encoding="utf-8")
            with self.assertRaises(ValueError):
                rehearsal.read_manifest(path)
            path.write_text("\n".join(lines + lines[:1]), encoding="utf-8")
            with self.assertRaises(ValueError):
                rehearsal.read_manifest(path)

    def test_restore_keeps_owners_acls_and_aborts_on_nonempty_target(self):
        with tempfile.TemporaryDirectory() as directory:
            roles, dump = Path(directory) / "roles.sql", Path(directory) / "source.dump"
            roles.write_text('CREATE ROLE "perfume_user";\n', encoding="utf-8")
            dump.write_bytes(b"fake-custom-dump")
            instance = rehearsal.Rehearsal.__new__(rehearsal.Rehearsal)
            instance.db = config()["services"][rehearsal.SERVICE]
            instance.container = Mock(return_value=container())
            instance.psql = Mock(return_value="")
            instance.run = Mock(return_value="")
            instance.restore(roles, dump, 'owner"name')
            command = instance.run.call_args.args[0]
            self.assertIn("--single-transaction", command)
            self.assertIn("--exit-on-error", command)
            self.assertEqual(rehearsal.DATABASE, command[command.index("--dbname") + 1])
            for forbidden in ("--create", "--clean", "--no-owner", "--no-privileges", "--disable-triggers"):
                self.assertNotIn(forbidden, command)
            self.assertIn(b'OWNER TO "owner""name"', instance.psql.call_args.args[0])
            instance.psql = Mock(side_effect=ValueError("Restore requires a fresh, empty test database"))
            instance.run.reset_mock()
            with self.assertRaises(ValueError):
                instance.restore(roles, dump, "perfume_user")
            instance.run.assert_not_called()

    def test_docker_sql_failures_do_not_echo_credentials_or_row_contents(self):
        import unittest.mock
        failed = subprocess.CompletedProcess(["docker"], 1, stdout=PASSWORD.encode(), stderr=b"secret provider payload")
        with unittest.mock.patch.object(rehearsal.subprocess, "run", return_value=failed):
            with self.assertRaises(ValueError) as caught:
                rehearsal.Rehearsal.run(["docker"])
        self.assertNotIn(PASSWORD, str(caught.exception))
        self.assertNotIn("secret provider payload", str(caught.exception))

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI required, not its engine.")
    def test_actual_compose_render_and_guard_without_starting_containers(self):
        environment = dict(os.environ)
        for key in list(environment):
            if key.startswith(("COMPOSE_", "POSTGRES_", "PGVECTOR_")):
                environment.pop(key)
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "test.env"
            env_file.write_text(f"POSTGRES_BASE_IMAGE={BASE}\nPOSTGRES_SERVER_VERSION=17.6\nPOSTGRES_SERVER_VERSION_NUM=170006\n"
                                f"PGVECTOR_TEST_LOCALE=en_US.utf8\nPGVECTOR_TEST_PASSWORD={PASSWORD}\n", encoding="utf-8")
            result = subprocess.run(["docker", "compose", "--project-name", rehearsal.PROJECT, "--project-directory", str(ROOT),
                                     "--env-file", str(env_file), "--file", str(ROOT / "compose.pgvector-rehearsal.yaml"),
                                     "config", "--format", "json"], capture_output=True, text=True, encoding="utf-8", env=environment,
                                    check=True, timeout=30)
            rehearsal.validate_config(json.loads(result.stdout))
            result = subprocess.run([os.sys.executable, str(ROOT / "scripts/pgvector-rehearsal.py"), "--env-file", str(env_file), "config"],
                                    capture_output=True, text=True, env=environment, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotIn(PASSWORD, result.stdout + result.stderr)
            env_file.write_text("POSTGRES_BASE_IMAGE=postgres:17-bookworm\nPOSTGRES_SERVER_VERSION=17.6\nPOSTGRES_SERVER_VERSION_NUM=170006\n"
                                f"PGVECTOR_TEST_LOCALE=en_US.utf8\nPGVECTOR_TEST_PASSWORD={PASSWORD}\n", encoding="utf-8")
            result = subprocess.run([os.sys.executable, str(ROOT / "scripts/pgvector-rehearsal.py"), "--env-file", str(env_file), "config"],
                                    capture_output=True, text=True, env=environment, timeout=30)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(PASSWORD, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
