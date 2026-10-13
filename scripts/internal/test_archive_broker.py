"""Probe the packaged archive subscriber, using only an owned MQTT topic."""
import pathlib
import subprocess
import sys
import tempfile
import zipfile

jar, broker = sys.argv[1:]
if broker not in {"tcp://10.77.0.3:1883", "tcp://10.77.0.6:1883"}:
    raise SystemExit("An explicit sensor broker is required")
root = pathlib.Path(__file__).resolve().parents[2]
destination = root / "output" / "archive-broker"
destination.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(dir=destination) as directory:
    with zipfile.ZipFile(jar) as archive:
        for prefix in ("adapters-", "domain-", "org.eclipse.paho.mqttv5.client-"):
            names = [name for name in archive.namelist()
                     if name.startswith("BOOT-INF/lib/" + prefix) and name.endswith(".jar")]
            if len(names) != 1:
                raise SystemExit("Packaged archive dependencies are ambiguous")
            (pathlib.Path(directory) / pathlib.Path(names[0]).name).write_bytes(archive.read(names[0]))
    jdk = root / ".tools" / "jdk" / "bin"
    subprocess.run(["rtk", "proxy", str(jdk / "javac"), "-cp", directory + "/*", "-d", directory,
                    str(root / "scripts/test/fixtures/MqttArchiveBacklogProbe.java")], check=True)
    subprocess.run(["rtk", "proxy", str(jdk / "java"), "-cp", directory + "/*:" + directory,
                    "MqttArchiveBacklogProbe", broker], check=True, timeout=30)
