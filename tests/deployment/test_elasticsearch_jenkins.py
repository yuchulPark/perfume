"""Deployment metadata and fully mocked SSH-shell regression checks. Never operate real containers."""
from copy import deepcopy
import os
from pathlib import Path
import tempfile
import unittest

from test_compose_guard import deployment_config, PASSWORD, guard, ai_environment
from test_elasticsearch_configuration import add_elasticsearch_configuration, search_network
import test_jenkins_db_preservation as preservation


class ElasticsearchDeploymentGuardTests(unittest.TestCase):
    def validate(self, config, enabled=True):
        guard.validate_config(config, PASSWORD, expected_elasticsearch=enabled)

    def test_enabled_connection_keeps_existing_auth_ai_support_and_db_guards(self):
        config = add_elasticsearch_configuration(deployment_config(ai_environment()))
        config["services"]["backend"]["environment"].update(
            PERFUME_AUTH_ENABLED="true", PERFUME_AUTH_COOKIE_SECURE="false", SPRING_PROFILES_ACTIVE="auth-dev-http",
            PERFUME_AUTH_DEV_HTTP_ENABLED="true", PERFUME_AUTH_DEV_HTTP_ORIGIN="http://192.168.0.19:8088",
            PERFUME_SUPPORT_ENABLED="true", PERFUME_SUPPORT_ALLOWED_ORIGINS="http://192.168.0.19:8088")
        self.validate(config)
        invalid = deepcopy(config); invalid["services"]["backend"]["environment"]["PERFUME_AUTH_COOKIE_SECURE"] = "true"
        with self.assertRaises(ValueError): self.validate(invalid)
        invalid = deepcopy(config); invalid["services"]["db"]["image"] = "postgres:latest"
        with self.assertRaises(ValueError): self.validate(invalid)

    def test_disabled_and_mismatched_opt_in_cannot_add_connection_resources(self):
        self.validate(deployment_config(), enabled=False)
        with self.assertRaises(ValueError): self.validate(deployment_config(), enabled=True)
        with self.assertRaises(ValueError): self.validate(add_elasticsearch_configuration(deployment_config()), enabled=False)
        config = add_elasticsearch_configuration(deployment_config())
        config["services"]["backend"]["environment"]["PERFUME_ELASTICSEARCH_ENABLED"] = "false"
        with self.assertRaises(ValueError): self.validate(config, enabled=False)

    def test_endpoint_alias_credentials_and_unexpected_overrides_are_rejected_without_echo(self):
        changes = {"PERFUME_ELASTICSEARCH_ENABLED": "TRUE", "PERFUME_ELASTICSEARCH_URL": "http://host?token=private-value",
                   "PERFUME_ELASTICSEARCH_ALIAS": "other", "PERFUME_ELASTICSEARCH_API_KEY": "private-value",
                   "PERFUME_ELASTICSEARCH_INDEX_API_KEY_FILE": "/run/secrets/index-key",
                   "PERFUME_ELASTICSEARCH_CA_FILE": "/wrong/ca"}
        for name, value in changes.items():
            with self.subTest(name=name):
                config = add_elasticsearch_configuration(deployment_config())
                config["services"]["backend"]["environment"][name] = value
                with self.assertRaises(ValueError) as error: self.validate(config)
                self.assertNotIn("private-value", str(error.exception))

    def test_only_exact_read_only_search_secret_sources_and_targets_are_allowed(self):
        for change in ("source", "external", "target", "uid", "extra", "frontend"):
            with self.subTest(change=change):
                config = add_elasticsearch_configuration(deployment_config())
                if change == "source": config["secrets"]["elastic_search_key"]["file"] = "/tmp/private-key"
                elif change == "external": config["secrets"]["elastic_search_key"]["external"] = True
                elif change == "target": config["services"]["backend"]["secrets"][0]["target"] = "/app/other"
                elif change == "uid": config["services"]["backend"]["secrets"][0]["uid"] = "0"
                elif change == "extra": config["secrets"]["index_key"] = {"file": "/tmp/index-key"}
                else: config["services"]["frontend"]["secrets"] = [{"source": "elastic_search_key"}]
                with self.assertRaises(ValueError): self.validate(config)

    def test_search_network_cannot_be_managed_or_attached_to_other_services(self):
        for change in ("managed", "name", "frontend", "db", "depends_on", "service", "volume"):
            with self.subTest(change=change):
                config = add_elasticsearch_configuration(deployment_config())
                if change == "managed": config["networks"]["search"] = {"name": "perfume-dev_search", "internal": True}
                elif change == "name": config["networks"]["search"]["name"] = "other_search"
                elif change in {"frontend", "db"}: config["services"][change]["networks"]["search"] = None
                elif change == "depends_on": config["services"]["backend"]["depends_on"]["elasticsearch"] = {"condition": "service_healthy"}
                elif change == "service": config["services"]["elasticsearch"] = {}
                else: config["volumes"]["elasticsearch-data"] = {"name": "perfume-dev_elasticsearch-data"}
                with self.assertRaises(ValueError): self.validate(config)

    def test_existing_network_requires_correct_isolation_and_project_but_not_a_running_node(self):
        guard.validate_elasticsearch_network(search_network()) # No containers/health state is required.
        for key, value in (("Name", "other_search"), ("Internal", False), ("Driver", "overlay"), ("Scope", "swarm"), ("Labels", {})):
            network = search_network(); network[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): guard.validate_elasticsearch_network(network)

    def test_secret_fingerprint_reads_only_metadata_and_detects_rotation(self):
        with tempfile.TemporaryDirectory() as directory:
            for relative in guard.ELASTIC_FILES.values():
                path = Path(directory) / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("private-fixture", encoding="utf-8"); path.chmod(0o400 if path.name == "search-api-key" else 0o644)
            before = guard.elasticsearch_files_fingerprint(directory)
            self.assertEqual(before, guard.elasticsearch_files_fingerprint(directory))
            key = Path(directory) / guard.ELASTIC_FILES["elastic_search_key"]
            key.chmod(0o600); key.write_text("rotated-fixture-with-different-size", encoding="utf-8"); key.chmod(0o400)
            self.assertNotEqual(before, guard.elasticsearch_files_fingerprint(directory))
            key.chmod(0o600); key.unlink()
            with self.assertRaises(OSError): guard.elasticsearch_files_fingerprint(directory)

    @unittest.skipIf(os.name == "nt", "POSIX permission and symlink enforcement")
    def test_secret_symlinks_and_broad_permissions_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in guard.ELASTIC_FILES.values():
                path = root / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("fixture")
            key = root / guard.ELASTIC_FILES["elastic_search_key"]
            key.chmod(0o644)
            with self.assertRaises(ValueError): guard.elasticsearch_files_fingerprint(root)
            key.chmod(0o400); guard.elasticsearch_files_fingerprint(root)
            key.unlink(); key.symlink_to(root / guard.ELASTIC_FILES["elastic_ca"])
            with self.assertRaises(ValueError): guard.elasticsearch_files_fingerprint(root)


@unittest.skipUnless(preservation.BASH and Path(preservation.BASH).exists(), "Bash required for mocked deployment")
class JenkinsElasticsearchTests(unittest.TestCase):
    def run_deploy(self, **kwargs):
        return preservation.JenkinsDbPreservationTests().run_deploy(**kwargs)

    def assert_apps_only(self, commands):
        up = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"] and "up" in cmd]
        self.assertEqual(["backend", "frontend"], [cmd[-1] for cmd in up])
        self.assertTrue(all("--no-deps" in cmd and "--no-build" in cmd for cmd in up))
        self.assertFalse(any(cmd[:2] in (["docker", "run"], ["docker", "pull"], ["docker", "stop"],
                                        ["docker", "restart"], ["docker", "rm"]) for cmd in commands))
        self.assertFalse(any("compose.elasticsearch.yaml" in arg or "compose.elasticsearch-reindex.yaml" in arg for cmd in commands for arg in cmd))
        self.assertFalse(any(cmd[:2] == ["docker", "exec"] and "elasticsearch" in cmd for cmd in commands))

    def test_enabled_deployment_preserves_http_auth_rag_support_and_does_not_require_es_health(self):
        inputs = {"PERFUME_AI_SEARCH_ENABLED": "true", "PERFUME_AI_SEARCH_MODEL_DIGEST": "a" * 64,
                  "PERFUME_AI_RECOMMEND_ENABLED": "true", "PERFUME_AI_RECOMMEND_MODEL_DIGEST": "b" * 64}
        result, commands = self.run_deploy(vector=True, extension="0.8.7", auth_http=True, support=True,
                                           ai_inputs=inputs, elastic_value="true")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr); self.assert_apps_only(commands)
        compose = [cmd for cmd in commands if cmd[:2] == ["docker", "compose"]]
        for file in ("compose.auth-dev-http.yaml", "compose.elasticsearch-app.yaml", "compose.elasticsearch-jenkins.yaml"):
            self.assertTrue(all(any(arg.endswith("/" + file) for arg in cmd) for cmd in compose))
        self.assertIn("Elasticsearch opt-in from server .env: true", result.stdout)

    def test_disabled_server_flag_ignores_inherited_enable_and_does_not_need_search_files_or_network(self):
        result, commands = self.run_deploy(auth_http=True, elastic_value="false")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr); self.assert_apps_only(commands)
        self.assertFalse(any("compose.elasticsearch-" in arg for cmd in commands for arg in cmd))
        self.assertFalse(any(cmd[:2] == ["docker", "network"] for cmd in commands))

    def test_invalid_duplicate_and_interpolated_flags_stop_before_any_git_or_docker(self):
        for value in ("TRUE", "", "true\nPERFUME_ELASTICSEARCH_ENABLED=false", "$(touch should-never-run)"):
            with self.subTest(value=value):
                result, commands = self.run_deploy(elastic_value=value)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(cmd[0] in ("git", "docker") for cmd in commands))

    def test_first_rollout_uses_old_preflight_then_requires_new_connection_validation(self):
        result, commands = self.run_deploy(legacy_search=True, vector=True, extension="0.8.7", auth_http=True, elastic_value="'true'")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr); self.assert_apps_only(commands)
        reset = next(i for i, cmd in enumerate(commands) if cmd[:2] == ["git", "reset"])
        before = [cmd for cmd in commands[:reset] if cmd[:2] == ["docker", "compose"]]
        after = [cmd for cmd in commands[reset:] if cmd[:2] == ["docker", "compose"]]
        self.assertFalse(any("compose.elasticsearch-app.yaml" in arg for cmd in before for arg in cmd))
        self.assertTrue(all(any(arg.endswith("/compose.elasticsearch-app.yaml") for arg in cmd) for cmd in after))

    def test_enabled_deployment_cannot_silently_drop_override_after_checkout(self):
        result, commands = self.run_deploy(legacy_search=True, keep_legacy_guard=True, elastic_value="true")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Elasticsearch deployment validation is required", result.stderr)
        self.assertFalse(any(cmd[:2] == ["docker", "compose"] and any(action in cmd for action in ("build", "up")) for cmd in commands))

    def test_missing_secret_unsafe_network_and_concurrent_rotation_abort_before_app_build(self):
        for options in ({"missing_search_secret": True}, {"unsafe_search_network": True}, {"change_search_secret": True}):
            with self.subTest(options=options):
                result, commands = self.run_deploy(elastic_value="true", **options)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(any(cmd[:2] == ["docker", "compose"] and any(action in cmd for action in ("build", "up")) for cmd in commands))

    def test_incoming_revision_cannot_track_search_secret_directory_or_files(self):
        for path in (".secrets", ".secrets/elasticsearch/search-api-key"):
            result, commands = self.run_deploy(protected_incoming=True, protected_path=path)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(any(cmd[:2] == ["git", "reset"] for cmd in commands))

    def test_check_action_validates_connection_without_any_container_lifecycle_operation(self):
        result, commands = self.run_deploy(auth_http=True, elastic_value="true", action="check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any(cmd[:2] == ["git", "fetch"] for cmd in commands))
        self.assertFalse(any(cmd[:2] == ["docker", "compose"] and any(action in cmd for action in ("build", "up")) for cmd in commands))


if __name__ == "__main__":
    unittest.main()
