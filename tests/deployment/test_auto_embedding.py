"""All Docker/systemd/process operations mocked; real Ubuntu/DB/Ollama are never contacted."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from test_jenkins_db_preservation import BASH
from test_pgvector_production import ROOT

spec = importlib.util.spec_from_file_location('auto_embedding', ROOT / 'scripts/perfume-auto-embedding.py')
auto = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auto)


class AutoEmbeddingSupervisorTests(unittest.TestCase):
    def config(self, directory, extra=''):
        path = Path(directory) / 'config'
        path.write_text('ENABLED=false\n' + extra, encoding='utf-8')
        return path

    def test_default_disabled_performs_no_docker_systemd_or_process_operation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(auto, 'command') as command, patch.object(auto.subprocess, 'Popen') as popen:
            values = auto.settings(self.config(directory))
            self.assertEqual(0, auto.run(values))
            command.assert_not_called()
            popen.assert_not_called()

    def test_settings_reject_invalid_limits_digest_boolean_duplicates_and_unknown_keys(self):
        for extra in ('MAX_ITEMS=0', 'MAX_ITEMS=10001', 'MAX_RUNTIME_SECONDS=0', 'MAX_RUNTIME_SECONDS=86401',
                      'PAGE_SIZE=101', 'DELAY_MS=-1', 'READ_TIMEOUT_MS=300001', 'MODEL_DIGEST=abc',
                      'MODEL=a\'bad', 'ENABLED=yes', 'MAX_ITEMS=5\nMAX_ITEMS=6', 'COMMAND=evil', 'MAX_ITEMS=$(secret)'):
            with self.subTest(extra=extra), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    auto.settings(self.config(directory, extra))
        with tempfile.TemporaryDirectory() as directory:
            config = self.config(directory)
            config.write_text('ENABLED=true\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                auto.settings(config)

    def test_environment_uses_local_daemon_and_drops_inherited_compose_credentials(self):
        with patch.dict(os.environ, {'COMPOSE_FILE': 'other.yaml', 'DOCKER_CONTEXT': 'remote', 'DB_PASSWORD': 'private',
                                     'DB_URL': 'external', 'SCENTREV_API_KEY': 'private', 'PERFUME_DB_IMAGE': 'wrong'}):
            env = auto.environment()
            self.assertEqual('unix:///var/run/docker.sock', env['DOCKER_HOST'])
            for key in ('COMPOSE_FILE', 'DOCKER_CONTEXT', 'DB_PASSWORD', 'DB_URL', 'SCENTREV_API_KEY', 'PERFUME_DB_IMAGE'):
                self.assertNotIn(key, env)

    def supervise(self, returncode=0, timeout=False, orphan=False, durable=True, checkpoint_status='SUCCESS', stale=False, checkpoint_code=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); state = root / 'state'; state.mkdir(); marker = root / '.perfume-embedding-active'
            process = MagicMock()
            process.stdout = io.StringIO('unsafe-password body\nPerfume embedding failed: perfumeId=1, code=TIMEOUT\n')
            process.poll.side_effect = [None, None] if timeout else [None, returncode]
            process.wait.return_value = returncode
            values = dict(auto.DEFAULTS, MODEL_DIGEST='a' * 64, MAX_RUNTIME_SECONDS='1' if timeout else '3600')
            clock = [0, 2] if timeout else [0, 0]
            def spawn(*_args, **_kwargs):
                if durable:
                    (state / 'state.json').write_text(json.dumps({'latest': {
                        'startedAt': '2000-01-01T00:00:00Z' if stale else auto.datetime.now(auto.timezone.utc).isoformat(),
                        'status': checkpoint_status, 'code': checkpoint_code or ('BATCH_BUSY' if checkpoint_status == 'SKIPPED' else 'OK')}}), encoding='utf-8')
                return process
            with patch.object(auto, 'ROOT', root), patch.object(auto, 'STATE', state), patch.object(auto, 'MARKER', marker), \
                 patch.object(auto, 'require_deploy_lock'), \
                 patch.object(auto, 'worker_id', return_value='worker' if orphan else ''), \
                 patch.object(auto, 'preflight') as preflight, patch.object(auto, 'stop_owned') as stop, \
                 patch.object(auto.subprocess, 'Popen', side_effect=spawn) as popen, \
                 patch.object(auto.signal, 'signal', return_value=auto.signal.SIG_DFL), \
                 patch.object(auto.time, 'monotonic', side_effect=clock), contextlib.redirect_stdout(io.StringIO()) as output:
                result = auto.run(values, once=True)
                if orphan:
                    popen.assert_not_called(); preflight.assert_not_called(); stop.assert_not_called()
                else:
                    args = popen.call_args.args[0]
                    self.assertEqual(('--no-deps' in args, '--rm' in args, '-T' in args), (True, True, True))
                    self.assertEqual('never', args[args.index('--pull') + 1])
                    self.assertIn('--perfume.ai.embedding.auto.enabled=true', args)
                    self.assertIn('--perfume.ai.embedding.batch.after-id=0', args)
                    self.assertIn('--spring.jpa.hibernate.ddl-auto=validate', args)
                    self.assertIn('--spring.sql.init.mode=never', args)
                    self.assertEqual((9,), popen.call_args.kwargs['pass_fds'])
                    self.assertFalse(marker.exists())
                    self.assertNotIn('unsafe-password', output.getvalue())
                return result, output.getvalue(), process

    def test_administrator_can_run_once_while_disabled_using_only_one_off_worker(self):
        result, output, _ = self.supervise()
        self.assertEqual(0, result)
        self.assertIn('status=SUCCESS code=COMPLETED', output)

    def test_worker_failure_and_runtime_limit_are_not_reported_as_success(self):
        result, output, _ = self.supervise(returncode=1)
        self.assertEqual(1, result)
        self.assertIn('status=FAILED code=WORKER_FAILED', output)
        result, output, process = self.supervise(returncode=143, timeout=True)
        self.assertEqual(1, result)
        self.assertIn('status=FAILED code=TIME_LIMIT', output)
        process.terminate.assert_called_once()

    def test_orphan_worker_is_skipped_without_takeover_or_second_process(self):
        result, output, _ = self.supervise(orphan=True)
        self.assertEqual(75, result)
        self.assertIn('status=SKIPPED code=WORKER_EXISTS', output)

    def test_zero_exit_requires_a_fresh_durable_success_report_and_preserves_busy_skip(self):
        with self.assertRaisesRegex(ValueError, 'RESULT_MISSING'):
            self.supervise(durable=False)
        with self.assertRaisesRegex(ValueError, 'RESULT_STALE'):
            self.supervise(stale=True)
        with self.assertRaisesRegex(ValueError, 'RESULT_INVALID'):
            self.supervise(checkpoint_status='FAILED')
        result, output, _ = self.supervise(checkpoint_status='SKIPPED')
        self.assertEqual(75, result)
        self.assertIn('status=SKIPPED code=BATCH_BUSY', output)
        self.assertNotIn('status=SUCCESS', output)

    def test_stop_only_targets_our_exact_one_off_container_and_never_the_web_or_db(self):
        labels = {'com.docker.compose.project': 'perfume-dev', 'com.docker.compose.service': 'backend',
                  'com.docker.compose.oneoff': 'True', 'com.perfume.embedding.owner': 'owner'}
        with patch.object(auto, 'worker_id', return_value='c' * 64), patch.object(auto, 'command', return_value=json.dumps(labels)) as command:
            auto.stop_owned('owner')
            self.assertEqual(['docker', 'stop', '--time', '400', 'c' * 64], command.call_args.args[0])
        labels['com.perfume.embedding.owner'] = 'someone-else'
        with patch.object(auto, 'worker_id', return_value='other'), patch.object(auto, 'command', return_value=json.dumps(labels)) as command:
            with self.assertRaisesRegex(ValueError, 'OWNER_MISMATCH'):
                auto.stop_owned('owner')
            self.assertEqual(1, command.call_count)

    def test_intermediate_checkpoint_cannot_be_mistaken_for_a_completed_invocation(self):
        with self.assertRaisesRegex(ValueError, 'RESULT_INVALID'):
            self.supervise(checkpoint_status='RUNNING', checkpoint_code='IN_PROGRESS')

    def test_cumulative_multi_range_report_is_filtered_without_hiding_pending_or_failure(self):
        prefix = 'Automatic embedding finished: startedAt=2026-10-18T00:00:00Z, finishedAt=2026-10-18T01:00:00Z, '
        reports = [
            prefix + 'status=SUCCESS, code=OK, total=2294, available=2169, target=1000, processed=1000, new=900, changed=0, skipped=100, failed=0, lastId=1129, upperId=2332, hasMore=true, interrupted=false, nextCursor=1129, pendingRanges=2, elapsedMs=3600000',
            prefix + 'status=FAILED, code=PARTIAL_FAILURE, total=2294, available=2169, target=1000, processed=1000, new=900, changed=0, skipped=99, failed=1, lastId=129, upperId=2332, hasMore=true, interrupted=false, nextCursor=1129, pendingRanges=2, elapsedMs=3600000',
            prefix + 'status=INTERRUPTED, code=TIME_LIMIT, total=2294, available=2169, target=1000, processed=300, new=250, changed=0, skipped=49, failed=1, lastId=429, upperId=2332, hasMore=true, interrupted=true, nextCursor=429, pendingRanges=3, elapsedMs=3600000']
        with contextlib.redirect_stdout(io.StringIO()) as output:
            auto.stream_safe(io.StringIO('\n'.join(reports + [reports[0] + ' private-password',
                                 'Automatic embedding finished: status=SUCCESS vector=[1,2,3]']) + '\n'))
        self.assertEqual(reports, output.getvalue().splitlines())

    def test_docker_or_spring_errors_and_malformed_safe_messages_do_not_leak(self):
        lines = ['DB_PASSWORD=private\n', 'Ollama response: [1,2,3] SECRET_SOURCE\n',
                 'Automatic embedding stopped: password=private\n',
                 'Perfume embedding failed: perfumeId=1, code=TIMEOUT private-password\n',
                 'INFO Perfume embedding failed: perfumeId=9, code=SOURCE_CHANGED\n',
                 'WARN Ollama embedding request failed: endpoint=/api/embed, code=TIMEOUT, httpStatus=0, elapsedMs=10, exceptionType=ResourceAccessException, rootCauseType=SocketTimeoutException\n',
                 'INFO Automatic embedding finished: startedAt=2026-10-18T00:00:00Z, finishedAt=2026-10-18T00:01:00Z, status=FAILED, code=PARTIAL_FAILURE, total=10, available=10, target=5, processed=5, new=2, changed=0, skipped=2, failed=1, lastId=8, upperId=100, hasMore=true, interrupted=false, nextCursor=8, pendingRanges=2, elapsedMs=60000\n']
        with contextlib.redirect_stdout(io.StringIO()) as output:
            auto.stream_safe(io.StringIO(''.join(lines)))
        self.assertNotIn('private', output.getvalue())
        self.assertNotIn('SECRET_SOURCE', output.getvalue())
        self.assertIn('perfumeId=9, code=SOURCE_CHANGED', output.getvalue())
        self.assertIn('rootCauseType=SocketTimeoutException', output.getvalue())
        self.assertIn('status=FAILED, code=PARTIAL_FAILURE, total=10', output.getvalue())

    def test_preflight_rejects_mismatched_backend_image_before_start(self):
        with patch.object(auto, 'command', side_effect=['a' * 64, 'b' * 64, 'perfume-dev_pgdata', 'healthy', 'image-a', 'image-b']):
            with self.assertRaisesRegex(ValueError, 'IMAGE_MISMATCH'):
                auto.preflight()

    def test_status_reports_effective_enable_flag_and_optional_read_only_coverage_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            values = dict(auto.DEFAULTS, MODEL_DIGEST='a' * 64)
            unit = MagicMock(stdout='inactive\n')
            with patch.object(auto, 'STATE', root), patch.object(auto, 'MARKER', root / 'marker'), \
                 patch.object(auto, 'settings', return_value=values), patch.object(auto, 'worker_id', return_value=''), \
                 patch.object(auto.subprocess, 'run', return_value=unit), patch.object(auto, 'command', return_value='') as command, \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                auto.status(False)
                self.assertIn('configured_enabled=false', output.getvalue())
                self.assertIn('checkpoint=absent', output.getvalue())
                self.assertTrue(all(call.args[0][0] == 'systemctl' for call in command.call_args_list))
                command.reset_mock()
                command.side_effect = [subprocess.CalledProcessError(1, ['systemctl']), '', 'counts-only']
                auto.status(True)
                self.assertIn('perfume-embedding.timer properties=unavailable', output.getvalue())
                sql_args = command.call_args.args[0]
                self.assertIn('psql', sql_args)
                sql = sql_args[-1]
                self.assertIn('missing_estimate', sql)
                self.assertIn('coverage_percent', sql)
                self.assertIn("model_digest='sha256:" + 'a' * 64, sql)
                self.assertNotRegex(sql, r'(?i)\b(?:insert|update|delete|alter|create|drop)\b')
                self.assertNotIn('source_text', sql)
                self.assertNotIn('embedding,', sql)


@unittest.skipUnless(BASH and Path(BASH).exists(), 'Git Bash required for local shell checks')
class AutoEmbeddingShellTests(unittest.TestCase):
    def test_deploy_lock_conflict_skips_before_python_or_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, value in (('.env', 'fixture'), ('.perfume-db-ready', 'perfume-dev_pgdata\n')):
                (root / name).write_text(value, encoding='utf-8')
            env = dict(os.environ, MSYS2_ARG_CONV_EXCL='*')
            posix = subprocess.run([BASH, '--noprofile', '--norc', '-c', 'cd "$1" && pwd -P', 'test', directory],
                                   env=env, capture_output=True, text=True, check=True).stdout.strip()
            fake = root / 'fake-bin'; fake.mkdir()
            (fake / 'flock').write_text('#!/usr/bin/env bash\nexit 1\n', encoding='utf-8', newline='\n')
            (fake / 'flock').chmod(0o755)
            text = (ROOT / 'scripts/perfume-auto-embedding.sh').read_text(encoding='utf-8').replace('/srv/perfume', posix)
            text = 'export PATH="' + posix + '/fake-bin:$PATH"\n' + text
            script = root / 'worker.sh'; script.write_text(text, encoding='utf-8', newline='\n')
            result = subprocess.run([BASH, '--noprofile', '--norc', str(script)], env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(75, result.returncode, result.stderr)
            self.assertIn('status=SKIPPED code=DEPLOY_BUSY', result.stdout)
            self.assertTrue((root / '.perfume-deploy.lock').exists())

    def test_service_has_no_restart_and_timer_has_no_chosen_schedule(self):
        service = (ROOT / 'deploy/systemd/perfume-embedding.service').read_text(encoding='utf-8')
        self.assertIn('Restart=no', service)
        self.assertIn('SuccessExitStatus=75', service)
        timer = (ROOT / 'deploy/systemd/perfume-embedding.timer').read_text(encoding='utf-8')
        self.assertNotRegex(timer, r'(?m)^On(?:Calendar|BootSec|UnitActiveSec)=')
        self.assertIn('Persistent=false', timer)


if __name__ == '__main__':
    unittest.main()
