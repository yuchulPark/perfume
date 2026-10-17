"""AI opt-in deployment metadata tests. No Docker CLI, model or database calls."""
import json
import os
import subprocess
import sys
import unittest

from test_compose_guard import SCRIPT, PASSWORD, guard, deployment_config, ai_environment


def enabled_inputs():
    return {"PERFUME_AI_SEARCH_ENABLED": "true", "PERFUME_AI_SEARCH_MODEL_DIGEST": "sha256:" + "a" * 64,
            "PERFUME_AI_RECOMMEND_ENABLED": "true", "PERFUME_AI_RECOMMEND_MODEL_DIGEST": "b" * 64}


class AiComposeEnvironmentTests(unittest.TestCase):
    def test_actual_compose_defaults_to_disabled_without_digests_and_preserves_limits(self):
        env = ai_environment()
        self.assertEqual(13, len(env))
        self.assertEqual("false", env["PERFUME_AI_SEARCH_ENABLED"])
        self.assertEqual("false", env["PERFUME_AI_RECOMMEND_ENABLED"])
        self.assertEqual("", env["PERFUME_AI_SEARCH_MODEL_DIGEST"])
        self.assertEqual("", env["PERFUME_AI_RECOMMEND_MODEL_DIGEST"])
        guard.validate_config(deployment_config(env), PASSWORD)

    def test_actual_compose_accepts_enabled_and_search_only_modes_with_full_independent_digests(self):
        for recommend in ("false", "true"):
            with self.subTest(recommend=recommend):
                inputs = dict(enabled_inputs(), PERFUME_AI_RECOMMEND_ENABLED=recommend)
                if recommend == "false":
                    inputs["PERFUME_AI_RECOMMEND_MODEL_DIGEST"] = ""
                env = ai_environment(inputs)
                for key, value in inputs.items():
                    self.assertEqual(value, env[key])
                guard.validate_config(deployment_config(env), PASSWORD)

    def test_missing_malformed_and_wrong_typed_digests_fail_without_including_their_values(self):
        for feature in ("SEARCH", "RECOMMEND"):
            key = f"PERFUME_AI_{feature}_MODEL_DIGEST"
            for bad in ("", "short-digest-do-not-log", "a" * 63, "a" * 65, "sha256:" + "z" * 64, " a" * 32, None, 123):
                with self.subTest(feature=feature, bad_type=type(bad).__name__):
                    env = ai_environment(enabled_inputs())
                    env[key] = bad
                    with self.assertRaises(ValueError) as failure:
                        guard.validate_config(deployment_config(env), PASSWORD)
                    self.assertNotIn("short-digest-do-not-log", str(failure.exception))
        # Malformed nonempty values are rejected even when both APIs are disabled.
        with self.assertRaises(ValueError):
            guard.validate_config(deployment_config(ai_environment({"PERFUME_AI_SEARCH_MODEL_DIGEST": "bad"})), PASSWORD)

    def test_recommendation_cannot_start_without_search_and_flags_are_strict(self):
        env = ai_environment(enabled_inputs())
        env["PERFUME_AI_SEARCH_ENABLED"] = "false"
        with self.assertRaises(ValueError):
            guard.validate_config(deployment_config(env), PASSWORD)
        for feature in ("SEARCH", "RECOMMEND"):
            for bad in (True, False, "yes", "1", "TRUE", " false ", None):
                with self.subTest(feature=feature, bad=bad):
                    env = ai_environment(enabled_inputs())
                    env[f"PERFUME_AI_{feature}_ENABLED"] = bad
                    with self.assertRaises(ValueError):
                        guard.validate_config(deployment_config(env), PASSWORD)

    def test_partial_configuration_and_changed_model_limits_or_extra_job_overrides_are_rejected(self):
        for key in ai_environment():
            with self.subTest(missing=key):
                env = ai_environment()
                del env[key]
                with self.assertRaises(ValueError):
                    guard.validate_config(deployment_config(env), PASSWORD)
        for key, bad in (
            ("PERFUME_AI_SEARCH_MODEL", "other-model"), ("PERFUME_AI_RECOMMEND_MODEL", "qwen3-embedding:0.6b"),
            ("PERFUME_AI_SEARCH_READ_TIMEOUT_MS", "30000"), ("PERFUME_AI_SEARCH_REQUEST_TIMEOUT_MS", "0"),
            ("PERFUME_AI_RECOMMEND_READ_TIMEOUT_MS", "180000"), ("PERFUME_AI_RECOMMEND_GENERATION_TIMEOUT_MS", "600000"),
            ("PERFUME_AI_RECOMMEND_REQUEST_TIMEOUT_MS", "600000"), ("PERFUME_AI_RECOMMEND_MAX_CONCURRENT", "2"),
            ("PERFUME_AI_SEARCH_MAX_CONCURRENT", "2"), ("PERFUME_AI_EMBEDDING_AUTO_ENABLED", "true"),
            ("PERFUME_AI_EMBEDDING_BATCH_ENABLED", "true"), ("SCENTREV_BRAND_IMPORT", "true"),
            ("SPRING_APPLICATION_JSON", '{"perfume.ai.recommend.enabled":true}'),
        ):
            with self.subTest(key=key):
                env = ai_environment()
                env[key] = bad
                with self.assertRaises(ValueError):
                    guard.validate_config(deployment_config(env), PASSWORD)

    def test_cli_rejects_enabled_missing_digest_without_echoing_credentials_or_metadata(self):
        config = deployment_config(ai_environment(enabled_inputs()))
        config["services"]["backend"]["environment"]["PERFUME_AI_RECOMMEND_MODEL_DIGEST"] = "private-invalid-model-identity"
        result = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(config), text=True,
                                capture_output=True, env=dict(os.environ, PERFUME_EXPECTED_DB_PASSWORD=PASSWORD))
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(PASSWORD, result.stdout + result.stderr)
        self.assertNotIn("private-invalid-model-identity", result.stdout + result.stderr)

    def test_legacy_disabled_preflight_and_existing_db_protections_still_hold_with_ai_enabled(self):
        guard.validate_config(deployment_config(), PASSWORD)
        for change in ("password", "db-volume", "db-image", "frontend-port", "ollama-port"):
            with self.subTest(change=change):
                config = deployment_config(ai_environment(enabled_inputs()))
                if change == "password":
                    config["services"]["backend"]["environment"]["DB_PASSWORD"] = "wrong"
                elif change == "db-volume":
                    config["volumes"]["pgdata"]["name"] = "new-empty-volume"
                elif change == "db-image":
                    config["services"]["db"]["image"] = "unapproved:latest"
                elif change == "frontend-port":
                    config["services"]["frontend"]["ports"][0]["published"] = "8089"
                else:
                    config["services"]["ollama"]["ports"] = [{"published": "11434", "target": 11434}]
                with self.assertRaises(ValueError):
                    guard.validate_config(config, PASSWORD)
