"""Operate only the isolated pgvector rehearsal; never connect to the source DB."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROJECT = "perfume-pgvector-rehearsal"
SERVICE = "rehearsal-db"
VOLUME = PROJECT + "_pgdata"
NETWORK = PROJECT + "_rehearsal"
DATABASE = "perfume_pgvector_rehearsal"
ADMIN = "perfume_pgvector_admin"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_config(config):
    require(config.get("name") == PROJECT, "Unexpected rehearsal project.")
    require(set(config.get("services", {})) == {SERVICE}, "Only the rehearsal DB is allowed.")
    volumes, networks = config.get("volumes", {}), config.get("networks", {})
    require(set(volumes) == {"rehearsal-pgdata"}, "Unexpected volume definitions.")
    volume = volumes["rehearsal-pgdata"]
    require(volume.get("name") == VOLUME and not any(volume.get(k) for k in ("external", "driver_opts", "driver")),
            "The volume must be an independent, Compose-owned test volume.")
    require(set(networks) == {"rehearsal"}, "Unexpected network definitions.")
    network = networks["rehearsal"]
    require(network.get("name") == NETWORK and network.get("internal") is True
            and not any(network.get(k) for k in ("external", "driver_opts", "driver", "ipam")), "Unexpected test network.")
    db = config["services"][SERVICE]
    for key in ("ports", "container_name", "network_mode", "privileged", "devices", "gpus", "volumes_from",
                "depends_on", "entrypoint", "command", "post_start", "pre_stop", "configs", "secrets"):
        require(not db.get(key), "Unexpected rehearsal service override: " + key)
    require(set(db.get("networks", {})) == {"rehearsal"}, "Unexpected network attachment.")
    mounts = db.get("volumes", [])
    require(len(mounts) == 1 and mounts[0].get("type") == "volume"
            and mounts[0].get("source") == "rehearsal-pgdata" and mounts[0].get("target") == "/var/lib/postgresql/data"
            and not mounts[0].get("volume", {}).get("subpath"), "Unexpected test data mount.")
    require(db.get("restart") == "no" and float(db.get("cpus", 0)) == 1
            and int(db.get("mem_limit", 0)) == 2 * 1024**3, "Unexpected resource limits.")
    build = db.get("build", {})
    require(Path(build.get("context", "")).resolve() == ROOT / "docker" / "pgvector"
            and build.get("dockerfile") == "Dockerfile", "Unexpected image build context.")
    args = build.get("args", {})
    require(set(args) == {"POSTGRES_BASE_IMAGE", "POSTGRES_SERVER_VERSION"}, "Unexpected build arguments.")
    require(re.fullmatch(r"postgres:17-bookworm@sha256:[0-9a-f]{64}", args.get("POSTGRES_BASE_IMAGE", "")) is not None,
            "Supply the exact running postgres:17-bookworm image repo digest.")
    version = args.get("POSTGRES_SERVER_VERSION", "")
    require(re.fullmatch(r"17\.(0|[1-9][0-9]*)", version) is not None, "Supply the exact running PostgreSQL 17 minor version.")
    require(db.get("image") == f"{PROJECT}-db:pg{version}-vector0.8.7", "Unexpected test image.")
    env = db.get("environment", {})
    require(set(env) == {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_INITDB_ARGS",
                         "REHEARSAL_EXPECTED_SERVER_VERSION_NUM"}, "Unexpected database environment overrides.")
    require(env.get("POSTGRES_DB") == DATABASE and env.get("POSTGRES_USER") == ADMIN, "Unexpected test database or role.")
    require(bool(env.get("POSTGRES_PASSWORD")) and not env["POSTGRES_PASSWORD"].startswith("REPLACE_"), "Set a separate test password.")
    expected_num = str(170000 + int(version.split(".")[1]))
    require(str(env.get("REHEARSAL_EXPECTED_SERVER_VERSION_NUM")) == expected_num, "server_version_num must match the exact minor version.")
    init_args = env.get("POSTGRES_INITDB_ARGS", "")
    require(re.fullmatch(r"--encoding=UTF8 --locale=[A-Za-z0-9_.@-]+", init_args) is not None
            and "REPLACE_" not in init_args, "Set the verified source UTF8/libc locale.")
    return db


def validate_resource(metadata, kind, name):
    require(metadata.get("Name") == name, "Unexpected " + kind + " name.")
    labels = metadata.get("Labels") or {}
    label_key = "com.docker.compose." + ("volume" if kind == "volume" else "network")
    expected_key = "rehearsal-pgdata" if kind == "volume" else "rehearsal"
    require(labels.get("com.docker.compose.project") == PROJECT and labels.get(label_key) == expected_key,
            "Refusing a resource not owned by the rehearsal project.")
    require(metadata.get("Driver") == ("local" if kind == "volume" else "bridge"), "Unexpected resource driver.")
    require(not metadata.get("Options"), "Resource driver options are prohibited.")
    if kind == "network":
        require(metadata.get("Internal") is True, "The test network must be internal.")


def validate_container(metadata):
    labels = metadata.get("Config", {}).get("Labels") or {}
    require(labels.get("com.docker.compose.project") == PROJECT and labels.get("com.docker.compose.service") == SERVICE,
            "Refusing a container outside the rehearsal project/service.")
    mounts = metadata.get("Mounts", [])
    require(len(mounts) == 1 and mounts[0].get("Type") == "volume" and mounts[0].get("Name") == VOLUME
            and mounts[0].get("Destination") == "/var/lib/postgresql/data", "Container is not using the isolated test volume.")
    require(set(metadata.get("NetworkSettings", {}).get("Networks", {})) == {NETWORK}, "Unexpected container network.")
    host = metadata.get("HostConfig", {})
    require(not host.get("Privileged") and not host.get("PortBindings") and not host.get("VolumesFrom"), "Unsafe container settings.")


def read_manifest(path):
    records = {}
    with Path(path).open(encoding="utf-8") as stream:
        for line in stream:
            if not line.strip():
                continue
            record = json.loads(line)
            key = (record["kind"], record["key"])
            require(key not in records, "Duplicate manifest record.")
            records[key] = record["value"]
    require(records.get(("manifest", "complete")) == 1, "Incomplete manifest.")
    require(all(key in records for key in (("server", "version"), ("database", "properties"), ("inventory", "tables"),
                                          ("data", "public.perfumes"), ("data", "public.brands"))), "Missing perfume verification records.")
    return records


def compare_manifests(source, clone):
    changed = sorted(key for key in source.keys() | clone.keys() if key not in source or key not in clone or source[key] != clone[key])
    if changed:
        # Object identifiers only; do not dump data, role settings or credentials.
        raise ValueError("Manifest mismatch: " + ", ".join(kind + ":" + key for kind, key in changed[:20]))
    return source[("inventory", "tables")]


def sql_identifier(value):
    require(bool(value) and "\x00" not in value, "Invalid source owner name.")
    return '"' + value.replace('"', '""') + '"'


class Rehearsal:
    def __init__(self, env_file):
        self.compose = ["docker", "compose", "--project-name", PROJECT, "--project-directory", str(ROOT),
                        "--env-file", str(Path(env_file).resolve()), "--file", str(ROOT / "compose.pgvector-rehearsal.yaml")]
        self.db = validate_config(json.loads(self.run(self.compose + ["config", "--format", "json"])))

    @staticmethod
    def run(command, input_bytes=None, stream=None):
        result = subprocess.run(command, input=input_bytes, stdin=stream, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(result.returncode == 0, "Docker/SQL command failed; no further steps were executed. Target: isolated rehearsal only.")
        return result.stdout.decode("utf-8")

    def resource(self, kind, name):
        # A successful listing distinguishes absence from an inaccessible daemon.
        found = self.run(["docker", kind, "ls", "--format", "{{.Name}}"])
        if name not in found.splitlines():
            return None
        metadata = json.loads(self.run(["docker", kind, "inspect", name]))[0]
        validate_resource(metadata, kind, name)
        return metadata

    def container(self, required=True):
        ids = self.run(["docker", "ps", "--all", "--filter", "label=com.docker.compose.project=" + PROJECT,
                        "--format", "{{.ID}}"]).splitlines()
        require(len(ids) <= 1, "Unexpected extra rehearsal containers; refusing changes.")
        if not ids:
            require(not required, "No test container. Run start first.")
            return None
        metadata = json.loads(self.run(["docker", "inspect", ids[0]]))[0]
        validate_container(metadata)
        require(self.resource("volume", VOLUME) is not None, "Missing rehearsal volume.")
        require(self.resource("network", NETWORK) is not None, "Missing rehearsal network.")
        return metadata

    def check_image(self, image):
        labels = json.loads(self.run(["docker", "image", "inspect", image]))[0].get("Config", {}).get("Labels") or {}
        args = self.db["build"]["args"]
        require(labels.get("perfume.pgvector.base-image") == args["POSTGRES_BASE_IMAGE"]
                and labels.get("perfume.pgvector.postgres-version") == args["POSTGRES_SERVER_VERSION"]
                and labels.get("perfume.pgvector.extension-version") == "0.8.7", "Image provenance does not match verified inputs.")

    def psql(self, data, variables=None):
        metadata = self.container()
        self.check_image(metadata["Image"])
        command = ["docker", "exec", "--interactive", metadata["Id"], "psql", "-X", "--no-password", "--quiet",
                   "--tuples-only", "--no-align", "--host", "/var/run/postgresql", "--username", ADMIN, "--dbname", DATABASE,
                   "--set", "ON_ERROR_STOP=1"]
        for key, value in (variables or {}).items():
            command += ["--set", key + "=" + str(value)]
        return self.run(command, input_bytes=data)

    def restore(self, roles, dump, owner):
        require(Path(roles).is_file() and Path(dump).is_file(), "Provide existing reviewed roles SQL and custom-format dump files.")
        role_bytes = Path(roles).read_bytes()
        require(ADMIN.encode() not in role_bytes, "Source roles must not contain the reserved test bootstrap role.")
        expected = self.db["environment"]["REHEARSAL_EXPECTED_SERVER_VERSION_NUM"]
        guard = f"""DO $$ BEGIN
IF current_database() <> '{DATABASE}' OR current_user <> '{ADMIN}' OR current_setting('server_version_num') <> '{expected}' THEN
RAISE EXCEPTION 'Unexpected restore target/version'; END IF;
IF EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' AND c.relkind IN ('r','p','v','m','f','S'))
OR EXISTS (SELECT FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname NOT IN ('public','information_schema'))
OR EXISTS (SELECT FROM pg_extension WHERE extname <> 'plpgsql') THEN
RAISE EXCEPTION 'Restore requires a fresh, empty test database'; END IF; END $$;
"""
        self.psql(guard.encode())
        # Roles first; preserve ownership and ACLs rather than suppressing errors.
        self.psql(b"BEGIN;\n" + role_bytes + b"\nCOMMIT;\n")
        metadata = self.container()
        with Path(dump).open("rb") as stream:
            self.run(["docker", "exec", "--interactive", metadata["Id"], "pg_restore", "--exit-on-error", "--single-transaction",
                      "--host", "/var/run/postgresql", "--username", ADMIN, "--dbname", DATABASE], stream=stream)
        self.psql(f"ALTER DATABASE {sql_identifier(DATABASE)} OWNER TO {sql_identifier(owner)};\n".encode())
        print("Restored into the isolated DB. Compare manifests before running verify.")

    def cleanup(self, delete_volume):
        # Validate ALL existing targets before removing even the test container.
        container = self.container(required=False)
        volume = self.resource("volume", VOLUME)
        network = self.resource("network", NETWORK)
        if network:
            expected_ids = {container["Id"]} if container else set()
            require(set(network.get("Containers", {})) <= expected_ids, "Another container uses the test network.")
        if volume:
            users = self.run(["docker", "ps", "--all", "--filter", "volume=" + VOLUME, "--no-trunc", "--format", "{{.ID}}"])
            require(set(users.splitlines()) <= ({container["Id"]} if container else set()), "Another container uses the test volume.")
        if container:
            self.run(["docker", "rm", "--force", container["Id"]])
        if network:
            self.run(["docker", "network", "rm", network["Id"]])
        if delete_volume and volume:
            self.resource("volume", VOLUME)  # Recheck ownership immediately before removal.
            self.run(["docker", "volume", "rm", VOLUME])  # No --force and no prune.
        print("Removed test container/network. " + ("Removed the test volume only." if delete_volume else "Kept the test volume."))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=str(ROOT / ".env.pgvector-rehearsal"))
    commands = parser.add_subparsers(dest="action", required=True)
    for action in ("config", "build", "start", "verify", "container"):
        commands.add_parser(action)
    restore = commands.add_parser("restore")
    restore.add_argument("--roles", required=True)
    restore.add_argument("--dump", required=True)
    restore.add_argument("--owner", required=True, help="Exact source database owner (from pg_database).")
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--output", required=True)
    cleanup = commands.add_parser("cleanup")
    cleanup.add_argument("--delete-test-volume", action="store_true")
    compare = commands.add_parser("compare")
    compare.add_argument("source")
    compare.add_argument("clone")
    args = parser.parse_args()
    if args.action == "compare":
        source = read_manifest(args.source)
        count = compare_manifests(source, read_manifest(args.clone))
        perfumes, brands = source[("data", "public.perfumes")]["rows"], source[("data", "public.brands")]["rows"]
        print(f"PASS: {count} tables, {perfumes} perfumes, {brands} brands; data, ownership/ACLs, roles and sequences match.")
        return
    rehearsal = Rehearsal(args.env_file)
    if args.action == "config":
        print("PASS: fixed test project/volume/network, exact digest/version; no host ports.")
    elif args.action == "build":
        rehearsal.run(rehearsal.compose + ["build", SERVICE])
        rehearsal.check_image(rehearsal.db["image"])
        print("Built pgvector 0.8.7 against the verified PostgreSQL base.")
    elif args.action == "start":
        rehearsal.resource("volume", VOLUME)
        rehearsal.resource("network", NETWORK)
        rehearsal.container(required=False)
        rehearsal.check_image(rehearsal.db["image"])
        rehearsal.run(rehearsal.compose + ["up", "--detach", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120", SERVICE])
        rehearsal.container()
        print("Started isolated test DB only.")
    elif args.action == "restore":
        rehearsal.restore(args.roles, args.dump, args.owner)
    elif args.action == "verify":
        print(rehearsal.psql((ROOT / "docs/sql/pgvector-smoke-test.sql").read_bytes(),
                            {"expected_server_version_num": rehearsal.db["environment"]["REHEARSAL_EXPECTED_SERVER_VERSION_NUM"]}).strip())
        print("PASS: vector 0.8.7, vector(1024), cosine order/scores and dimension rejection.")
    elif args.action == "manifest":
        output = rehearsal.psql((ROOT / "docs/sql/pgvector-catalog-manifest.sql").read_bytes(), {"expected_db": DATABASE})
        # Never overwrite a source snapshot or a previous verification result.
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(output)
        read_manifest(args.output)
        print("Wrote clone manifest (no row contents/passwords).")
    elif args.action == "container":
        print(rehearsal.container()["Id"])
    elif args.action == "cleanup":
        rehearsal.cleanup(args.delete_test_volume)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError) as error:
        # Raw Docker/SQL output and environment JSON can contain credentials/data.
        if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError):
            print("ERROR: " + str(error), file=sys.stderr)
        else:
            print("ERROR: invalid input, unavailable Docker CLI, or inaccessible file; no further steps executed.", file=sys.stderr)
        sys.exit(1)
