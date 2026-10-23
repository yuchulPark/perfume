"""Bounded diagnostics for disposable mock shells; never trace arguments or values."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time


def instrument(script):
    # FD 8 is separate from stdout, so command substitutions/JSON pipelines stay intact.
    # DEBUG observes command text only to select fixed labels; it never writes that text.
    return r'''exec 8>"$PERFUME_TEST_TRACE"
mock_phase=setup
set -ET
trap 'mock_line=$LINENO; case "$BASH_COMMAND" in verify_configuration) mock_phase=configuration ;; verify_database) mock_phase=database ;; git\ fetch*) mock_phase=git-fetch ;; git\ reset*) mock_phase=git-reset ;; compose\ build*) mock_phase=build-apps ;; compose\ up*) mock_phase=replace-apps ;; curl*) mock_phase=healthcheck ;; esac; printf "%s %s %s\n" "${EPOCHREALTIME:-0}" "$mock_phase" "$mock_line" >&8' DEBUG
trap 'printf "Mock-deploy-failure-at-line-%s\n" "$LINENO" >&2' ERR
''' + script


def summary(trace, elapsed):
    records = []
    for line in Path(trace).read_text(encoding="utf-8").splitlines() if Path(trace).exists() else []:
        parts = line.split()
        if len(parts) == 3 and parts[1] in {
            "setup", "configuration", "database", "git-fetch", "git-reset",
            "build-apps", "replace-apps", "healthcheck",
        } and parts[2].isdigit():
            try:
                records.append((float(parts[0]), parts[1], parts[2]))
            except ValueError:
                pass
    gaps = sorted(((max(0, b[0] - a[0]), a[1], a[2]) for a, b in zip(records, records[1:])), reverse=True)
    last = f"{records[-1][1]}:line-{records[-1][2]}" if records else "not-started"
    slow = ",".join(f"{phase}:line-{line}={seconds:.3f}s" for seconds, phase, line in gaps[:3])
    return f"mock-deploy elapsed={elapsed:.3f}s last={last} slowest=[{slow}]"


def run(command, *, env, trace, timeout=60):
    started = time.monotonic()
    # Files avoid an orphaned descendant keeping a capture pipe open after timeout.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as output, \
            tempfile.TemporaryFile(mode="w+", encoding="utf-8") as errors, \
            subprocess.Popen(command, env=env, text=True, stdout=output, stderr=errors,
                             start_new_session=os.name != "nt") as process:
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Kill only this owned mock process tree; do not leave pipe-holding children.
            if os.name == "nt":
                subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                               capture_output=True, timeout=5, check=False)
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            process.kill()
            process.wait(timeout=5)
            raise AssertionError(f"Mock deploy exceeded {timeout}s; "
                                 + summary(trace, time.monotonic() - started)) from None
        output.seek(0); errors.seek(0)
        stdout, stderr = output.read(), errors.read()
    detail = summary(trace, time.monotonic() - started)
    if os.environ.get("PERFUME_TEST_DEPLOY_TIMINGS") == "1":
        print(detail, flush=True)
    if process.returncode:
        stderr += "\n" + detail
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
