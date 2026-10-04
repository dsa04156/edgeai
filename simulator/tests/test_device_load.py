import sys
from pathlib import Path
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_load import Sample, run_window, summarize


def devices(n):
    return [{'id': str(i), 'session': {'id': 'session-'+str(i)}, 'sequence': 0, 'observationCount': 0} for i in range(n)]


class DeviceLoadTest(unittest.TestCase):
    def test_queued_latency_is_not_reported_as_fast_service_time(self):
        summary = summarize([Sample('observation', 0, 1, 1.005, 201, True)], 0, 1)
        self.assertAlmostEqual(5, summary['requestMs']['p95'])
        self.assertAlmostEqual(1005, summary['scheduledLatencyMs']['p95'])
        self.assertAlmostEqual(1000, summary['maxDispatchDelayMs'])
        self.assertLess(summary['completedRps'], summary['offeredRps'])

    def test_slow_target_cannot_silently_reduce_offered_work(self):
        class Slow:
            def request(self, method, path, body=None):
                time.sleep(.05)
                return (201, body, []) if method == 'POST' else (200, {'device': {'id': path.split('/')[-1]}}, [])
        report = run_window(Slow(), devices(20), .1, .1, workers=1)
        self.assertEqual(22, report['summary']['offered'])
        self.assertGreater(report['summary']['dropped'], 0)
        self.assertLessEqual(report['summary']['sent'], 4)
        self.assertEqual(22, len(report['samples']))

    def test_http_success_with_wrong_session_is_failure(self):
        class Wrong:
            def request(self, method, path, body=None):
                return (201, {**body, 'sessionId': 'wrong'}, []) if method == 'POST' else (200, {'device': {'id': '0'}}, [])
        population = devices(1)
        report = run_window(Wrong(), population, .02, .02, workers=1)
        self.assertEqual(1, report['summary']['unexpected'])
        self.assertEqual(0, population[0]['observationCount'])


if __name__ == '__main__':
    unittest.main()
