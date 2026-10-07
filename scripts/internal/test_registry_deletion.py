"""Run registration deletion tests in a new disposable PostgreSQL database."""
import os
import subprocess
import uuid
from postgres_backup import Postgres, identifier


def main():
    pg = Postgres()
    database = "edgeai_registry_test_" + uuid.uuid4().hex[:12]
    pg.sql("CREATE DATABASE " + identifier(database), "postgres")
    print("Created isolated registry test database", flush=True)
    try:
        env = os.environ.copy()
        env.update(EDGEAI_DB_NAME=database, EDGEAI_INFRASTRUCTURE_SOURCE="disabled")
        for feature in ("KUBE", "PROMETHEUS", "EDGEX", "RUNTIME", "VD", "REMOTE", "STREAM", "STREAM_BINDINGS", "STREAM_RUNS"):
            env[f"EDGEAI_{feature}_ENABLED"] = "false"
        result = subprocess.run([
            "backend/gradlew", "-p", "backend", ":app:integrationTest",
            "--tests", "io.edgeai.app.integration.RegistryDeletionIntegrationTest",
            "--tests", "io.edgeai.app.integration.VirtualDeviceIntegrationTest", "--console=plain",
        ], env=env, check=False)
        return result.returncode
    finally:
        pg.sql("DROP DATABASE " + identifier(database), "postgres")
        print("Removed isolated registry test database", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
