#!/usr/bin/env python3
"""Bounded one-off Docker supervisor. No shell/eval, credentials, model pull or DB lifecycle operations."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone

ROOT = Path('/srv/perfume')
STATE = Path('/var/lib/perfume-embedding')
CONFIG = Path('/etc/perfume/embedding-batch.conf')
WORKER = 'perfume-dev-embedding-batch'
MARKER = ROOT / '.perfume-embedding-active'
DEFAULTS = {'ENABLED': 'false', 'MAX_ITEMS': '100', 'MAX_RUNTIME_SECONDS': '3600',
            'PAGE_SIZE': '25', 'DELAY_MS': '250', 'READ_TIMEOUT_MS': '300000',
            'MODEL': 'qwen3-embedding:0.6b', 'MODEL_DIGEST': ''}
# Accept only messages whose entire payload matches a known, content-free schema.
SAFE_LOG = re.compile(
    r'(?:Perfume embedding failed: perfumeId=\d+, code=[A-Z_]+'
    r'|Ollama embedding request failed: endpoint=/api/(?:tags|embed), code=[A-Z_]+, '
    r'httpStatus=\d+, elapsedMs=\d+, exceptionType=[A-Za-z0-9_$]+, rootCauseType=[A-Za-z0-9_$]+'
    r'|Automatic embedding started: startedAt=[0-9T:.\-]+Z'
    r'|Automatic embedding stopped: code=STATE_OR_EXECUTION_ERROR'
    r'|Automatic embedding finished: startedAt=[0-9T:.\-]+Z, finishedAt=[0-9T:.\-]+Z, '
    r'status=(?:SUCCESS|FAILED|INTERRUPTED|SKIPPED), code=[A-Z_]+, '
    r'total=-?\d+, available=-?\d+, target=-?\d+, processed=\d+, new=\d+, changed=\d+, skipped=\d+, '
    r'failed=\d+, lastId=\d+, upperId=-?\d+, hasMore=(?:true|false), interrupted=(?:true|false), '
    r'nextCursor=\d+, pendingRanges=\d+, elapsedMs=\d+)')


def emit(status, code):
    print(f'Automatic embedding supervisor: at={datetime.now(timezone.utc).isoformat()} status={status} code={code}', flush=True)


def settings(path=CONFIG):
    values = dict(DEFAULTS)
    if path.is_symlink() or not path.is_file():
        raise ValueError('CONFIG_INVALID')
    seen = set()
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key not in values or key in seen:
            raise ValueError('CONFIG_INVALID')
        seen.add(key)
        values[key] = value
    if values['ENABLED'] not in ('true', 'false'):
        raise ValueError('CONFIG_INVALID')
    for key, low, high in [('MAX_ITEMS', 1, 10000), ('MAX_RUNTIME_SECONDS', 1, 86400),
                           ('PAGE_SIZE', 1, 100), ('DELAY_MS', 0, 60000), ('READ_TIMEOUT_MS', 1, 300000)]:
        if not re.fullmatch(r'\d+', values[key]) or not low <= int(values[key]) <= high:
            raise ValueError('CONFIG_INVALID')
    if not re.fullmatch(r'[A-Za-z0-9._:/-]{1,128}', values['MODEL']):
        raise ValueError('CONFIG_INVALID')
    if values['MODEL_DIGEST'] and not re.fullmatch(r'(sha256:)?[0-9a-f]{64}', values['MODEL_DIGEST']):
        raise ValueError('CONFIG_INVALID')
    if values['ENABLED'] == 'true' and not values['MODEL_DIGEST']:
        raise ValueError('MODEL_DIGEST_REQUIRED')
    return values


def environment():
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(('COMPOSE_', 'DOCKER_')) or key in {
                'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'DB_URL', 'SCENTREV_API_KEY', 'PERFUME_DB_IMAGE'}:
            env.pop(key, None)
    # Docker must use the local daemon, independent of the login user's configured context.
    env['DOCKER_HOST'] = 'unix:///var/run/docker.sock'
    return env


def command(args, timeout=30):
    return subprocess.run(args, env=environment(), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL, text=True, timeout=timeout, check=True).stdout.strip()


def compose(*args):
    return ['docker', 'compose', '--project-name', 'perfume-dev', '--project-directory', str(ROOT),
            '--env-file', str(ROOT / '.env'), '--file', str(ROOT / 'compose.yaml'), *args]


def worker_id():
    return command(['docker', 'ps', '--all', '--quiet', '--filter', f'name=^/{WORKER}$'])


def require_deploy_lock():
    import fcntl
    opened = os.fstat(9)
    expected = (ROOT / '.perfume-deploy.lock').stat(follow_symlinks=False)
    if (opened.st_dev, opened.st_ino) != (expected.st_dev, expected.st_ino):
        raise ValueError('DEPLOY_LOCK_REQUIRED')
    fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)


def preflight():
    backend = command(compose('ps', '--quiet', 'backend'))
    db = command(compose('ps', '--quiet', 'db'))
    if not re.fullmatch('[0-9a-f]+', backend) or not re.fullmatch('[0-9a-f]+', db):
        raise ValueError('SERVICES_UNAVAILABLE')
    volume = command(['docker', 'inspect', '--format',
                      '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}', db])
    if volume != 'perfume-dev_pgdata' or command(['docker', 'inspect', '--format', '{{.State.Health.Status}}', db]) != 'healthy':
        raise ValueError('DATABASE_UNAVAILABLE')
    running_image = command(['docker', 'inspect', '--format', '{{.Image}}', backend])
    if command(['docker', 'image', 'inspect', '--format', '{{.Id}}', 'perfume-dev-backend']) != running_image:
        raise ValueError('IMAGE_MISMATCH')


def stop_owned(owner):
    container = worker_id()
    if container:
        labels = json.loads(command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', container]))
        if (labels.get('com.docker.compose.project') != 'perfume-dev'
                or labels.get('com.docker.compose.service') != 'backend'
                or labels.get('com.docker.compose.oneoff', '').lower() != 'true'
                or labels.get('com.perfume.embedding.owner') != owner):
            raise ValueError('WORKER_OWNER_MISMATCH')
        command(['docker', 'stop', '--time', '400', container], timeout=420)


def stream_safe(output):
    for line in output:
        # A Docker or Spring exception can contain secrets. Only full known message payloads pass.
        for marker in ('Perfume embedding failed:', 'Ollama embedding request failed:', 'Automatic embedding '):
            index = line.find(marker)
            if index >= 0:
                payload = line[index:].strip()
                if SAFE_LOG.fullmatch(payload):
                    print(payload, flush=True)
                break


def run(values, once=False):
    if values['ENABLED'] != 'true' and not once:
        emit('SKIPPED', 'DISABLED')
        return 0
    if not values['MODEL_DIGEST']:
        raise ValueError('MODEL_DIGEST_REQUIRED')
    require_deploy_lock()
    if STATE.is_symlink() or not STATE.is_dir() or MARKER.is_symlink():
        raise ValueError('STATE_INVALID')
    # Never take over or remove an orphan worker. A stale marker is recoverable only after absence is proven.
    if worker_id():
        emit('SKIPPED', 'WORKER_EXISTS')
        return 75
    if MARKER.exists():
        MARKER.unlink()
    preflight()
    owner = uuid.uuid4().hex
    descriptor = os.open(MARKER, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, 'w') as marker:
        marker.write(owner + '\n')
        marker.flush()
        os.fsync(marker.fileno())
    args = compose('run', '--rm', '--no-deps', '--pull', 'never', '-T', '--name', WORKER,
                   '--label', f'com.perfume.embedding.owner={owner}',
                   '--volume', f'{STATE}:/var/lib/perfume-embedding', '--entrypoint', 'java', 'backend',
                   '-Xms128m', '-Xmx512m', '-jar', '/app/app.jar')
    properties = {
        'perfume.ai.embedding.batch.enabled': 'true', 'perfume.ai.embedding.auto.enabled': 'true',
        'perfume.ai.embedding.auto.state-directory': '/var/lib/perfume-embedding',
        'perfume.ai.embedding.auto.max-runtime-seconds': values['MAX_RUNTIME_SECONDS'],
        'perfume.ai.embedding.model': values['MODEL'], 'perfume.ai.embedding.model-digest': values['MODEL_DIGEST'],
        'perfume.ai.embedding.base-url': 'http://ollama:11434', 'perfume.ai.embedding.batch.after-id': '0',
        'perfume.ai.embedding.batch.max-items': values['MAX_ITEMS'], 'perfume.ai.embedding.batch.page-size': values['PAGE_SIZE'],
        'perfume.ai.embedding.batch.delay-ms': values['DELAY_MS'], 'perfume.ai.embedding.read-timeout-ms': values['READ_TIMEOUT_MS'],
        'scentrev.brand-import': 'false', 'perfume.ai.search.enabled': 'false', 'perfume.ai.recommend.enabled': 'false',
        'spring.jpa.hibernate.ddl-auto': 'validate', 'spring.sql.init.mode': 'never', 'spring.jpa.show-sql': 'false',
        'spring.main.banner-mode': 'off', 'logging.level.root': 'OFF',
        'logging.level.com.perfume.ai.embedding': 'INFO',
        'spring.datasource.hikari.connection-timeout': '10000',
        'spring.datasource.hikari.data-source-properties.connectTimeout': '10',
        'spring.datasource.hikari.data-source-properties.socketTimeout': '30',
    }
    args += [f'--{key}={value}' for key, value in properties.items()]
    requested = threading.Event()
    previous = {sig: signal.signal(sig, lambda *_: requested.set()) for sig in (signal.SIGTERM, signal.SIGINT)}
    process = None
    started = time.monotonic()
    started_at = datetime.now(timezone.utc)
    emit('RUNNING', 'STARTED')
    try:
        process = subprocess.Popen(args, env=environment(), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, pass_fds=(9,))
        reader = threading.Thread(target=stream_safe, args=(process.stdout,), daemon=True)
        reader.start()
        timed_out = False
        while process.poll() is None:
            if requested.wait(0.2) or time.monotonic() - started >= int(values['MAX_RUNTIME_SECONDS']):
                timed_out = not requested.is_set()
                stop_owned(owner)
                # Covers a signal during Docker create/start before the worker becomes visible.
                if process.poll() is None:
                    process.terminate()
                break
        try:
            result = process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            result = process.wait(timeout=10)
        reader.join(timeout=5)
        if requested.is_set() or timed_out:
            outcome, code, exit_status = 'FAILED', 'TIME_LIMIT' if timed_out else 'STOPPED', 1
        elif result != 0:
            outcome, code, exit_status = 'FAILED', 'WORKER_FAILED', 1
        else:
            # A zero exit without this invocation's durable report is not completion (e.g. old image).
            checkpoint = STATE / 'state.json'
            if checkpoint.is_symlink() or not checkpoint.is_file():
                raise ValueError('RESULT_MISSING')
            latest = json.loads(checkpoint.read_text(encoding='utf-8')).get('latest') or {}
            if datetime.fromisoformat(latest.get('startedAt', '')) < started_at:
                raise ValueError('RESULT_STALE')
            if latest.get('status') == 'SKIPPED' and latest.get('code') == 'BATCH_BUSY':
                outcome, code, exit_status = 'SKIPPED', 'BATCH_BUSY', 75
            elif latest.get('status') == 'SUCCESS' and latest.get('code') == 'OK':
                outcome, code, exit_status = 'SUCCESS', 'COMPLETED', 0
            else:
                raise ValueError('RESULT_INVALID')
    finally:
        try:
            # Retain the deploy lock and marker until the worker is confirmed absent/stopped.
            stop_owned(owner)
            if not worker_id():
                MARKER.unlink(missing_ok=True)
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
    emit(outcome, code)
    return exit_status


def status(include_database=False):
    values = settings()
    print(f'configured_enabled={values["ENABLED"]} max_items={values["MAX_ITEMS"]} max_runtime_seconds={values["MAX_RUNTIME_SECONDS"]}')
    for unit in ('perfume-embedding.timer', 'perfume-embedding.service'):
        for operation in ('is-enabled', 'is-active'):
            result = subprocess.run(['systemctl', operation, unit], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
            value = result.stdout.strip()
            print(f'{unit} {operation}={value if re.fullmatch("[a-z-]+", value) else "unknown"}')
    for unit, properties in (
            ('perfume-embedding.timer', ['NextElapseUSecRealtime']),
            ('perfume-embedding.service', ['Result', 'ExecMainStatus', 'ExecMainStartTimestamp', 'ExecMainExitTimestamp'])):
        try:
            print(command(['systemctl', 'show', unit, *[f'--property={name}' for name in properties]]))
        except subprocess.CalledProcessError:
            # The initial disabled/manual validation intentionally has no installed timer yet.
            print(f'{unit} properties=unavailable')
    path = STATE / 'state.json'
    if path.is_file() and not path.is_symlink():
        data = json.loads(path.read_text(encoding='utf-8'))
        # State contains no source text/vector; use only known result metadata.
        print(json.dumps({key: data.get(key) for key in ('lastSuccessAt', 'latest')}, ensure_ascii=True))
    else:
        print('checkpoint=absent')
    print(f'orphan_guard={MARKER.exists()} worker_present={bool(worker_id())}')
    if include_database:
        if not values['MODEL_DIGEST']:
            raise ValueError('MODEL_DIGEST_REQUIRED')
        digest = values['MODEL_DIGEST'].removeprefix('sha256:')
        # All substitutions are validated identifiers/digests. Query returns counts only.
        sql = ("WITH p AS (SELECT count(*) AS total FROM public.perfumes), e AS ("
               "SELECT count(DISTINCT e.perfume_id) AS matched FROM ai.perfume_embeddings e "
               "JOIN public.perfumes p ON p.id=e.perfume_id "
               f"WHERE model_name='{values['MODEL']}' AND model_digest='sha256:{digest}' AND text_version='perfume-v1') "
               "SELECT total, matched AS embedded, total-matched AS missing_estimate, "
               "round(100.0*matched/nullif(total,0),2) AS coverage_percent FROM p,e;")
        print(command(compose('exec', '-T', 'db', 'psql', '-X', '--no-password', '--host', '/var/run/postgresql',
                              '--username', 'perfume_user', '--dbname', 'perfume', '--set', 'ON_ERROR_STOP=1', '-c', sql)))


def main():
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--once', action='store_true', help='Explicit administrator run, even while timer execution is disabled')
    modes.add_argument('--check', action='store_true', help='Validate local config only; no Docker, DB or Ollama calls')
    modes.add_argument('--status', action='store_true', help='Read systemd/checkpoint/Docker status')
    modes.add_argument('--status-db', action='store_true', help='Also query read-only catalog/embedding counts')
    args = parser.parse_args()
    try:
        if args.status or args.status_db:
            status(args.status_db)
            return 0
        values = settings()
        if args.check:
            emit('SUCCESS', 'CONFIG_VALID_DISABLED' if values['ENABLED'] == 'false' else 'CONFIG_VALID_ENABLED')
            return 0
        return run(values, args.once)
    except (OSError, ValueError, TypeError, subprocess.SubprocessError):
        emit('FAILED', 'PREFLIGHT_OR_SUPERVISOR_ERROR')
        return 1


if __name__ == '__main__':
    sys.exit(main())
