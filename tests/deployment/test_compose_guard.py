"""Offline tests for boundaries that protect existing databases and secrets."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "validate-perfume-compose.py"
spec = importlib.util.spec_from_file_location("compose_guard", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
PASSWORD = "offline-test-password"


def deployment_config():
    return {
        "name": "perfume-dev",
        "volumes": {"pgdata": {"name": "perfume-dev_pgdata"},
                    "ollama-data": {"name": "perfume-dev_ollama-data"}},
        "networks": {name: {"name": "perfume-dev_" + name, "internal": name in {"app", "data", "ai"}}
                     for name in ("app", "data", "db-access", "web", "ai", "ollama-download")},
        "services": {
            "db": {
                "image": "postgres:17-bookworm",
                "environment": {"POSTGRES_DB": "perfume", "POSTGRES_USER": "perfume_user", "POSTGRES_PASSWORD": PASSWORD},
                "volumes": [{"type": "volume", "source": "pgdata", "target": "/var/lib/postgresql/data"}],
                "ports": [{"host_ip": "127.0.0.1", "published": "15432", "target": 5432, "protocol": "tcp"}],
                "networks": {"data": None, "db-access": None},
            },
            "backend": {
                "environment": {"DB_URL": "jdbc:postgresql://db:5432/perfume", "DB_USERNAME": "perfume_user",
                                "DB_PASSWORD": PASSWORD, "SCENTREV_API_KEY": ""},
                "build": {"context": "/srv/perfume", "dockerfile": "Dockerfile"},
                "networks": {"app": None, "data": None, "ai": None},
                "depends_on": {"db": {"condition": "service_healthy"}},
            },
            "frontend": {
                "build": {"context": "/srv/perfume/frontend", "dockerfile": "Dockerfile"},
                "ports": [{"published": "8088", "target": 80}],
                "networks": {"app": None, "web": None},
            },
            "ollama": {
                "image": "ollama/ollama:0.40.4",
                "cpus": 3.0,
                "mem_limit": 6 * 1024 ** 3,
                "environment": {"OLLAMA_HOST": "0.0.0.0:11434", "OLLAMA_NO_CLOUD": "1",
                                "OLLAMA_VULKAN": "0", "OLLAMA_NUM_PARALLEL": "1",
                                "OLLAMA_MAX_LOADED_MODELS": "1", "OLLAMA_CONTEXT_LENGTH": "4096",
                                "OLLAMA_KEEP_ALIVE": "5m"},
                "volumes": [{"type": "volume", "source": "ollama-data", "target": "/root/.ollama"}],
                "networks": {"ai": None, "ollama-download": None},
                "healthcheck": {"test": ["CMD", "ollama", "list"]},
            },
        },
    }


class DeploymentGuardTests(unittest.TestCase):
    def test_existing_fixed_deployment_is_accepted(self):
        for memory_limit in (6 * 1024 ** 3, str(6 * 1024 ** 3)):
            with self.subTest(memory_limit=memory_limit):
                config = deployment_config()
                config["services"]["ollama"]["mem_limit"] = memory_limit
                guard.validate_config(config, PASSWORD)

    def test_project_and_volume_cannot_move_to_stock_or_an_empty_new_volume(self):
        for project, volume in (("stock-dividend", "stock-db-data"), ("perfume-dev", "new-empty-volume")):
            with self.subTest(project=project, volume=volume):
                config = deployment_config()
                config["name"] = project
                config["volumes"]["pgdata"]["name"] = volume
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_db_cannot_be_published_on_external_interfaces_or_other_ports(self):
        for host, port in (("0.0.0.0", "15432"), ("::", "15432"), ("127.0.0.1", "5432")):
            with self.subTest(host=host, port=port):
                config = deployment_config()
                config["services"]["db"]["ports"][0].update(host_ip=host, published=port)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_new_init_script_mount_is_rejected(self):
        config = deployment_config()
        config["services"]["db"]["volumes"].append({"type": "bind", "source": "/tmp/init", "target": "/docker-entrypoint-initdb.d"})
        with self.assertRaises(ValueError):
            guard.validate_config(config, PASSWORD)

    def test_stock_network_and_image_reuse_are_rejected(self):
        for kind in ("network", "image"):
            with self.subTest(kind=kind):
                config = deployment_config()
                if kind == "network":
                    config["networks"]["data"]["name"] = "stock-network"
                else:
                    config["services"]["backend"]["image"] = "stock-backend"
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_credential_mismatch_stops_deployment_instead_of_rotating_password(self):
        with self.assertRaises(ValueError):
            guard.validate_config(deployment_config(), "different-password")

    def test_provider_key_and_command_override_are_rejected(self):
        for kind in ("key", "command"):
            with self.subTest(kind=kind):
                config = deployment_config()
                if kind == "key":
                    config["services"]["backend"]["environment"]["SCENTREV_API_KEY"] = "fake-provider-key"
                else:
                    config["services"]["backend"]["command"] = ["--scentrev.brand-import=true"]
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_build_cannot_target_stock_or_receive_credentials(self):
        for kind in ("context", "args"):
            with self.subTest(kind=kind):
                config = deployment_config()
                if kind == "context":
                    config["services"]["backend"]["build"]["context"] = "/srv/stock"
                else:
                    config["services"]["backend"]["build"]["args"] = {"DB_PASSWORD": PASSWORD}
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_cannot_publish_even_a_localhost_port(self):
        for host in ("0.0.0.0", "127.0.0.1", "::"):
            with self.subTest(host=host):
                config = deployment_config()
                config["services"]["ollama"]["ports"] = [
                    {"host_ip": host, "published": "11434", "target": 11434}]
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_store_cannot_use_the_db_or_an_unpersisted_path(self):
        for mount in (
            {"type": "volume", "source": "pgdata", "target": "/root/.ollama"},
            {"type": "bind", "source": "/tmp/models", "target": "/root/.ollama"},
            {"type": "volume", "source": "ollama-data", "target": "/tmp/models"},
            {"type": "volume", "source": "ollama-data", "target": "/root/.ollama", "read_only": True},
        ):
            with self.subTest(mount=mount):
                config = deployment_config()
                config["services"]["ollama"]["volumes"] = [mount]
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_model_volume_cannot_be_renamed_or_shared_externally(self):
        for change in ({"name": "stock-db-data"}, {"name": "new-empty-models"}, {"external": True}):
            with self.subTest(change=change):
                config = deployment_config()
                config["volumes"]["ollama-data"].update(change)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_networks_remain_isolated_from_db_frontend_and_backend_egress(self):
        for service, network in (("db", "ai"), ("frontend", "ai"), ("backend", "ollama-download"),
                                 ("ollama", "data"), ("ollama", "app")):
            with self.subTest(service=service, network=network):
                config = deployment_config()
                config["services"][service]["networks"][network] = None
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)
        for change in ({"internal": False}, {"external": True}, {"name": "stock-network"}):
            with self.subTest(change=change):
                config = deployment_config()
                config["networks"]["ai"].update(change)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_host_network_and_gpu_access_are_rejected(self):
        for change in (
            {"network_mode": "host"}, {"privileged": True}, {"runtime": "nvidia"},
            {"devices": [{"source": "/dev/dri", "target": "/dev/dri"}]}, {"gpus": "all"},
            {"deploy": {"resources": {"reservations": {"devices": [{"capabilities": ["gpu"]}]}}}},
        ):
            with self.subTest(change=change):
                config = deployment_config()
                config["services"]["ollama"].update(change)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_startup_and_healthcheck_cannot_automatically_pull_models(self):
        for change in (
            {"command": ["pull", "qwen3:4b-instruct"]},
            {"entrypoint": ["sh", "-c", "ollama serve & ollama pull qwen3:4b-instruct; wait"]},
            {"healthcheck": {"test": ["CMD", "ollama", "pull", "qwen3-embedding:0.6b"]}},
            {"post_start": [{"command": "ollama pull qwen3:4b-instruct"}]},
        ):
            with self.subTest(change=change):
                config = deployment_config()
                config["services"]["ollama"].update(change)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_applications_do_not_require_ollama_or_models_to_start(self):
        for service in ("backend", "frontend"):
            with self.subTest(service=service):
                config = deployment_config()
                config["services"][service].setdefault("depends_on", {})["ollama"] = {"condition": "service_healthy"}
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    def test_ollama_resource_limits_and_local_only_settings_are_preserved(self):
        for change in ({"cpus": 4.0}, {"mem_limit": 0}, {"image": "ollama/ollama:latest"}):
            with self.subTest(change=change):
                config = deployment_config()
                config["services"]["ollama"].update(change)
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)
        for setting, value in (("OLLAMA_NO_CLOUD", "0"), ("OLLAMA_NUM_PARALLEL", "4"),
                               ("OLLAMA_MAX_LOADED_MODELS", "2"), ("OLLAMA_CONTEXT_LENGTH", "32768")):
            with self.subTest(setting=setting):
                config = deployment_config()
                config["services"]["ollama"]["environment"][setting] = value
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)

    @unittest.skipUnless(shutil.which("docker"), "Docker CLI is not installed; offline boundary tests still run.")
    def test_repository_compose_matches_guard_without_starting_containers(self):
        project_directory = SCRIPT.parents[1]
        environment = dict(os.environ)
        for key in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "PERFUME_DB_IMAGE"):
            environment.pop(key, None)
        environment["DB_PASSWORD"] = PASSWORD
        result = subprocess.run([
            "docker", "compose", "--project-name", "perfume-dev", "--project-directory", str(project_directory),
            "--env-file", str(project_directory / ".env.example"), "--file", str(project_directory / "compose.yaml"),
            "config", "--format", "json",
        ], capture_output=True, text=True, encoding="utf-8", env=environment, check=True, timeout=30)
        guard.validate_config(json.loads(result.stdout), PASSWORD, str(project_directory))

    def test_runtime_image_must_disable_import_and_schema_writes(self):
        image = {"Entrypoint": ["java", "-jar", "/app/app.jar"], "Cmd": [
            "--server.port=8081", "--scentrev.brand-import=false", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.sql.init.mode=never", "--spring.jpa.show-sql=false",
        ]}
        guard.validate_backend_image(image)
        for flag in ("--scentrev.brand-import=true", "--spring.jpa.hibernate.ddl-auto=update", "--spring.sql.init.mode=always"):
            with self.subTest(flag=flag):
                invalid = copy.deepcopy(image)
                invalid["Cmd"].append(flag)
                with self.assertRaises(ValueError):
                    guard.validate_backend_image(invalid)

    def test_cli_errors_never_echo_password_or_provider_key(self):
        config = deployment_config()
        config["services"]["backend"]["environment"]["SCENTREV_API_KEY"] = "do-not-log-this-provider-key"
        environment = dict(os.environ, PERFUME_EXPECTED_DB_PASSWORD=PASSWORD, PYTHONDONTWRITEBYTECODE="1")
        result = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(config),
                                text=True, capture_output=True, env=environment)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(PASSWORD, result.stdout + result.stderr)
        self.assertNotIn("do-not-log-this-provider-key", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
