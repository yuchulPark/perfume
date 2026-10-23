"""Validate deployment metadata only; never open a database connection."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

BASE_DIGEST = "sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826"
BASE_IMAGE = "postgres:17-bookworm@" + BASE_DIGEST
POSTGRES_VERSION = "17.11"
SERVER_VERSION_NUM = "170011"
VECTOR_VERSION = "0.8.7"
APPROVAL_NAME = ".env.pgvector-approval.json"
APPROVAL_PATH = Path(__file__).resolve().parents[1] / APPROVAL_NAME
SHA256 = r"[0-9a-f]{64}"
AI_FIXED_ENV = {
    "PERFUME_AI_SEARCH_MODEL": "qwen3-embedding:0.6b",
    "PERFUME_AI_SEARCH_READ_TIMEOUT_MS": "60000",
    "PERFUME_AI_SEARCH_REQUEST_TIMEOUT_MS": "120000",
    "PERFUME_AI_SEARCH_MAX_CONCURRENT": "1",
    "PERFUME_AI_RECOMMEND_MODEL": "qwen3:4b-instruct",
    "PERFUME_AI_RECOMMEND_READ_TIMEOUT_MS": "300000",
    "PERFUME_AI_RECOMMEND_GENERATION_TIMEOUT_MS": "300000",
    "PERFUME_AI_RECOMMEND_REQUEST_TIMEOUT_MS": "420000",
    "PERFUME_AI_RECOMMEND_MAX_CONCURRENT": "1",
}
AI_INPUT_ENV = {
    "PERFUME_AI_SEARCH_ENABLED", "PERFUME_AI_SEARCH_MODEL_DIGEST",
    "PERFUME_AI_RECOMMEND_ENABLED", "PERFUME_AI_RECOMMEND_MODEL_DIGEST",
}


def validate_ai_environment(environment):
    keys = {key for key in environment if key.startswith("PERFUME_AI_")}
    # The first deployment validates the old checkout before fetching the new Compose.
    # No AI overrides means the existing application.properties keeps both APIs disabled.
    if not keys:
        return
    require(keys == AI_INPUT_ENV | AI_FIXED_ENV.keys(), "Unexpected or incomplete AI environment settings.")
    require(all(environment[key] == value for key, value in AI_FIXED_ENV.items()),
            "AI model, deadline and CPU concurrency settings must retain the validated limits.")
    for feature in ("SEARCH", "RECOMMEND"):
        enabled = environment[f"PERFUME_AI_{feature}_ENABLED"]
        digest = environment[f"PERFUME_AI_{feature}_MODEL_DIGEST"]
        require(enabled in ("false", "true"), "AI enabled flags must be true or false.")
        require(isinstance(digest, str) and (digest == "" or re.fullmatch(r"(?:sha256:)?" + SHA256, digest) is not None),
                "AI model identities require a full lowercase SHA-256 digest.")
        require(enabled != "true" or digest != "", "Enabled AI APIs require their own full model digest.")
    require(environment["PERFUME_AI_RECOMMEND_ENABLED"] != "true" or environment["PERFUME_AI_SEARCH_ENABLED"] == "true",
            "Recommendations require semantic search to be enabled.")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_approval(approval):
    require(isinstance(approval, dict) and set(approval) == {
        "format", "image_id", "base_image", "base_image_id", "postgres_version", "postgres_package_version",
        "pgvector_version", "platform", "postgres_binary_sha256", "vector_binary_sha256", "vector_control_sha256",
    }, "Invalid operator image approval.")
    require(approval["format"] == 1 and approval["base_image"] == BASE_IMAGE
            and approval["postgres_version"] == POSTGRES_VERSION and approval["pgvector_version"] == VECTOR_VERSION,
            "Unapproved base or extension version.")
    for key in ("image_id", "base_image_id"):
        require(re.fullmatch("sha256:" + SHA256, approval[key]) is not None, "A full immutable Image ID is required.")
    require(approval["image_id"] != approval["base_image_id"], "The derived image must differ from its base.")
    for key in ("postgres_binary_sha256", "vector_binary_sha256", "vector_control_sha256"):
        require(re.fullmatch(SHA256, approval[key]) is not None, "Invalid artifact checksum.")
    require(re.fullmatch(r"17\.11-[A-Za-z0-9.+~]+", approval["postgres_package_version"]) is not None,
            "Unexpected PostgreSQL package version.")
    require(approval["platform"] in {"linux/amd64", "linux/arm64"}, "Unsupported image platform.")
    return approval


def load_approval(path=APPROVAL_PATH):
    path = Path(path)
    require(not path.is_symlink(), "Image approval cannot be a symlink.")
    if not path.exists():
        return None
    require(path.is_file(), "Image approval must be a regular file.")
    if os.name == "posix":
        require(not stat.S_IMODE(path.stat().st_mode) & 0o022, "Image approval must not be group/world writable.")
    with path.open(encoding="utf-8") as stream:
        return validate_approval(json.load(stream))


def validate_db_reference(image, approval=None):
    if approval is not None:
        validate_approval(approval)
    if image in {"postgres:17-bookworm", BASE_IMAGE}:
        return
    require(approval is not None and image == approval["image_id"],
            "DB image must be the default, the fixed rollback base, or the manually approved Image ID.")


def image_environment(image):
    return dict(item.split("=", 1) for item in image["Config"].get("Env", []) if "=" in item)


def validate_db_container(container, image, configured_image, approval=None):
    validate_db_reference(configured_image, approval)
    require(re.fullmatch(SHA256, container["Id"]) is not None and container["Image"] == image["Id"], "Invalid DB/image identity.")
    labels = container["Config"].get("Labels") or {}
    require(labels.get("com.docker.compose.project") == "perfume-dev" and labels.get("com.docker.compose.service") == "db",
            "Unexpected DB container project/service.")
    require(container["State"].get("Running") is True and container["State"].get("Health", {}).get("Status") == "healthy",
            "The existing DB must already be healthy.")
    mounts = container.get("Mounts", [])
    require(len(mounts) == 1 and mounts[0].get("Type") == "volume" and mounts[0].get("Name") == "perfume-dev_pgdata"
            and mounts[0].get("Destination") == "/var/lib/postgresql/data" and mounts[0].get("RW") is True,
            "Unexpected running DB data volume.")
    require(set(container["NetworkSettings"].get("Networks", {})) == {"perfume-dev_data", "perfume-dev_db-access"},
            "Unexpected running DB networks.")
    require(container["HostConfig"].get("PortBindings") == {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "15432"}]}
            and not container["HostConfig"].get("Privileged") and not container["HostConfig"].get("VolumesFrom"),
            "Unexpected running DB access settings.")
    for key in ("Entrypoint", "Cmd"):
        require(container["Config"].get(key) == image["Config"].get(key), "DB startup defaults must be retained.")
    require(image["Config"].get("Entrypoint") == ["docker-entrypoint.sh"] and image["Config"].get("Cmd") == ["postgres"],
            "Unexpected PostgreSQL image startup.")
    env = image_environment(image)
    runtime_env = image_environment(container)
    require(runtime_env.get("PGDATA") == "/var/lib/postgresql/data" and runtime_env.get("POSTGRES_DB") == "perfume"
            and runtime_env.get("POSTGRES_USER") == "perfume_user", "Unexpected running DB identity/data path.")
    require(env.get("PG_MAJOR") == "17" and re.fullmatch(r"17\.11-[A-Za-z0-9.+~]+", env.get("PG_VERSION", "")) is not None
            and env.get("PGDATA") == "/var/lib/postgresql/data" and image.get("Os") == "linux", "Incompatible PostgreSQL image.")
    if configured_image in {"postgres:17-bookworm", BASE_IMAGE}:
        require(any(digest in {"postgres@" + BASE_DIGEST, "docker.io/library/postgres@" + BASE_DIGEST}
                    for digest in image.get("RepoDigests", [])), "The running base image digest differs from PostgreSQL 17.11.")
    else:
        require(image["Id"] == approval["image_id"] and image.get("Os") + "/" + image.get("Architecture", "") == approval["platform"],
                "The running image differs from the operator approval.")
        require(env["PG_VERSION"] == approval["postgres_package_version"], "PostgreSQL package version changed.")
        labels = image["Config"].get("Labels") or {}
        require(labels.get("perfume.pgvector.base-image") == BASE_IMAGE
                and labels.get("perfume.pgvector.postgres-version") == POSTGRES_VERSION
                and labels.get("perfume.pgvector.extension-version") == VECTOR_VERSION, "Unexpected pgvector build provenance.")


def docker_output(arguments, environment=None):
    result = subprocess.run(["docker"] + arguments, capture_output=True, text=True, encoding="utf-8", check=False, env=environment)
    require(result.returncode == 0, "Docker image/runtime inspection failed.")
    return result.stdout


def probe_script(with_vector=False):
    script = """set -eu
postgres --version
. /etc/os-release
printf '%s\\n' "$VERSION_CODENAME" "$PG_MAJOR" "$PG_VERSION"
sha256sum /usr/lib/postgresql/17/bin/postgres | cut -d ' ' -f 1
"""
    if with_vector:
        script += """sha256sum /usr/lib/postgresql/17/lib/vector.so /usr/share/postgresql/17/extension/vector.control | cut -d ' ' -f 1
sed -n "s/^default_version = '\\(.*\\)'$/\\1/p" /usr/share/postgresql/17/extension/vector.control
"""
    return script


def validate_probe(output, with_vector=False, approval=None):
    lines = output.splitlines()
    words = lines[0].split() if lines else []
    require(len(lines) == (8 if with_vector else 5) and len(words) >= 3
            and words[:3] == ["postgres", "(PostgreSQL)", POSTGRES_VERSION]
            and lines[1] == "bookworm" and lines[2] == "17", "Unexpected runtime PostgreSQL version/distribution.")
    require(re.fullmatch(r"17\.11-[A-Za-z0-9.+~]+", lines[3]) is not None, "Unexpected runtime PostgreSQL package.")
    require(re.fullmatch(SHA256, lines[4]) is not None, "Invalid PostgreSQL executable checksum.")
    if with_vector:
        require(lines[7] == VECTOR_VERSION and all(re.fullmatch(SHA256, x) is not None for x in lines[5:7]),
                "Missing or unexpected pgvector artifacts.")
    if approval is not None:
        require(lines[3] == approval["postgres_package_version"] and lines[4] == approval["postgres_binary_sha256"],
                "The PostgreSQL executable differs from the approved binary.")
        if with_vector:
            require(lines[5] == approval["vector_binary_sha256"] and lines[6] == approval["vector_control_sha256"],
                    "The vector files differ from the approved artifacts.")
    return lines


def validate_config(config, expected_password, deploy_directory="/srv/perfume", db_approval=None):
    require(bool(expected_password), "Jenkins DB password credential is missing.")
    require(config["name"] == "perfume-dev", "Compose project must be perfume-dev.")
    services = config["services"]
    require(set(services) == {"db", "backend", "frontend", "ollama"}, "Unexpected Compose services.")
    require(all(not s.get("container_name") for s in services.values()), "Fixed container names are prohibited.")
    db, backend, frontend = (services[name] for name in ("db", "backend", "frontend"))
    ollama = services["ollama"]
    require(backend.get("image") in (None, "perfume-dev-backend", "perfume-dev-backend:latest")
            and frontend.get("image") in (None, "perfume-dev-frontend", "perfume-dev-frontend:latest"), "Unexpected application image names.")
    validate_db_reference(db["image"], db_approval)
    require(not db.get("build") and not db.get("pull_policy") and not db.get("post_start") and not db.get("pre_stop"),
            "DB build, pull policy and lifecycle hooks are prohibited.")
    require(set(config["volumes"]) == {"pgdata", "ollama-data"}, "Unexpected volume definitions.")
    require(config["volumes"]["pgdata"]["name"] == "perfume-dev_pgdata", "DB volume must be perfume-dev_pgdata.")
    require(config["volumes"]["ollama-data"]["name"] == "perfume-dev_ollama-data", "Unexpected Ollama model volume.")
    require(all(not v.get("external") for v in config["volumes"].values()), "External volumes are prohibited.")
    mounts = db["volumes"]
    require(len(mounts) == 1 and mounts[0]["type"] == "volume"
            and mounts[0]["source"] == "pgdata"
            and mounts[0]["target"] == "/var/lib/postgresql/data", "Unexpected DB mount or initialization script.")
    db_ports = db["ports"]
    require(len(db_ports) == 1 and db_ports[0].get("host_ip") == "127.0.0.1"
            and str(db_ports[0]["published"]) == "15432" and db_ports[0]["target"] == 5432
            and db_ports[0].get("protocol", "tcp") == "tcp", "DB must bind only 127.0.0.1:15432.")
    web_ports = frontend["ports"]
    require(len(web_ports) == 1 and str(web_ports[0]["published"]) == "8088"
            and web_ports[0]["target"] == 80, "Frontend port must be 8088.")
    require(not backend.get("ports"), "Backend must not publish a host port.")
    require(not backend.get("volumes") and not frontend.get("volumes"), "Unexpected application bind mounts.")
    networks = config["networks"]
    require(set(networks) == {"app", "data", "db-access", "web", "ai", "ollama-download"}, "Unexpected network definitions.")
    for name, network in networks.items():
        require(network["name"] == "perfume-dev_" + name, "Unexpected network name.")
        require(not network.get("external"), "External networks are prohibited.")
        require(bool(network.get("internal")) == (name in {"app", "data", "ai"}), "Unexpected network isolation.")
    for name, expected in (("db", {"data", "db-access"}), ("backend", {"app", "data", "ai"}),
                           ("frontend", {"app", "web"}), ("ollama", {"ai", "ollama-download"})):
        require(set(services[name]["networks"]) == expected, "Unexpected service network attachment.")
        require(not services[name].get("network_mode"), "Service network overrides are prohibited.")
    require(ollama["image"] == "ollama/ollama:0.40.4" and not ollama.get("build"), "Unexpected Ollama image.")
    require(not ollama.get("ports"), "Ollama must not publish a host port.")
    model_mounts = ollama["volumes"]
    require(len(model_mounts) == 1 and model_mounts[0]["type"] == "volume"
            and model_mounts[0]["source"] == "ollama-data"
            and model_mounts[0]["target"] == "/root/.ollama"
            and not model_mounts[0].get("read_only"), "Unexpected Ollama model mount.")
    require(not ollama.get("privileged") and not ollama.get("devices") and not ollama.get("gpus")
            and ollama.get("runtime") in (None, "runc")
            and not ollama.get("deploy", {}).get("resources", {}).get("reservations", {}).get("devices"),
            "Ollama must run without GPU access or privileged mode.")
    # Compose versions render byte values as either JSON integers or decimal strings.
    require(float(ollama.get("cpus", 0)) == 3.0 and str(ollama.get("mem_limit")) == str(6 * 1024 ** 3),
            "Ollama must retain its CPU and memory limits.")
    require(ollama.get("environment") == {
        "OLLAMA_HOST": "0.0.0.0:11434", "OLLAMA_NO_CLOUD": "1", "OLLAMA_VULKAN": "0",
        "OLLAMA_NUM_PARALLEL": "1", "OLLAMA_MAX_LOADED_MODELS": "1",
        "OLLAMA_CONTEXT_LENGTH": "4096", "OLLAMA_KEEP_ALIVE": "5m",
    }, "Unexpected Ollama runtime settings.")
    require(ollama.get("healthcheck", {}).get("test") == ["CMD", "ollama", "list"],
            "Ollama healthcheck must not pull or load models.")
    require(not ollama.get("depends_on") and not ollama.get("post_start"), "Ollama setup must be manual.")
    require(set(backend.get("depends_on") or {}) <= {"db"}
            and set(frontend.get("depends_on") or {}) <= {"backend"},
            "Application startup must not depend on Ollama.")
    db_env, app_env = db["environment"], backend["environment"]
    require(set(db_env) == {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"}, "Unexpected DB environment overrides.")
    require(db_env["POSTGRES_DB"] == "perfume" and db_env["POSTGRES_USER"] == "perfume_user", "Unexpected DB identity.")
    require(app_env["DB_URL"] == "jdbc:postgresql://db:5432/perfume"
            and app_env["DB_USERNAME"] == "perfume_user", "Unexpected backend DB connection.")
    require(db_env["POSTGRES_PASSWORD"] == expected_password
            and app_env["DB_PASSWORD"] == expected_password, "Jenkins credential and server .env password differ.")
    require(app_env.get("SCENTREV_API_KEY") == "", "Web deployment must not receive a ScentRev API key.")
    auth_keys = {"PERFUME_AUTH_ENABLED", "PERFUME_AUTH_COOKIE_SECURE"}
    http_keys = {"SPRING_PROFILES_ACTIVE", "PERFUME_AUTH_DEV_HTTP_ENABLED", "PERFUME_AUTH_DEV_HTTP_ORIGIN"}
    require(set(app_env) <= {"DB_URL", "DB_USERNAME", "DB_PASSWORD", "SCENTREV_API_KEY"} | auth_keys | http_keys | AI_INPUT_ENV | AI_FIXED_ENV.keys(),
            "Unexpected backend environment overrides.")
    if http_keys & app_env.keys():
        require(app_env.get("SPRING_PROFILES_ACTIVE") == "auth-dev-http"
                and app_env.get("PERFUME_AUTH_DEV_HTTP_ENABLED") == "true"
                and app_env.get("PERFUME_AUTH_DEV_HTTP_ORIGIN") == "http://192.168.0.19:8088"
                and app_env.get("PERFUME_AUTH_ENABLED") == "true"
                and app_env.get("PERFUME_AUTH_COOKIE_SECURE") == "false",
                "HTTP authentication is restricted to the explicitly opted-in private development VM.")
    elif auth_keys & app_env.keys():
        require(auth_keys <= app_env.keys()
                and app_env["PERFUME_AUTH_ENABLED"] in {"false", "true"}
                and app_env["PERFUME_AUTH_COOKIE_SECURE"] == "true",
                "Authentication deployment requires an explicit flag and Secure session cookies.")
    validate_ai_environment(app_env)
    for name in services:
        require(not services[name].get("command") and not services[name].get("entrypoint"), "Image startup defaults must be retained.")
    for name, directory in (("backend", deploy_directory), ("frontend", os.path.join(deploy_directory, "frontend"))):
        build = services[name]["build"]
        require(os.path.realpath(build["context"]) == os.path.realpath(directory)
                and build.get("dockerfile", "Dockerfile") == "Dockerfile", "Unexpected application build context.")
        require(not build.get("args") and not build.get("secrets"), "Deployment credentials must not enter builds.")


def validate_backend_image(config):
    require(config.get("Entrypoint") == ["java", "-jar", "/app/app.jar"], "Unexpected backend entrypoint.")
    required = {
        "--server.port=8081", "--scentrev.brand-import=false",
        "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never",
        "--spring.jpa.show-sql=false",
    }
    require(set(config.get("Cmd") or []) == required, "Backend startup must disable import and automatic schema changes.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-image", action="store_true")
    parser.add_argument("--deploy-directory", default="/srv/perfume")
    parser.add_argument("--approval-file", default=str(APPROVAL_PATH))
    parser.add_argument("--print-db-image", action="store_true")
    parser.add_argument("--db-container", action="store_true")
    parser.add_argument("--expected-db-image")
    args = parser.parse_args()
    try:
        metadata = json.load(sys.stdin)
        if args.backend_image:
            validate_backend_image(metadata)
        elif args.db_container:
            approval = load_approval(args.approval_file)
            image = json.loads(docker_output(["image", "inspect", metadata["Image"]]))[0]
            validate_db_container(metadata, image, args.expected_db_image, approval)
            with_vector = args.expected_db_image not in {"postgres:17-bookworm", BASE_IMAGE}
            validate_probe(docker_output(["exec", metadata["Id"], "sh", "-ec", probe_script(with_vector)]),
                           with_vector, approval if with_vector else None)
        else:
            validate_config(metadata, os.environ.get("PERFUME_EXPECTED_DB_PASSWORD"), args.deploy_directory,
                            load_approval(args.approval_file))
    except (ValueError, KeyError, TypeError, AttributeError, IndexError, OSError):
        # Never include configuration JSON or credentials in errors.
        print("Perfume deployment configuration rejected; check the documented fixed settings and credentials.", file=sys.stderr)
        sys.exit(1)
    print(metadata["services"]["db"]["image"] if args.print_db_image else "Perfume deployment configuration verified.")
