"""Failure artifacts retain bounded transition facts while excluding response bodies and credentials."""
import json
from pathlib import Path
import runpy
import unittest

sanitize = runpy.run_path(str(Path(__file__).with_name('test-stream-kubernetes.py')))['driver_failure_evidence']


class FailureEvidenceTest(unittest.TestCase):
    def test_transition_captured_before_cleanup_keeps_only_allowlisted_fields(self):
        value = {'phase':'vd-distinct-offload-offload-releasing','type':'AssertionError','locations':'vd_stream_acceptance.py:202',
            'runId':'00000000-0000-0000-0000-000000000001','message':'private-canary',
            'attemptTransition':{'task':'sink','expectedOldState':'OFFLOADED','credential':'private-canary',
                'attempts':[{'id':'00000000-0000-0000-0000-000000000002','number':2,'epoch':2,'state':'DISPATCHING','body':'private-canary'}]}}
        result = sanitize(value)
        self.assertEqual('DISPATCHING', result['attemptTransition']['attempts'][0]['state'])
        self.assertEqual(2, result['attemptTransition']['attempts'][0]['epoch'])
        self.assertNotIn('private-canary', json.dumps(result))

    def test_unrecognized_values_and_non_integer_sequences_are_not_exported(self):
        result = sanitize({'attemptTransition':{'task':'root','expectedOldState':'FAILED',
            'attempts':[{'id':'private-canary','state':'private-canary','number':True,'epoch':'private-canary'}]}})
        self.assertEqual({'id':None,'state':None,'number':None,'epoch':None}, result['attemptTransition']['attempts'][0])
        self.assertNotIn('private-canary', json.dumps(result))

    def test_malformed_or_oversized_transition_is_omitted(self):
        for attempts in ['private-canary', [None], [{}]*5]:
            result = sanitize({'attemptTransition':{'task':'sink','expectedOldState':'OFFLOADED','attempts':attempts}})
            self.assertNotIn('attemptTransition', result)


if __name__=='__main__':
    unittest.main()
