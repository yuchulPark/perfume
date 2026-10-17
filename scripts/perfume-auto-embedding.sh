#!/usr/bin/env bash
# systemd entry point; never source from a login/maintenance shell.
set +x
set -euo pipefail
umask 077
cd /srv/perfume
test "$(pwd -P)" = /srv/perfume
test -f .env && test ! -L .env
test -f .perfume-db-ready && test ! -L .perfume-db-ready
test "$(cat .perfume-db-ready)" = perfume-dev_pgdata
test ! -L .perfume-deploy.lock
exec 9>.perfume-deploy.lock
flock -n 9 || { printf 'Automatic embedding: status=SKIPPED code=DEPLOY_BUSY\n'; exit 75; }
exec python3 /srv/perfume/scripts/perfume-auto-embedding.py "$@"
