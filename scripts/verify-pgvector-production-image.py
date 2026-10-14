"""Manual image verification/approval only; never switch images or change a DB."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("perfume_compose_guard", ROOT / "scripts/validate-perfume-compose.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
REHEARSAL_IMAGE = "perfume-pgvector-rehearsal-db:pg17.11-vector0.8.7"


def inspect_image(reference):
    return json.loads(guard.docker_output(["image", "inspect", reference]))[0]


def probe_image(image_id, with_vector=False):
    # Entrypoint is overridden. This never starts postgres or mounts a source volume.
    return guard.validate_probe(guard.docker_output([
        "run", "--rm", "--pull", "never", "--network", "none", "--read-only", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--user", "postgres", "--entrypoint", "sh", image_id,
        "-ec", guard.probe_script(with_vector),
    ]), with_vector)


def verify_candidate(candidate=REHEARSAL_IMAGE):
    guard.require(candidate == REHEARSAL_IMAGE or candidate == "perfume-postgres:pg17.11-vector0.8.7"
                  or (isinstance(candidate, str) and guard.re.fullmatch("sha256:" + guard.SHA256, candidate) is not None),
                  "Use the rehearsal/production tag or a full local Image ID.")
    base, derived = inspect_image(guard.BASE_IMAGE), inspect_image(candidate)
    # Resolve the mutable candidate tag ONCE, then use only its full immutable ID.
    guard.require(guard.re.fullmatch("sha256:" + guard.SHA256, derived["Id"]) is not None, "Invalid candidate Image ID.")
    base_lines, vector_lines = probe_image(base["Id"]), probe_image(derived["Id"], True)
    guard.require(base_lines[:5] == vector_lines[:5], "PostgreSQL version/distribution/package/binary changed.")
    guard.require(base.get("Os") == derived.get("Os") == "linux" and base.get("Architecture") == derived.get("Architecture"),
                  "Candidate platform differs from the existing base.")
    for key in ("Entrypoint", "Cmd", "User", "WorkingDir", "Env", "Volumes", "ExposedPorts", "StopSignal"):
        guard.require(base["Config"].get(key) == derived["Config"].get(key), "Candidate changes PostgreSQL runtime defaults.")
    approval = guard.validate_approval({
        "format": 1, "image_id": derived["Id"], "base_image": guard.BASE_IMAGE, "base_image_id": base["Id"],
        "postgres_version": guard.POSTGRES_VERSION, "postgres_package_version": base_lines[3],
        "pgvector_version": guard.VECTOR_VERSION, "platform": base["Os"] + "/" + base["Architecture"],
        "postgres_binary_sha256": base_lines[4], "vector_binary_sha256": vector_lines[5], "vector_control_sha256": vector_lines[6],
    })
    # Scope discovery to the perfume DB service. Never enumerate stock resources.
    ids = guard.docker_output(["ps", "--all", "--quiet", "--filter", "label=com.docker.compose.project=perfume-dev",
                              "--filter", "label=com.docker.compose.service=db"]).splitlines()
    guard.require(len(ids) == 1, "Expected one existing perfume DB container.")
    container = json.loads(guard.docker_output(["inspect", ids[0]]))[0]
    guard.validate_db_container(container, base, "postgres:17-bookworm")
    # Also check the running binary and the actual server; image labels alone are insufficient.
    running = guard.validate_probe(guard.docker_output(["exec", container["Id"], "sh", "-ec", guard.probe_script()]))
    guard.require(running == base_lines, "The running PostgreSQL executable differs from its original image.")
    identity = guard.docker_output(["exec", container["Id"], "psql", "-X", "--no-password", "--host", "/var/run/postgresql",
        "--username", "perfume_user", "--dbname", "perfume", "-At", "--set", "ON_ERROR_STOP=1", "--command",
        "SELECT current_database() || '|' || current_user || '|' || current_setting('server_version_num');"]).strip()
    guard.require(identity == "perfume|perfume_user|170011", "Unexpected source database/user/server version.")
    guard.validate_db_container(dict(container, Image=derived["Id"]), derived, derived["Id"], approval)
    return approval


def write_approval(approval, path=guard.APPROVAL_PATH):
    guard.validate_approval(approval)
    # Explicit manual action; never overwrite an existing operator decision.
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        json.dump(approval, stream, indent=2)
        stream.write("\n")


def readonly_preflight():
    """Inspect the current DB and plan the approved image in Compose; never apply it."""
    approval = guard.load_approval()
    guard.require(approval is not None, "A prior manual image approval is required.")
    environment = dict(os.environ)
    for key in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "COMPOSE_PROFILES", "COMPOSE_DRY_RUN",
                "PERFUME_DB_IMAGE", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "DB_URL", "SCENTREV_API_KEY"):
        environment.pop(key, None)
    compose = ["compose", "--project-name", "perfume-dev", "--project-directory", str(ROOT),
               "--env-file", str(ROOT / ".env"), "--file", str(ROOT / "compose.yaml")]
    current = json.loads(guard.docker_output(compose + ["config", "--format", "json"], environment=environment))
    # Compare backend/DB credentials privately. TCP authentication remains in the Jenkins guard.
    password = current["services"]["db"]["environment"]["POSTGRES_PASSWORD"]
    guard.validate_config(current, password, str(ROOT), approval)
    ids = guard.docker_output(["ps", "--all", "--quiet", "--filter", "label=com.docker.compose.project=perfume-dev",
                               "--filter", "label=com.docker.compose.service=db"]).splitlines()
    guard.require(len(ids) == 1, "Expected one existing perfume DB container.")
    source = json.loads(guard.docker_output(["inspect", ids[0]]))[0]
    current_ref = current["services"]["db"]["image"]
    guard.validate_db_container(source, inspect_image(source["Image"]), current_ref, approval)
    with_vector = current_ref not in {"postgres:17-bookworm", guard.BASE_IMAGE}
    guard.validate_probe(guard.docker_output(["exec", source["Id"], "sh", "-ec", guard.probe_script(with_vector)]),
                         with_vector, approval)
    identity = guard.docker_output(["exec", source["Id"], "psql", "-X", "--no-password", "--host", "/var/run/postgresql",
        "--username", "perfume_user", "--dbname", "perfume", "-At", "--set", "ON_ERROR_STOP=1", "--command",
        "BEGIN READ ONLY; SELECT current_database() || '|' || current_user || '|' || current_setting('server_version_num') "
        "|| '|' || coalesce((SELECT extversion FROM pg_extension WHERE extname='vector'), 'none'); COMMIT;"])
    expected = {"perfume|perfume_user|170011|none"}
    if with_vector:
        expected.add("perfume|perfume_user|170011|0.8.7")
    # Quiet output excludes transaction command tags, keeping a single identity row.
    rows = [line for line in identity.splitlines() if line not in {"BEGIN", "COMMIT", ""}]
    guard.require(len(rows) == 1 and rows[0] in expected, "Unexpected live DB version or extension state.")
    target_image = inspect_image(approval["image_id"])
    guard.validate_db_container(dict(source, Image=target_image["Id"]), target_image, approval["image_id"], approval)
    target_environment = dict(environment, PERFUME_DB_IMAGE=approval["image_id"])
    target = json.loads(guard.docker_output(compose + ["config", "--format", "json"], environment=target_environment))
    guard.validate_config(target, password, str(ROOT), approval)
    guard.require(target["services"]["db"]["image"] == approval["image_id"], "Compose did not select the approved Image ID.")
    # Never retry without --dry-run if this Compose version cannot recognize it.
    guard.docker_output(compose + ["--dry-run", "up", "--detach", "--no-deps", "--no-build", "--pull", "never", "db"],
                        environment=target_environment)
    after = json.loads(guard.docker_output(["inspect", source["Id"]]))[0]
    guard.require(after["Id"] == source["Id"] and after["Image"] == source["Image"] and after["State"]["Running"] is True,
                  "The source DB changed during the read-only preflight.")
    return approval["image_id"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", default=REHEARSAL_IMAGE)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--write-approval", action="store_true", help="Manually record the verified Image ID; does not change .env or Docker services.")
    modes.add_argument("--preflight", action="store_true", help="Read-only approved-ID/Compose dry-run checks; no new containers, approval writes or DB changes.")
    args = parser.parse_args()
    try:
        if args.preflight:
            guard.require(args.candidate == REHEARSAL_IMAGE, "--preflight uses the approval file, not a candidate tag.")
            image_id = readonly_preflight()
            print("Read-only preflight passed: Compose recognizes approved Image ID " + image_id + "; source DB retained.")
            sys.exit(0)
        approval = verify_candidate(args.candidate)
        if args.write_approval:
            write_approval(approval)
        print("Verified PostgreSQL 17.11 / pgvector 0.8.7 Image ID: " + approval["image_id"])
        print("Manual approval recorded." if args.write_approval else "Verification only; no approval file written.")
    except (ValueError, KeyError, TypeError, AttributeError, IndexError, OSError):
        message = ("Read-only preflight rejected: check approval, local Image ID, Compose --dry-run support and source DB state. No changes applied."
                   if args.preflight else
                   "Image verification rejected: check source digest, platform, runtime defaults, version and artifact hashes. No DB changes performed.")
        print(message, file=sys.stderr)
        sys.exit(1)
