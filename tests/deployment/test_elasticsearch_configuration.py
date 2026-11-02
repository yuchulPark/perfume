"""Read-only Compose rendering: never contact a daemon, build, pull, run or change containers."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
DOCKER = shutil.which("docker")


@unittest.skipUnless(DOCKER, "Docker Compose CLI required for configuration rendering only")
class ElasticsearchComposeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(("COMPOSE_", "PERFUME_", "DB_"))}
        env.update(DB_PASSWORD="compose-test-placeholder", PERFUME_REINDEX_IMAGE="sha256:" + "a" * 64)
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "empty.env"
            env_file.write_text("", encoding="utf-8")
            def render(*files):
                command = [DOCKER, "compose", "--env-file", str(env_file), "--project-name", "perfume-dev", "--profile", "*"]
                for filename in ("compose.yaml", *files):
                    command += ["-f", str(ROOT / filename)]
                command += ["config", "--format", "json"]
                result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, timeout=30, check=True)
                return json.loads(result.stdout)
            cls.base = render()
            cls.infra = render("compose.elasticsearch.yaml")
            cls.app = render("compose.elasticsearch.yaml", "compose.elasticsearch-app.yaml")
            cls.index = render("compose.elasticsearch.yaml", "compose.elasticsearch-reindex.yaml")

    def test_infrastructure_does_not_change_any_existing_service_or_volume(self):
        for name, service in self.base["services"].items():
            self.assertEqual(service, self.infra["services"][name], name)
        for name, volume in self.base["volumes"].items():
            self.assertEqual(volume, self.infra["volumes"][name], name)
        es = self.infra["services"]["elasticsearch"]
        self.assertEqual(["search"], es["profiles"])
        self.assertFalse(es.get("ports"))
        self.assertFalse(es.get("depends_on"))
        self.assertEqual({"search"}, set(es["networks"]))
        self.assertTrue(self.infra["networks"]["search"]["internal"])
        self.assertEqual("true", es["environment"]["xpack.security.enabled"])
        self.assertEqual("true", es["environment"]["xpack.security.http.ssl.enabled"])
        self.assertNotIn("ELASTIC_PASSWORD", es["environment"])
        self.assertEqual(2 * 1024**3, int(es["mem_limit"]))
        self.assertEqual(1.0, float(es["cpus"]))

    def test_backend_requires_separate_opt_in_and_keeps_existing_networks_and_security(self):
        before = self.base["services"]["backend"]
        after = self.app["services"]["backend"]
        self.assertEqual(set(before["networks"]) | {"search"}, set(after["networks"]))
        for name, value in before["environment"].items():
            self.assertEqual(value, after["environment"][name], name)
        self.assertEqual("false", after["environment"]["PERFUME_ELASTICSEARCH_ENABLED"])
        self.assertNotIn("PERFUME_ELASTICSEARCH_INDEX_API_KEY_FILE", after["environment"])
        self.assertEqual(before["depends_on"], after["depends_on"])
        self.assertEqual(before["healthcheck"], after["healthcheck"])

    def test_manual_indexer_is_bounded_separate_and_not_a_web_startup_dependency(self):
        job = self.index["services"]["elasticsearch-reindex"]
        self.assertEqual(["search-index"], job["profiles"])
        self.assertEqual(["--elasticsearch-reindex=full"], job["command"])
        self.assertTrue(job["read_only"])
        self.assertFalse(job.get("ports"))
        self.assertFalse(job.get("depends_on"))
        self.assertEqual({"data", "search"}, set(job["networks"]))
        self.assertEqual(self.base["services"]["backend"], self.index["services"]["backend"])
        source = (ROOT / "docker/elasticsearch/Dockerfile").read_text(encoding="utf-8")
        self.assertIn("FROM docker.elastic.co/elasticsearch/elasticsearch:8.19.23", source)
        self.assertIn("elasticsearch-plugin install --batch analysis-nori", source)
        query_key = json.loads((ROOT / "docker/elasticsearch/search-api-key.json").read_text())
        self.assertEqual(["read"], query_key["role_descriptors"]["catalog_read"]["indices"][0]["privileges"])


if __name__ == "__main__":
    unittest.main()
