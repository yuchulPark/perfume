# syntax=docker/dockerfile:1
# No floating fallback: supply the digest of the image used by the running DB.
ARG POSTGRES_BASE_IMAGE
FROM ${POSTGRES_BASE_IMAGE} AS builder
ARG POSTGRES_BASE_IMAGE
ARG POSTGRES_SERVER_VERSION

RUN set -eu; \
    echo "$POSTGRES_BASE_IMAGE" | grep -Eq '^postgres:17-bookworm@sha256:[0-9a-f]{64}$'; \
    echo "$POSTGRES_SERVER_VERSION" | grep -Eq '^17\.[0-9]+$'; \
    . /etc/os-release; test "$VERSION_CODENAME" = bookworm; \
    test "$PG_MAJOR" = 17; \
    test "$(postgres --version | awk '{print $3}')" = "$POSTGRES_SERVER_VERSION"; \
    sha256sum /usr/lib/postgresql/17/bin/postgres > /tmp/postgres.sha256; \
    apt-mark hold postgresql-17 postgresql-client-17 locales; \
    apt-get update; \
    apt-get install -y --no-install-recommends build-essential "postgresql-server-dev-17=$PG_VERSION"; \
    sha256sum --check /tmp/postgres.sha256; \
    test "$(pg_config --version | awk '{print $2}')" = "$POSTGRES_SERVER_VERSION"

# This is the upstream release tag, not master or the pgvector image's PG tag.
ADD https://github.com/pgvector/pgvector.git#v0.8.7 /tmp/pgvector
RUN set -eu; \
    cd /tmp/pgvector; \
    grep -Eq '^EXTVERSION = 0\.8\.7$' Makefile; \
    make clean; \
    make -j1 OPTFLAGS=""; \
    make OPTFLAGS="" install DESTDIR=/out; \
    install -D -m 644 LICENSE /out/usr/share/doc/pgvector/LICENSE; \
    sha256sum --check /tmp/postgres.sha256

# The runtime starts again from the identical digest: no apt upgrades reach it.
FROM ${POSTGRES_BASE_IMAGE} AS runtime
ARG POSTGRES_BASE_IMAGE
ARG POSTGRES_SERVER_VERSION
COPY --from=builder /out/usr/lib/postgresql/17/ /usr/lib/postgresql/17/
COPY --from=builder /out/usr/share/postgresql/17/extension/ /usr/share/postgresql/17/extension/
COPY --from=builder /out/usr/share/doc/pgvector/ /usr/share/doc/pgvector/
COPY --from=builder /tmp/postgres.sha256 /tmp/postgres.sha256
RUN set -eu; \
    . /etc/os-release; test "$VERSION_CODENAME" = bookworm; \
    test "$PG_MAJOR" = 17; \
    test "$(postgres --version | awk '{print $3}')" = "$POSTGRES_SERVER_VERSION"; \
    sha256sum --check /tmp/postgres.sha256; \
    grep -Eq "^default_version = '0\.8\.7'$" /usr/share/postgresql/17/extension/vector.control; \
    ldd /usr/lib/postgresql/17/lib/vector.so > /tmp/vector.ldd; \
    if grep -q 'not found' /tmp/vector.ldd; then exit 1; fi; \
    rm /tmp/postgres.sha256 /tmp/vector.ldd
LABEL perfume.pgvector.base-image="${POSTGRES_BASE_IMAGE}" \
      perfume.pgvector.postgres-version="${POSTGRES_SERVER_VERSION}" \
      perfume.pgvector.extension-version="0.8.7"
# Retain the base image's user, entrypoint, PGDATA and initialization behavior.
