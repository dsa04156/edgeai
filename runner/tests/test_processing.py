import unittest
import json
import sys
import hashlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from edgeai_runner.processing import process, ProcessingError
from test_runner import Fixture
from test_runner import ROOT


class ProcessingTest(unittest.TestCase):
    def test_engine_rejects_invalid_configuration_and_boolean_sample_counts(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":1,"outputPort":"output","pipelines":[
            {"name":"energy","input":{"port":"input","output":"signal"},"steps":[{"op":"rms","window":1}]}]}
        frame={"vdId":"vd-1","vdRevision":0,"capturedAt":"2026-10-12T00:00:00Z"}
        artifacts={"input":{"apiVersion":"edgeai.processing-output/v1","outputs":{"signal":{"values":[4]}}}}
        for invalid in [dict(config,sampleCount=True),dict(config,pipelines=[]),
                        dict(config,pipelines=config["pipelines"]*2),
                        dict(config,pipelines=[dict(config["pipelines"][0],steps=[{"op":"threshold","operator":"gt","value":0},{"op":"rms","window":1}])])]:
            with self.subTest(config=invalid),self.assertRaises(ProcessingError):process(invalid,frame,artifacts)

    def test_derived_output_records_upstream_content_and_preserves_frequency_coordinates(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":2,"outputPort":"output","pipelines":[
            {"name":"alarm","input":{"port":"input","output":"spectrum"},"steps":[{"op":"threshold","operator":"gt","value":3}]}]}
        frame={"vdId":"vd-2","vdRevision":0,"capturedAt":"2026-10-12T00:00:00Z"}
        artifact={"apiVersion":"edgeai.processing-output/v1","outputs":{"spectrum":{"values":[0,2,4],"frequenciesHz":[0,1,2]}}}
        first=process(config,frame,{"input":artifact})
        self.assertEqual(first["outputs"]["alarm"]["frequenciesHz"],[1,2])
        artifact["outputs"]["spectrum"]["values"]=[0,3,5]
        second=process(config,frame,{"input":artifact})
        self.assertNotEqual(first["upstreamSha256"],second["upstreamSha256"])

    def test_fft_rejects_irregular_sensor_timing_instead_of_inventing_frequencies(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":4,"outputPort":"output","pipelines":[
            {"name":"spectrum","input":{"sourceKey":"input","property":"signal"},"steps":[{"op":"fft","sampleRateHz":1}]}]}
        frame={"vdId":"vd-1","vdRevision":0,"capturedAt":"2026-10-12T00:00:08Z","sources":{"input":{"properties":{"signal":{"samples":[
            {"value":value,"observedAt":timestamp} for value,timestamp in zip([0,1,0,-1],["2026-10-12T00:00:00Z","2026-10-12T00:00:01Z","2026-10-12T00:00:05Z","2026-10-12T00:00:06Z"])]}}}}}
        with self.assertRaises(ProcessingError):process(config,frame,{})

    def test_processing_artifact_can_be_consumed_by_reference_ai_runner(self):
        artifact=json.dumps({"apiVersion":"edgeai.processing-output/v1","vdId":"vd-1","outputs":{"average":{"values":[2,3]}}}).encode()
        with Fixture([sys.executable,str(ROOT/"runner/examples/linear.py")],{"weights":[4,-1],"bias":-2,"inputPipeline":"average"}) as fixture:
            fixture.input_data=artifact
            fixture.assignment["inputs"]={"input":{"url":fixture.origin+"/input","bytes":len(artifact),"sha256":hashlib.sha256(artifact).hexdigest()}}
            self.assertEqual(fixture.finish(fixture.start())[0],0)
            result=json.loads(fixture.outputs["output"])
            self.assertEqual(result["score"],3)
            self.assertEqual(result["sourceMode"],"VD")

    def test_fft_reports_one_sided_amplitude_and_frequency_for_a_known_sine(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":4,"outputPort":"output",
                "pipelines":[{"name":"spectrum","input":{"port":"signal","output":"wave"},
                              "steps":[{"op":"fft","sampleRateHz":4}]}]}
        artifact={"apiVersion":"edgeai.processing-output/v1","outputs":{"wave":{"values":[0,1,0,-1]}}}
        result=process(config,{"vdId":"vd-2","vdRevision":0,"capturedAt":"2026-10-12T00:00:00Z"},{"signal":artifact})
        self.assertEqual(result["outputs"]["spectrum"]["frequenciesHz"],[0,1,2])
        for actual,expected in zip(result["outputs"]["spectrum"]["values"],[0,1,0]):
            self.assertAlmostEqual(actual,expected,places=12)

    def test_rms_computes_the_root_mean_square_of_an_artifact_window(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":2,"outputPort":"output",
                "pipelines":[{"name":"rms","input":{"port":"signal","output":"wave"},"steps":[{"op":"rms","window":2}]}]}
        artifact={"apiVersion":"edgeai.processing-output/v1","outputs":{"wave":{"values":[3,4]}}}
        result=process(config,{"vdId":"vd-2","vdRevision":0,"capturedAt":"2026-10-12T00:00:00Z"},{"signal":artifact})
        self.assertAlmostEqual(result["outputs"]["rms"]["values"][0],3.5355339059327378)

    def test_common_runner_processes_a_pinned_sensor_and_commits_the_output(self):
        config={"apiVersion":"edgeai.processing/v1","sampleCount":1,"outputPort":"output",
                "pipelines":[{"name":"alarm","input":{"sourceKey":"input","property":"temperature"},
                              "steps":[{"op":"threshold","operator":"gt","value":30}]}]}
        frame={"vdId":"vd-1","vdRevision":2,"capturedAt":"2026-10-12T00:00:00Z","processing":config,
               "sources":{"input":{"properties":{"temperature":{"value":32,"observedAt":"2026-10-12T00:00:00Z"}}}}}
        with Fixture([sys.executable,"-m","edgeai_runner.processing"],{"vdInputs":frame}) as fixture:
            self.assertEqual(fixture.finish(fixture.start())[0],0)
            self.assertEqual(json.loads(fixture.outputs["output"])["outputs"]["alarm"]["values"],[True])
            self.assertEqual(len(fixture.commits),1)

    def test_sensor_window_is_filtered_averaged_and_compared_in_order(self):
        config = {"apiVersion": "edgeai.processing/v1", "sampleCount": 4, "outputPort": "signal",
                  "pipelines": [{"name": "temperature", "input": {"sourceKey": "input", "property": "temperature"},
                                 "steps": [{"op": "filter", "mode": "lowPass", "alpha": 0.5},
                                           {"op": "movingAverage", "window": 2},
                                           {"op": "threshold", "operator": "gt", "value": 2}]}]}
        frame = {"vdId": "vd-1", "vdRevision": 0, "capturedAt": "2026-10-12T00:00:04Z",
                 "sources": {"input": {"deviceId": "sensor-1", "deviceUid": "uid-1",
                                       "properties": {"temperature": {"samples": [
                                           {"value": 1, "observedAt": "2026-10-12T00:00:01Z"},
                                           {"value": 3, "observedAt": "2026-10-12T00:00:02Z"},
                                           {"value": 5, "observedAt": "2026-10-12T00:00:03Z"},
                                           {"value": 7, "observedAt": "2026-10-12T00:00:04Z"}]}}}}}
        result = process(config, frame, {})
        self.assertEqual(result["outputs"]["temperature"]["values"], [False, True, True])
        self.assertEqual(result["vdId"], "vd-1")


if __name__ == "__main__":
    unittest.main()
