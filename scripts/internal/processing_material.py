"""Create private deployment material locally, without changing a cluster."""
import base64
from pathlib import Path
import secrets
import subprocess
import sys

import yaml

destination = Path(sys.argv[1])
if not destination.is_absolute() or destination.exists():
    raise SystemExit("Use a new absolute private directory; existing credentials are never overwritten")
destination.mkdir(mode=0o700, parents=True)


def openssl(*arguments):
    result = subprocess.run(["openssl", *arguments], cwd=destination,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if result.returncode:
        raise SystemExit("Private certificate creation failed; inspect the local private directory")


openssl("req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "365",
        "-keyout", "ca.key", "-out", "ca.crt", "-subj", "/CN=edgeai-processing-ca",
        "-addext", "basicConstraints=critical,CA:TRUE")
openssl("req", "-new", "-newkey", "rsa:3072", "-nodes", "-keyout", "tls.key",
        "-out", "tls.csr", "-subj", "/CN=edgeai-api.edgeai.svc")
(destination / "tls.ext").write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:edgeai-api.edgeai.svc,DNS:edgeai-api.edgeai.svc.cluster.local\n")
openssl("x509", "-req", "-in", "tls.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
        "-CAcreateserial", "-days", "365", "-out", "tls.crt", "-extfile", "tls.ext")
credential, password = secrets.token_urlsafe(48), secrets.token_urlsafe(48)


def secret(name, namespace, values, kind="Opaque"):
    return {"apiVersion": "v1", "kind": "Secret", "type": kind,
        "metadata": {"name": name, "namespace": namespace,
            "labels": {"app.kubernetes.io/part-of": "edgeai", "edgeai.io/processing-material": "true"}},
        "data": {key: base64.b64encode(value if isinstance(value, bytes) else value.encode()).decode()
                 for key, value in values.items()}}


material = [secret("edgeai-processing-agent", namespace,
    {"credential": credential, "ca.crt": (destination / "ca.crt").read_bytes()})
    for namespace in ("edgeai", "ekuiper")]
material += [secret("edgeai-processing-history", "edgeai", {"password": password}),
    secret("edgeai-processing-tls", "edgeai", {key: (destination / key).read_bytes()
        for key in ("tls.crt", "tls.key")}, "kubernetes.io/tls")]
(destination / "secrets.yaml").write_text(yaml.safe_dump_all(material, sort_keys=False))
for file in destination.iterdir():
    file.chmod(0o600)
print("Private material prepared locally. No secrets are printed and no cluster resources changed.")
