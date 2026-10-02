"""Deterministic reader fixtures. Actual container measurement is exercised in test_runner."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.telemetry import Sampler


class TelemetryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.group = self.path / 'cgroup'; self.group.mkdir()
        self.tick = 0
        self.now = datetime(2026, 10, 2, tzinfo=timezone.utc)
        self.write('cpu.stat', 'usage_usec 100000\n')
        self.write('cpu.max', '50000 100000\n')
        self.write('memory.current', '8388608\n')
        self.write('memory.max', '67108864\n')
        self.sampler = Sampler(self.path, self.group, lambda: self.tick, lambda: self.now)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, content):
        (self.group / name).write_text(content)

    def sample(self):
        self.tick += 5; self.now += timedelta(seconds=5)
        return self.sampler.sample()

    def test_actual_counter_units_and_unlimited_or_reset_are_not_zero_filled(self):
        self.write('cpu.stat', 'usage_usec 1100000\n')
        first = self.sample()
        self.assertEqual(1000000, first['cpuUsageMicros'])
        self.assertEqual(500, first['cpuLimitMillicores'])
        self.assertEqual(5000, first['intervalMillis'])
        self.assertEqual(8388608, first['memoryBytes'])
        self.write('cpu.max', 'max 100000'); self.write('memory.max', 'max')
        self.write('cpu.stat', 'usage_usec 10\n')
        second = self.sample()
        self.assertIsNone(second['cpuUsageMicros']); self.assertIsNone(second['cpuLimitMillicores'])
        self.assertIsNone(second['memoryLimitBytes']); self.assertIsNone(second['latencyMicros'])
        self.assertEqual(8388608, second['memoryBytes'])

    def test_latency_is_fresh_monotonic_and_not_reused(self):
        path = self.path / 'telemetry.json'
        def save(seq, observed, micros=1200):
            path.write_text(json.dumps({'sequence': seq, 'observedAt': observed.isoformat(), 'latencyMicros': micros}))
        save(1, self.now); self.assertEqual(1200, self.sample()['latencyMicros'])
        self.assertIsNone(self.sample()['latencyMicros'])
        save(2, self.now - timedelta(seconds=31)); self.assertIsNone(self.sample()['latencyMicros'])
        save(3, self.now + timedelta(seconds=100)); self.assertIsNone(self.sample()['latencyMicros'])
        save(4, self.now, True); self.assertIsNone(self.sample()['latencyMicros'])
        save(5, self.now, 0); self.assertEqual(0, self.sample()['latencyMicros'])
        path.unlink(); path.symlink_to(self.group / 'memory.current'); self.assertIsNone(self.sample()['latencyMicros'])

    def test_missing_cgroup_and_invalid_files_never_invent_measurement(self):
        sampler = Sampler(self.path, None, lambda: self.tick, lambda: self.now)
        self.tick = 5
        self.assertIsNone(sampler.sample())
        (self.path / 'telemetry.json').write_text('x' * 4097)
        self.tick = 10; self.assertIsNone(sampler.sample())
        (self.path / 'telemetry.json').write_text('{"sequence":1,"sequence":2}')
        self.tick = 15; self.assertIsNone(sampler.sample())
        self.tick = 100; self.assertIsNone(sampler.sample())


if __name__ == '__main__':
    unittest.main()
