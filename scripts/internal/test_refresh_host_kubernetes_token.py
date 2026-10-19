import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "ops/refresh-host-kubernetes-token.sh"

class RefreshHostTokenTests(unittest.TestCase):
    def test_rotates_same_service_account_without_printing_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "token"; target.write_text("previous-private-token")
            stub = root / "kubectl"; stub.write_text("#!/bin/sh\n[ \"$*\" = \"--context test-context --kubeconfig /test/kubeconfig -n edgeai create token edgeai-control-plane --duration=24h\" ] || exit 9\nprintf '%s' 'new-private-token'\n"); stub.chmod(0o700)
            result = subprocess.run(["bash", str(SCRIPT), "test-context", "/test/kubeconfig", "edgeai", "edgeai-control-plane", str(target)], env={**os.environ, "KUBECTL_BIN": str(stub)}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(target.read_text(), "new-private-token\n")
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("private-token", result.stdout + result.stderr)

    def test_failed_token_request_preserves_previous_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "token"; target.write_text("previous-private-token")
            stub = root / "kubectl"; stub.write_text("#!/bin/sh\nexit 7\n"); stub.chmod(0o700)
            result = subprocess.run(["bash", str(SCRIPT), "test-context", "/test/kubeconfig", "edgeai", "edgeai-control-plane", str(target)], env={**os.environ, "KUBECTL_BIN": str(stub)}, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(target.read_text(), "previous-private-token")
            self.assertNotIn("private-token", result.stdout + result.stderr)

if __name__ == "__main__": unittest.main()
