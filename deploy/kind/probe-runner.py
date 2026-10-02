"""Executed through stdin inside an owned, already claimed test Runner Pod.

Exercises the real internal API and S3 without printing credentials, URLs or payloads.
The normal workload is sleeping; this independent client deliberately submits bad results.
"""
import concurrent.futures
import hashlib
import io
import json
import threading
import urllib.error
import urllib.request
import uuid
import sys
sys.path.insert(0, '/opt/edgeai')
from edgeai_runner.main import Runner, json_encode
phase = 'initialization'


def main():
    global phase
    runner = Runner()

    def post(operation, payload):
        req = urllib.request.Request(runner.base + '/' + operation, method='POST', data=json_encode(payload),
            headers={'Authorization': 'Bearer ' + runner.token, 'X-EdgeAI-Pod-Token': runner.pod_token_file.read_text().strip(), 'Content-Type': 'application/json'})
        try:
            response = runner.http.open(req, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read(262145)
            assert len(content) <= 262144
            return response.status, json.loads(content) if content else {}

    phase = 'claim-replay'
    assignment = runner.api('claim', runner.identity)
    assert runner.api('claim', runner.identity)['attemptId'] == assignment['attemptId']
    assert post('claim', {**runner.identity, 'podUid': str(uuid.uuid4())})[0] == 409
    assert post('claim', {**runner.identity, 'epoch': runner.epoch + 1})[0] == 409
    phase = 'artifact-upload'
    content = b'{"sourceMode":"SYNTHETIC","features":[2,1],"score":0.25,"prediction":1}'
    manifest = {'port': 'output', 'sha256': hashlib.sha256(content).hexdigest(), 'bytes': len(content), 'mediaType': 'application/json'}
    grant = runner.api('uploads', {**runner.identity, 'outputs': [manifest]})['outputs'][0]
    version = runner.upload(grant, manifest, io.BytesIO(content))
    output = {**manifest, 'versionId': version}
    failures = []
    for name, changed in [('missing-version', {**output, 'versionId': str(uuid.uuid4())}),
                          ('wrong-size', {**output, 'bytes': len(content) + 1}),
                          ('wrong-hash', {**output, 'sha256': '0' * 64})]:
        phase = name
        status, body = post('commit', {**runner.identity, 'outputs': [changed]})
        assert status == 400 and body.get('code') == 'ARTIFACT_INVALID'
        failures.append({'case': name, 'status': status, 'code': body['code']})
    assert post('commit', {**runner.identity, 'outputs': []})[0] == 400
    # Start both requests together. Physical teardown may fence the second before auth;
    # if it reaches the transaction, it must replay the very same Result.
    phase = 'concurrent-commit'
    barrier = threading.Barrier(2)
    def commit():
        barrier.wait(timeout=5)
        return post('commit', {**runner.identity, 'outputs': [output]})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        values = list(executor.map(lambda _: commit(), range(2)))
    assert sum(status == 201 for status, _ in values) == 1
    result_ids = {body['resultId'] for status, body in values if status in (200, 201)}
    assert len(result_ids) == 1 and all(status in (200, 201, 401, 409) for status, _ in values)
    print(json.dumps({'rejectedArtifacts': failures, 'concurrentCommitStatuses': sorted(status for status, _ in values), 'resultId': next(iter(result_ids))}), flush=True)


try:
    main()
except Exception:
    print(json.dumps({'failedPhase': phase}), flush=True)
    raise SystemExit(1)
