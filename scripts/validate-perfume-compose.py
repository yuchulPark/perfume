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
    require(set(services) == {"db", "backend", "frontend"}, "Unexpected Compose services.")
    require(all(not s.get("container_name") for s in services.values()), "Fixed container names are prohibited.")
    db, backend, frontend = (services[name] for name in ("db", "backend", "frontend"))
    require(backend.get("image") in (None, "perfume-dev-backend", "perfume-dev-backend:latest")
            and frontend.get("image") in (None, "perfume-dev-frontend", "perfume-dev-frontend:latest"), "Unexpected application image names.")
    require(db["image"] == "postgres:17-bookworm", "Unexpected PostgreSQL image.")
    require(set(config["volumes"]) == {"pgdata"}, "Unexpected volume definitions.")
    require(config["volumes"]["pgdata"]["name"] == "perfume-dev_pgdata", "DB volume must be perfume-dev_pgdata.")
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
    require(set(networks) == {"app", "data", "db-access", "web"}, "Unexpected network definitions.")
    for name, network in networks.items():
        require(network["name"] == "perfume-dev_" + name, "Unexpected network name.")
        require(bool(network.get("internal")) == (name in {"app", "data"}), "Unexpected network isolation.")
    for name, expected in (("db", {"data", "db-access"}), ("backend", {"app", "data"}), ("frontend", {"app", "web"})):
        require(set(services[name]["networks"]) == expected, "Unexpected service network attachment.")
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
