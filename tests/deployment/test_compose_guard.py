"""Offline tests for boundaries that protect existing databases and secrets."""
import copy
import importlib.util
import json
import os
from pathlib import Path
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
        "volumes": {"pgdata": {"name": "perfume-dev_pgdata"}},
        "networks": {name: {"name": "perfume-dev_" + name, "internal": name in {"app", "data"}}
                     for name in ("app", "data", "db-access", "web")},
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
                "networks": {"app": None, "data": None},
            },
            "frontend": {
                "build": {"context": "/srv/perfume/frontend", "dockerfile": "Dockerfile"},
                "ports": [{"published": "8088", "target": 80}],
                "networks": {"app": None, "web": None},
            },
        },
    }


class DeploymentGuardTests(unittest.TestCase):
    def test_existing_fixed_deployment_is_accepted(self):
        guard.validate_config(deployment_config(), PASSWORD)

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
