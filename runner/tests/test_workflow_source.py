"""Authenticated workflow source contract and producer behavior."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_execution import execution_spec
from edgeai_runner.main import RunnerError


class WorkflowSourceContractTest(unittest.TestCase):
    def test_explicit_source_has_one_json_output_and_no_upstream_input(self):
        spec = {
            'source': 'WORKFLOW_DATA', 'command': ['edgeai-workflow-input'], 'args': [],
            'inputs': {}, 'outputs': {'sample': {'mediaType': 'application/json', 'maxPayloadBytes': 4096}},
            'stepTimeoutSeconds': 60,
            'limits': {'maxFrames': 128, 'maxBufferBytes': 16777216, 'maxStateBytes': 262144},
        }
        limits = execution_spec(spec)
        self.assertEqual(128, limits.max_frames)
        unmarked = {k: v for k, v in spec.items() if k != 'source'}
        with self.assertRaises(RunnerError):
            execution_spec(unmarked)


if __name__ == '__main__':
    unittest.main()
