#!/usr/bin/env bash
# Run as a child, never source this file in a PuTTY login shell.
# This helper takes the existing deployment lock; it never calls Docker or changes DB/.env.
set +x
set -euo pipefail
test "$#" -eq 0
cd /srv/perfume
test "$(pwd -P)" = /srv/perfume
test -f .env
test ! -L .env
test -f .perfume-db-ready
test ! -L .perfume-db-ready
test "$(cat .perfume-db-ready)" = perfume-dev_pgdata
test ! -L .perfume-deploy.lock
command -v flock >/dev/null
exec 9>.perfume-deploy.lock
flock -n 9 || { echo "Another perfume operation holds the deployment lock." >&2; exit 75; }
printf 'Maintenance child shell: failures exit this shell; FD 9 holds the perfume deployment lock.\n'
# Retain the lock's open file description across exec. Closing all inherited FDs
# releases it automatically; do not explicitly unlock while a child command may run.
export PS1='(perfume-maintenance) \w\$ '
export HISTFILE=/dev/null
exec bash --noprofile --norc -e -u -o pipefail -i
