"""Actual Linux child processes, pipes, watchdog and descendant cleanup."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_workload import MAX_MESSAGE, WorkloadError, WorkloadProcess

FIXTURE = str(Path(__file__).parent / 'fixtures/stream_workload.py')


def until(condition, seconds=5):
    end=time.monotonic()+seconds
    while not condition() and time.monotonic()<end:time.sleep(.005)
    if not condition():raise AssertionError('Owned process did not reach expected state')


class StreamWorkloadTest(unittest.TestCase):
    def setUp(self):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);self.root=Path(temp.name)

    def process(self, mode, duration=10, cancel=None):
        child=WorkloadProcess([sys.executable,FIXTURE,mode],self.root,time.monotonic()+duration,cancel=cancel)
        self.addCleanup(child.close)
        return child

    def reply(self, child):
        end=time.monotonic()+5
        while time.monotonic()<end:
            result=child.step()
            if result is not None:return result
            time.sleep(.001)
        self.fail('No bounded process response')

    def test_persistent_process_handles_pipe_capacity_without_restarting(self):
        child=self.process('echo');pid=child.process.pid
        for encoded in (b'{"x":1}',b'{"value":"'+b'a'*200000+b'"}'):
            child.request(encoded,3);self.assertEqual(encoded,self.reply(child));self.assertEqual(pid,child.process.pid)
        self.assertIsNone(child.process.poll())

    def test_watchdog_kills_actual_process_and_other_group_descendant_while_parent_does_not_poll(self):
        other=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],start_new_session=True)
        self.addCleanup(lambda: (other.kill(),other.wait()) if other.poll() is None else None)
        child=self.process('descendants',duration=.7)
        until(lambda:(self.root/'owned.json').exists())
        owned=json.loads((self.root/'owned.json').read_text())
        # No step/check/close calls: the independent watchdog must fence both groups.
        until(lambda:child.process.poll() is not None,2)
        until(lambda:all(not Path('/proc',str(pid)).exists() for pid in owned.values()),2)
        self.assertIsNone(other.poll())
        with self.assertRaisesRegex(WorkloadError,'LEASE_EXPIRED'):child.renew(time.monotonic()+30)

    def test_request_timeout_and_cancel_do_not_wait_for_a_blocked_workload(self):
        for mode in ('timeout','cancel'):
            with self.subTest(mode=mode):
                cancel=threading.Event();child=self.process('hang',cancel=cancel)
                child.request(b'{}',.2 if mode=='timeout' else 5)
                if mode=='cancel':cancel.set()
                until(lambda:child.process.poll() is not None,2)
                with self.assertRaisesRegex(WorkloadError,'TIMEOUT' if mode=='timeout' else 'CANCELLED'):child.check()
                child.close()

    def test_supervised_task_session_keeps_its_runner_alive_while_killing_all_workload_groups(self):
        completed=subprocess.run([sys.executable,FIXTURE,'supervised-owner'],cwd=self.root,
                                 start_new_session=True,capture_output=True,timeout=8)
        self.assertEqual(0,completed.returncode,'Supervised process fixture failed; private output suppressed')
        self.assertEqual(b'PASS supervised owner survives descendant cleanup\n',completed.stdout)

    def test_flooded_stdout_and_crashed_process_fail_with_fixed_reasons(self):
        child=self.process('flood');child.request(b'{}',5)
        with self.assertRaisesRegex(WorkloadError,'RESPONSE_TOO_LARGE'):self.reply(child)
        self.assertLessEqual(len(child._received),MAX_MESSAGE+65536)
        child.close();crashed=self.process('exit')
        until(lambda:crashed.process.poll() is not None)
        with self.assertRaisesRegex(WorkloadError,'WORKLOAD_EXITED'):crashed.check()

    def test_credentials_are_absent_from_workload_environment(self):
        with patch.dict(os.environ,{'EDGEAI_CLAIM_FILE':'fixture-private','EDGEAI_API_PASSWORD':'fixture-private','AWS_SECRET_ACCESS_KEY':'fixture-private'}):
            child=self.process('environment')
        child.request(b'{}',2)
        self.assertEqual({'EDGEAI_CLAIM_FILE':False,'EDGEAI_API_PASSWORD':False,'AWS_SECRET_ACCESS_KEY':False},json.loads(self.reply(child)))

    def test_repeated_close_never_signals_a_released_process_group(self):
        child=self.process('hang')
        child.close()
        # The old PID/PGID can now be reused by the OS for an unrelated process.
        with patch('edgeai_runner.stream_workload.os.killpg') as signal_group:
            child.close()
            signal_group.assert_not_called()

    def test_refresh_before_expiry_and_shortened_lease_are_both_enforced(self):
        child=self.process('hang',duration=.4)
        child.renew(time.monotonic()+1)
        time.sleep(.45);self.assertIsNone(child.process.poll())
        child.renew(time.monotonic()+.1)
        until(lambda:child.process.poll() is not None,2)
        with self.assertRaisesRegex(WorkloadError,'LEASE_EXPIRED'):child.check()


if __name__ == '__main__':unittest.main()
