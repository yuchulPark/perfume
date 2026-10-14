"""Validate deployment metadata only; never open a database connection."""
import argparse
import json
import os
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_config(config, expected_password, deploy_directory="/srv/perfume"):
    require(bool(expected_password), "Jenkins DB password credential is missing.")
    require(config["name"] == "perfume-dev", "Compose project must be perfume-dev.")
    services = config["services"]
    require(set(services) == {"db", "backend", "frontend", "ollama"}, "Unexpected Compose services.")
    require(all(not s.get("container_name") for s in services.values()), "Fixed container names are prohibited.")
    db, backend, frontend = (services[name] for name in ("db", "backend", "frontend"))
    ollama = services["ollama"]
    require(backend.get("image") in (None, "perfume-dev-backend", "perfume-dev-backend:latest")
            and frontend.get("image") in (None, "perfume-dev-frontend", "perfume-dev-frontend:latest"), "Unexpected application image names.")
    require(db["image"] == "postgres:17-bookworm", "Unexpected PostgreSQL image.")
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
    require(db_env["POSTGRES_DB"] == "perfume" and db_env["POSTGRES_USER"] == "perfume_user", "Unexpected DB identity.")
    require(app_env["DB_URL"] == "jdbc:postgresql://db:5432/perfume"
            and app_env["DB_USERNAME"] == "perfume_user", "Unexpected backend DB connection.")
    require(db_env["POSTGRES_PASSWORD"] == expected_password
            and app_env["DB_PASSWORD"] == expected_password, "Jenkins credential and server .env password differ.")
    require(app_env.get("SCENTREV_API_KEY") == "", "Web deployment must not receive a ScentRev API key.")
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
    args = parser.parse_args()
    try:
        metadata = json.load(sys.stdin)
        if args.backend_image:
            validate_backend_image(metadata)
        else:
            validate_config(metadata, os.environ.get("PERFUME_EXPECTED_DB_PASSWORD"), args.deploy_directory)
    except (ValueError, KeyError, TypeError, AttributeError):
        # Never include configuration JSON or credentials in errors.
        print("Perfume deployment configuration rejected; check the documented fixed settings and credentials.", file=sys.stderr)
        sys.exit(1)
    print("Perfume deployment configuration verified.")
