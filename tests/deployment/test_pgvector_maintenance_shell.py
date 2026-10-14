"""Exercise child-shell failure/FD ownership locally; never use SSH or Docker."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from test_jenkins_db_preservation import BASH
from test_pgvector_production import ROOT


@unittest.skipUnless(BASH and Path(BASH).exists(), "Bash required for local maintenance-shell checks.")
class MaintenanceShellTests(unittest.TestCase):
    def run_session(self, commands, busy=False, missing_env=False):
        with tempfile.TemporaryDirectory(prefix="perfume-maintenance-mock-") as directory:
            root = Path(directory)
            env = dict(os.environ, MSYS2_ARG_CONV_EXCL="*", MSYS2_ENV_CONV_EXCL="PERFUME_LOCK_LOG", PYTHONDONTWRITEBYTECODE="1")
            posix = subprocess.run([BASH, "--noprofile", "--norc", "-c", 'cd "$1" && pwd -P', "test", directory],
                                   env=env, text=True, capture_output=True, check=True).stdout.strip()
            if not missing_env:
                (root / ".env").write_text("fixture-only", encoding="utf-8")
            (root / ".perfume-db-ready").write_text("perfume-dev_pgdata\n", encoding="utf-8", newline="\n")
            (root / "fake-bin").mkdir()
            # Local Git Bash lacks flock. This stub checks the actual inherited FD,
            # while Linux/Ubuntu's kernel lock lifecycle is a separate integration check.
            flock = root / "fake-bin/flock"
            flock.write_text("#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" >>\"$PERFUME_LOCK_LOG\"\n"
                             "printf '' >&9\n[[ \"$PERFUME_LOCK_BUSY\" != 1 ]]\n", encoding="utf-8", newline="\n")
            flock.chmod(0o755)
            helper = (ROOT / "scripts/pgvector-maintenance-shell.sh").read_text(encoding="utf-8").replace("/srv/perfume", posix)
            helper = "export PATH='" + posix + "/fake-bin':\"$PATH\"\n" + helper
            (root / "helper.sh").write_text(helper, encoding="utf-8", newline="\n")
            parent = (
                "set -euo pipefail\n"
                "bash '" + posix + "/helper.sh' || printf '__CHILD_FAILED__\\n'\n"
                "printf '__PARENT_ALIVE__\\n'\n"
                "if { printf '' >&9; } 2>/dev/null; then printf '__FD_LEAK__\\n'; else printf '__PARENT_FD_CLOSED__\\n'; fi\n"
            )
            env.update(PERFUME_LOCK_LOG=posix + "/lock-calls", PERFUME_LOCK_BUSY="1" if busy else "0")
            result = subprocess.run([BASH, "--noprofile", "--norc", "-c", parent], env=env, input=commands,
                                    text=True, capture_output=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("__PARENT_ALIVE__", result.stdout)
            self.assertIn("__PARENT_FD_CLOSED__", result.stdout)
            self.assertNotIn("__FD_LEAK__", result.stdout)
            if missing_env:
                self.assertFalse((root / "lock-calls").exists())
                self.assertFalse((root / ".perfume-deploy.lock").exists())
            else:
                self.assertEqual("-n 9\n", (root / "lock-calls").read_text())
                self.assertTrue((root / ".perfume-deploy.lock").is_file())
            if "__CHILD_FD_OPEN__" in commands and not busy:
                self.assertIn("__CHILD_FD_OPEN__", (root / ".perfume-deploy.lock").read_text())
            return result

    def test_failed_command_exits_child_but_keeps_parent_shell(self):
        result = self.run_session("printf '__CHILD_FD_OPEN__\\n' >&9\nfalse\nprintf '__MUST_NOT_RUN__\\n'\n")
        self.assertIn("__CHILD_FAILED__", result.stdout)
        self.assertNotIn("__MUST_NOT_RUN__", result.stdout)

    def test_normal_exit_and_busy_lock_keep_parent_shell(self):
        result = self.run_session("exit 0\n")
        self.assertNotIn("__CHILD_FAILED__", result.stdout)
        result = self.run_session("printf '__MUST_NOT_RUN__\\n'\n", busy=True)
        self.assertIn("__CHILD_FAILED__", result.stdout)
        self.assertNotIn("__MUST_NOT_RUN__", result.stdout)

    def test_missing_env_aborts_before_taking_lock_or_opening_child_shell(self):
        result = self.run_session("printf '__MUST_NOT_RUN__\\n'\n", missing_env=True)
        self.assertIn("__CHILD_FAILED__", result.stdout)
        self.assertNotIn("__MUST_NOT_RUN__", result.stdout)


class BackupDocumentationTests(unittest.TestCase):
    def test_all_pg_dumpall_commands_use_short_initial_database_option(self):
        commands = []
        for directory in ("docs", "scripts"):
            for path in (ROOT / directory).rglob("*"):
                if path.suffix not in {".md", ".sh", ".py"}:
                    continue
                text = path.read_text(encoding="utf-8").replace("\\\n", " ")
                commands += re.findall(r"(?m)^.*(?:exec |\" )?pg_dumpall[^\n]*", text)
        self.assertGreaterEqual(len(commands), 2)
        for command in commands:
            self.assertNotRegex(command, r"--database(?:[=\s]|$)")
            if "--roles-only" in command:
                self.assertRegex(command, r"\s-l\s")


if __name__ == "__main__":
    unittest.main()
