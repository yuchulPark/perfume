"""Metadata-only support opt-in checks; never connects to any database or server."""
from copy import deepcopy
from pathlib import Path
import os
import unittest
from unittest.mock import patch
import test_jenkins_db_preservation as preservation
from test_compose_guard import deployment_config, PASSWORD
from test_pgvector_production import guard


class SupportDeploymentTests(unittest.TestCase):
    def validate(self, config):
        return guard.validate_config(config, PASSWORD, "/srv/perfume")

    def test_support_configuration_does_not_change_existing_resources(self):
        config = deployment_config()
        baseline = deepcopy(config)
        config["services"]["backend"]["environment"].update(PERFUME_SUPPORT_ENABLED="false", PERFUME_SUPPORT_ALLOWED_ORIGINS="")
        self.validate(config)
        for name in ("db", "ollama", "frontend"):
            self.assertEqual(baseline["services"][name], config["services"][name])
        env = config["services"]["backend"]["environment"]
        env.update(PERFUME_AUTH_ENABLED="true", PERFUME_AUTH_COOKIE_SECURE="false", SPRING_PROFILES_ACTIVE="auth-dev-http",
                   PERFUME_AUTH_DEV_HTTP_ENABLED="true", PERFUME_AUTH_DEV_HTTP_ORIGIN="http://192.168.0.19:8088",
                   PERFUME_SUPPORT_ENABLED="true", PERFUME_SUPPORT_ALLOWED_ORIGINS="http://192.168.0.19:8088")
        self.validate(config)
        for override in ({"PERFUME_SUPPORT_ENABLED":"invalid"}, {"PERFUME_SUPPORT_ALLOWED_ORIGINS":"*"},
                         {"PERFUME_SUPPORT_ALLOWED_ORIGINS":"http://evil.example"}, {"PERFUME_AUTH_ENABLED":"false"},
                         {"PERFUME_SUPPORT_ALLOWED_ORIGINS":""}):
            invalid = deepcopy(config)
            invalid["services"]["backend"]["environment"].update(override)
            with self.assertRaises(ValueError): self.validate(invalid)

    def test_support_env_is_cleared_and_websocket_proxy_preserves_existing_paths(self):
        root = Path(__file__).resolve().parents[2]
        script = (root/"scripts/jenkins-deploy.sh").read_text(encoding="utf-8")
        self.assertIn("unset PERFUME_SUPPORT_ENABLED PERFUME_SUPPORT_ALLOWED_ORIGINS", script)
        nginx = (root/"frontend/nginx.conf").read_text(encoding="utf-8")
        for text in ('location = /ws/support', 'proxy_http_version 1.1', 'proxy_set_header Upgrade $http_upgrade',
                     'proxy_set_header Connection "upgrade"', 'location ~ ^/api(?:/|$)', 'proxy_read_timeout 450s'):
            self.assertIn(text, nginx)

    def test_inherited_support_flags_cannot_activate_chat_in_authentication_on_or_off_deploys(self):
        # Reuse the real shell fixture and its DB/.env preservation assertions.
        # Only this test extends the fail-closed command double with the new flag names.
        old = preservation.FAKE_TOOLS
        guarded = old.replace('for key in PERFUME_AI_SEARCH_ENABLED',
                              'for key in PERFUME_SUPPORT_ENABLED PERFUME_SUPPORT_ALLOWED_ORIGINS PERFUME_AI_SEARCH_ENABLED')
        self.assertNotEqual(old, guarded)
        case = preservation.JenkinsDbPreservationTests()
        with patch.dict(os.environ, PERFUME_SUPPORT_ENABLED='true', PERFUME_SUPPORT_ALLOWED_ORIGINS='http://untrusted.example'), patch.object(preservation, 'FAKE_TOOLS', guarded):
            for enabled in ('false', 'true'):
                with self.subTest(auth_enabled=enabled):
                    result, commands = case.run_deploy(auth_enabled=enabled)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    up = [cmd for cmd in commands if cmd[:2] == ['docker', 'compose'] and 'up' in cmd]
                    self.assertEqual(['backend', 'frontend'], [cmd[-1] for cmd in up])
                    self.assertTrue(all('--no-deps' in cmd for cmd in up))
