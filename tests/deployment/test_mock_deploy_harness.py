"""Regression tests for the test harness itself; no real Docker or server access."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import mock_deploy_diagnostics as diagnostics
from mock_deploy_tools import FAKE_TOOLS, PYTHON_DRIVER, prepare, read_commands
from test_compose_guard import deployment_config
from test_jenkins_db_preservation import BASH
from test_pgvector_production import container, image, probe


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash required for mock harness checks.")
class MockDeployHarnessTests(unittest.TestCase):
    def environment(self, root):
        env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*", MSYS2_ENV_CONV_EXCL="PERFUME_TEST_POSIX_DIRECTORY")
        posix = subprocess.run([BASH, "--noprofile", "--norc", "-c", 'cd "$1" && pwd -P', "test", str(root)],
                               env=env, text=True, capture_output=True, check=True, timeout=10).stdout.strip()
        env.update(PERFUME_TEST_POSIX_DIRECTORY=posix, PERFUME_TEST_DIRECTORY=str(root),
                   PERFUME_TEST_TRACE=posix + "/steps.log", SYNTHETIC_SECRET="synthetic-secret-never-trace")
        return env

    def test_failure_diagnostics_include_phase_without_commands_or_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); env = self.environment(root)
            script = diagnostics.instrument('set +x\nverify_database() { : "$SYNTHETIC_SECRET"; return 7; }\nverify_database\n')
            result = diagnostics.run([BASH, "--noprofile", "--norc", "-c", script], env=env, trace=root / "steps.log")
            self.assertEqual(7, result.returncode)
            self.assertIn("last=database:line-", result.stderr)
            self.assertNotIn(env["SYNTHETIC_SECRET"], result.stderr + (root / "steps.log").read_text())
            self.assertNotIn("SYNTHETIC_SECRET", (root / "steps.log").read_text())

    def test_timeout_reports_progress_and_requests_owned_mock_process_tree_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); env = self.environment(root)
            (root / "steps.log").write_text("1 database 123\n", encoding="utf-8")
            process = MagicMock(pid=12345)
            process.__enter__.return_value = process
            process.wait.side_effect = [subprocess.TimeoutExpired("mock", 60), 0]
            # Exercise timeout deterministically, without CPU-dependent sleeps or
            # sending any real signal to the synthetic PID.
            with patch.object(diagnostics.subprocess, "Popen", return_value=process), \
                    patch.object(diagnostics.subprocess, "run") as cleanup, \
                    patch.object(diagnostics.os, "killpg", create=True) as kill_group, \
                    self.assertRaisesRegex(AssertionError, r"Mock deploy exceeded 60s; mock-deploy elapsed=.*last=database:line-123") as caught:
                diagnostics.run([BASH, "mock-deploy"], env=env, trace=root / "steps.log", timeout=60)
            process.kill.assert_called_once_with()
            if os.name == "nt":
                self.assertEqual(["taskkill.exe", "/PID", "12345", "/T", "/F"], cleanup.call_args.args[0])
            else:
                kill_group.assert_called_once_with(12345, diagnostics.signal.SIGKILL)
            self.assertNotIn(env["SYNTHETIC_SECRET"], str(caught.exception))

    def test_unknown_mock_command_is_rejected_and_argv_record_is_lossless(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); env = self.environment(root)
            prepare(root, dict(compose=deployment_config(), container=container(), image=image(), probe=probe(),
                               extension="none", change_id=False, protected_incoming=False))
            path = root / "tools.sh"; path.write_text(FAKE_TOOLS, encoding="utf-8", newline="\n")
            args = ["docker", "rm", "quote' space\nsecond line"]
            result = subprocess.run([BASH, str(path), *args], env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(97, result.returncode)
            self.assertEqual([args], read_commands(root))
            self.assertNotIn(args[-1], result.stderr)

    def test_inline_python_relay_preserves_cli_globals_argv_and_stdin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); env = self.environment(root)
            driver = root / "driver.py"; driver.write_text(PYTHON_DRIVER, encoding="utf-8")
            code = "import json,sys; print(json.dumps([sys.argv, globals().get('__file__'), __name__, __package__, __spec__, sys.stdin.read()]))"
            for args, input_text in ((["-c", code, "argument"], "input"), (["-", "argument"], code)):
                with self.subTest(mode=args[0]):
                    expected = subprocess.run([sys.executable, *args], env=env, input=input_text, text=True, capture_output=True, check=True, timeout=10)
                    actual = subprocess.run([sys.executable, str(driver), *args], env=env, input=input_text, text=True, capture_output=True, check=True, timeout=10)
                    self.assertEqual(expected.stdout, actual.stdout)
