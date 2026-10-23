"""Render the actual private HTTP override offline; no Docker daemon or data access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest
from test_compose_guard import guard, PASSWORD

ROOT = Path(__file__).resolve().parents[2]

@unittest.skipUnless(shutil.which("docker"), "Docker CLI required for offline Compose rendering")
class AuthDevHttpComposeTests(unittest.TestCase):
    def render(self, enabled):
        environment = {key:value for key,value in os.environ.items()
                       if not key.startswith(("COMPOSE_", "DB_", "PERFUME_", "SPRING_"))}
        environment.update(DB_PASSWORD=PASSWORD, PERFUME_AUTH_ENABLED="true", PERFUME_AUTH_DEV_HTTP_ENABLED=enabled)
        result = subprocess.run(["docker", "compose", "--env-file", os.devnull, "-f", str(ROOT / "compose.yaml"),
                                 "-f", str(ROOT / "compose.auth-dev-http.yaml"), "config", "--format", "json"],
                                env=environment, capture_output=True, text=True, encoding="utf-8", check=True, timeout=30)
        return json.loads(result.stdout)

    def test_actual_override_retains_four_services_volumes_healthchecks_and_requires_opt_in(self):
        config = self.render("true")
        guard.validate_config(config, PASSWORD, str(ROOT))
        self.assertEqual({"db", "backend", "frontend", "ollama"}, set(config["services"]))
        self.assertFalse(config["services"]["backend"].get("ports"))
        self.assertIn("/api/perfumes?size=1", str(config["services"]["backend"]["healthcheck"]))
        self.assertEqual("8088", str(config["services"]["frontend"]["ports"][0]["published"]))
        with self.assertRaises(ValueError):
            guard.validate_config(self.render("false"), PASSWORD, str(ROOT))

if __name__ == "__main__":
    unittest.main()
