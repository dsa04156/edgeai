"""Signals may interrupt the main thread while Event.wait owns its condition lock."""
import os
from pathlib import Path
import subprocess
import sys
import unittest
import uuid


class CancellationTest(unittest.TestCase):
    def test_runner_signal_handler_returns_even_when_wait_lock_is_held(self):
        self.check_signal_owner('main','cancelled','Runner')

    def test_supervisor_signal_handler_returns_even_when_wait_lock_is_held(self):
        self.check_signal_owner('virtual_device','stopping','Supervisor')

    def check_signal_owner(self,module,event,constructor):
        image=os.environ.get('EDGEAI_RUNNER_IMAGE')
        package='/opt/edgeai' if image else str(Path(__file__).resolve().parents[1])
        for name in ('SIGTERM','SIGINT'):
            with self.subTest(signal=name):
                script=f'''import os,signal,sys
sys.path.insert(0,{package!r})
from edgeai_runner import {module} as main
class Probe:
    def run(self):
        with main.{event}._cond:
            os.kill(os.getpid(),signal.{name})
        assert main.{event}.is_set()
        assert main.{event}.wait(0)
        main.{event}.clear()
        assert not main.{event}.is_set()
        print("SIGNAL_RETURNED",flush=True)
        return 0
    def report_failure(self,code):
        pass
main.{constructor}=Probe
raise SystemExit(main.main())
'''
                container='edgeai-signal-'+uuid.uuid4().hex
                command=(['docker','run','--rm','--name',container,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',
                          '--entrypoint','python3',image,'-c',script] if image else [sys.executable,'-c',script])
                try:
                    result=subprocess.run(command,capture_output=True,timeout=5)
                finally:
                    if image:
                        subprocess.run(['docker','rm','-f',container],capture_output=True,timeout=10)
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertEqual(b'SIGNAL_RETURNED\n',result.stdout)
                self.assertEqual(b'',result.stderr)
