"""Actual VD Pod/supervisor/control-plane acceptance. No Task/hardware acceptance is implied."""
import argparse
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent


def wait(check, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(.4)
    raise AssertionError(message)


class VDScenario:
    def __init__(self, context, report, env=None):
        env = os.environ if env is None else env
        self.env = dict(env)
        self.kubectl = ['kubectl', '--context', context, '--request-timeout=15s']
        self.context = context
        self.namespace = 'edgeai-runtimes'
        self.origin = env['EDGEAI_SMOKE_API_URL'].rstrip('/')
        self.proxy = env.get('EDGEAI_SMOKE_PROXY_URL', '').rstrip('/')
        for url in filter(None, [self.origin, self.proxy]):
            parsed = urllib.parse.urlsplit(url)
            assert parsed.scheme in ('http', 'https') and parsed.hostname and not any([parsed.username, parsed.password, parsed.path, parsed.query, parsed.fragment]), 'An API origin without credentials/path is required'
        self.auth = 'Basic ' + base64.b64encode((env['EDGEAI_API_USER'] + ':' + env['EDGEAI_API_PASSWORD']).encode()).decode()
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        self.created = []
        self.report_path = Path(report)
        self.report = {'scope': 'real-kubernetes-vd-idle-lifecycle', 'taskExecution': False, 'cases': []}
        for namespace in ['edgeai', self.namespace]:
            meta = self.kube(['get', 'namespace', namespace, '-o', 'json'])['metadata']
            assert meta.get('labels', {}).get('app.kubernetes.io/part-of') == 'edgeai'
            assert meta.get('labels', {}).get('app.kubernetes.io/managed-by') == 'edgeai-bootstrap' and 'deletionTimestamp' not in meta, 'Refusing unowned namespace'
        self.csrf = self.request('csrf')['token']
        self.prefix = 'vd-demo-' + uuid.uuid4().hex
        pin = json.loads((ROOT / 'deploy/kubernetes/overlays/dev/release.json').read_text())
        digest = env.get('EDGEAI_RUNNER_DIGEST', pin['runnerDigest'])
        assert re.fullmatch(r'sha256:[a-f0-9]{64}', digest), 'An immutable tested Runner digest is required'
        self.image = 'ghcr.io/dsa04156/edgeai-runner@' + digest
        self.report['runnerImage'] = self.image

    def kube(self, arguments, data=None):
        result = subprocess.run(self.kubectl + arguments, input=None if data is None else json.dumps(data), text=True, capture_output=True, timeout=25, env=self.env)
        assert result.returncode == 0, 'VD Kubernetes operation failed; private output suppressed'
        return json.loads(result.stdout) if result.stdout.strip() else None

    def request(self, path, method='GET', body=None, key=None, expected=200, recovery=20):
        headers = {'Authorization': self.auth}
        if self.csrf: headers['X-CSRF-TOKEN'] = self.csrf
        if key: headers['Idempotency-Key'] = key
        if body is not None: headers['Content-Type'] = 'application/json'
        base = self.proxy + '/api/control-plane/' if self.proxy else self.origin + '/api/v1/'
        request = urllib.request.Request(base + path, headers=headers, method=method, data=None if body is None else json.dumps(body).encode())
        deadline = time.monotonic() + recovery
        while True:
            try: response = self.client.open(request, timeout=10)
            except urllib.error.HTTPError as error: response = error
            except OSError:
                if method == 'GET' and time.monotonic() < deadline:
                    time.sleep(.3); continue
                raise AssertionError('VD API connection unavailable; private output suppressed') from None
            with response:
                status = response.status; payload = response.read(1048577)
            if method == 'GET' and status in (502, 503, 504) and time.monotonic() < deadline:
                time.sleep(.3); continue
            assert status in (expected if isinstance(expected, tuple) else (expected,)), f'VD {method} returned HTTP {status}, expected {expected}'
            assert len(payload) <= 1048576, 'VD response exceeded bound'
            try: return json.loads(payload)
            except Exception: raise AssertionError('Invalid VD response; private output suppressed') from None

    def publish(self, kind, suffix, spec):
        return self.request('profiles/' + kind, 'POST', {'key': self.prefix + '-' + suffix, 'version': '1.0.0', 'spec': spec}, expected=201)['id']

    def create(self, suffix, placement, impossible=False):
        service = json.loads((ROOT / 'contracts/profiles/service-execution.example.json').read_text())
        service.update(image=self.image, platform={'os': 'linux', 'architectures': ['amd64']})
        if impossible:
            service['resources']['requests']['cpu'] = '100000'
            service['resources']['limits']['cpu'] = '100000'
        sp = self.publish('SERVICE', suffix, service)
        vp = self.publish('VD', suffix, {'apiVersion': 'edgeai.vd/v1', 'type': 'emulation', 'serviceProfileVersionId': sp, 'sources': {},
            'state': {'mode': 'STATELESS'}, 'runtime': {'maxConcurrentTasks': 1, 'startupTimeoutSeconds': 15 if impossible else 180, 'drainTimeoutSeconds': 10}})
        vd = self.request('virtual-devices', 'POST', {'key': self.prefix + '-' + suffix, 'displayName': 'VD lifecycle acceptance', 'profileVersionId': vp, 'sources': [], 'placement': placement}, expected=201)
        self.created.append(vd['id'])
        status = self.execution(vd['id'])
        assert status['enabled'] and status['current'] is None, 'Enable VD execution explicitly; registration alone is not readiness'
        return vd

    def execution(self, vd): return self.request('virtual-devices/' + vd + '/execution')

    def command(self, vd, action, revision, key=None, expected=202):
        return self.request('virtual-devices/' + vd + '/' + action, 'POST', {'revision': revision}, key=key or str(uuid.uuid4()), expected=expected)

    def resources(self, vd):
        return self.kube(['-n', self.namespace, 'get', 'pods,secrets,jobs', '-l', 'edgeai.io/vd-id=' + vd, '-o', 'json'])['items']

    def ready(self, vd, operation):
        def check():
            status = self.execution(vd)
            op = self.request('operations/' + operation)
            assert op['state'] not in ('FAILED', 'SUPERSEDED'), 'VD operation failed before readiness'
            runtime = status['current']
            if not runtime or not runtime['ready'] or op['state'] != 'SUCCEEDED': return None
            resources = self.resources(vd)
            pods = [r for r in resources if r['kind'] == 'Pod']
            if len(pods) != 1 or any(r['kind'] == 'Job' for r in resources): return None
            pod = pods[0]
            if pod['metadata']['uid'] != runtime['podUid'] or pod.get('metadata', {}).get('deletionTimestamp'): return None
            containers = pod.get('status', {}).get('containerStatuses', [])
            if len(containers) != 1 or not containers[0]['ready']: return None
            assert pod['spec']['restartPolicy'] == 'Never' and containers[0]['restartCount'] == 0
            assert pod['spec']['containers'][0]['command'] == ['python3', '/opt/edgeai/vd.py']
            assert containers[0]['imageID'].endswith(self.image.split('@')[1]), 'VD did not run the tested digest'
            node = self.kube(['get', 'node', pod['spec']['nodeName'], '-o', 'json'])
            assert runtime['nodeUid'] == node['metadata']['uid'] and runtime['nodeName'] == node['metadata']['name']
            claims = [r for r in resources if r['kind'] == 'Secret']
            assert len(claims) == 1 and any(o['uid'] == claims[0]['metadata']['uid'] and o.get('controller') for o in pod['metadata'].get('ownerReferences', []))
            return runtime, pod
        return wait(check, 200, 'Actual VD supervisor did not reach authenticated Kubernetes readiness')

    def proof(self, pod):
        # Read only the Pod created in this scenario. All bearer values stay inside a private subprocess pipe.
        assert pod['metadata']['labels']['edgeai.io/vd-id'] in self.created
        code = "import json,os;from pathlib import Path;d=json.loads(Path('/work/.vd-owner').read_text());d.update(claim=Path(os.environ['EDGEAI_VD_CLAIM_FILE']).read_text().strip(),podToken=Path(os.environ['EDGEAI_POD_TOKEN_FILE']).read_text().strip());print(json.dumps(d))"
        result = subprocess.run(self.kubectl + ['-n', self.namespace, 'exec', pod['metadata']['name'], '--', 'python3', '-c', code], text=True, capture_output=True, timeout=20, env=self.env)
        assert result.returncode == 0 and len(result.stdout) < 32768, 'VD identity capture failed; private output suppressed'
        try: return json.loads(result.stdout)
        except Exception: raise AssertionError('VD identity capture invalid; private output suppressed') from None

    def stale_poll(self, vd, proof):
        # Called after physical deletion, using the original claim and pod-bound token in memory only.
        body = {k: proof[k] for k in ('runtimeId', 'generation', 'podUid', 'sessionId')}
        body.update(vdId=vd, sequence=0, state='RUNNING', active=[], completed=[])
        request = urllib.request.Request(self.origin + '/internal/v1/vd-runtimes/' + proof['runtimeId'] + '/poll', method='POST', data=json.dumps(body).encode(),
            headers={'Authorization': 'Bearer ' + proof['claim'], 'X-EdgeAI-Pod-Token': proof['podToken'], 'Content-Type': 'application/json'})
        try: response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error: response = error
        with response: assert response.status in (401, 409), 'Old VD producer was not fenced'

    def exercise(self, placement, suffix, restart=None):
        vd = self.create(suffix, placement); identity = vd['id']; key = str(uuid.uuid4())
        operation = self.command(identity, 'provision', 0, key)
        assert self.command(identity, 'provision', 0, key, 200)['id'] == operation['id']
        first, pod = self.ready(identity, operation['id']); assert first['generation'] == 1
        if placement['mode'] == 'NODE': assert first['nodeUid'] == placement['nodeId']
        restart_proof = None
        if restart:
            restart_proof = restart()
            self.csrf = self.request('csrf')['token']
            after, fresh = self.ready(identity, operation['id'])
            assert after['id'] == first['id'] and fresh['metadata']['uid'] == pod['metadata']['uid'], 'API restart changed the live VD generation'
            assert after['leaseUntil'] > first['leaseUntil'], 'The same supervisor did not renew its persisted poll session after restart'
        # API restart may change the direct forwarding address; take credentials only after recovery.
        proof = self.proof(pod)
        renamed = self.request('virtual-devices/' + identity, 'PATCH', {'revision': 0, 'displayName': 'Same VD, new name', 'sources': [], 'placement': placement})
        assert renamed['revision'] == 1 and self.execution(identity)['current']['id'] == first['id']
        replacement = self.command(identity, 'replace', 1)
        second, _ = self.ready(identity, replacement['id'])
        assert second['generation'] == 2 and second['id'] != first['id'] and second['podUid'] != first['podUid']
        snapshot = self.execution(identity); history = snapshot['runtimeHistory']
        assert len(history) == 2 and history[1]['observedState'] == 'TERMINATED'
        assert history[1]['updatedAt'] <= second['createdAt'], 'Next generation started before source termination was recorded'
        assert all(r['metadata']['uid'] != first['podUid'] for r in self.resources(identity))
        self.stale_poll(identity, proof); proof.clear()
        assert self.command(identity, 'provision', 0, key, 200)['id'] == operation['id']
        assert len(self.execution(identity)['runtimeHistory']) == 2
        drain = self.command(identity, 'drain', 1)
        wait(lambda: self.request('operations/' + drain['id'])['state'] == 'SUCCEEDED' and self.execution(identity)['current'] is None and not self.resources(identity), 80, 'Drain did not confirm physical Pod/Secret deletion')
        snapshot = self.execution(identity)
        assert len(snapshot['bindings']) == 2 and all(b['closedAt'] for b in snapshot['bindings'])
        assert self.request('virtual-devices/' + identity)['vd']['state'] == 'REGISTERED'
        self.report['cases'].append({'case': suffix, 'vdId': identity, 'placement': placement, 'generations': snapshot['runtimeHistory'], 'operations': snapshot['operations'], 'bindings': snapshot['bindings'], 'restart': restart_proof, 'stalePollFenced': True, 'resourcesRemaining': 0})
        print('PASS: actual VD ' + suffix + ' supervisor Ready, same identity across replacement, old producer fenced and physical drain confirmed', flush=True)

    def startup_failure(self):
        vd = self.create('startup-failure', {'mode': 'AUTO'}, impossible=True); identity = vd['id']
        operation = self.command(identity, 'provision', 0)
        unschedulable = []
        def failed():
            for pod in self.resources(identity):
                if pod['kind'] == 'Pod' and any(c.get('type') == 'PodScheduled' and c.get('status') == 'False' and c.get('reason') == 'Unschedulable' for c in pod.get('status', {}).get('conditions', [])):
                    unschedulable.append(pod['metadata']['uid'])
            op = self.request('operations/' + operation['id'])
            if op['state'] != 'FAILED': return None
            assert op['reason'] == 'STARTUP_TIMEOUT'
            state = self.execution(identity)
            return state if state['current'] is None and not self.resources(identity) else None
        snapshot = wait(failed, 80, 'Unschedulable VD did not time out and clean up')
        assert snapshot['runtimeHistory'][0]['failureReason'] == 'STARTUP_TIMEOUT'
        assert unschedulable, 'The real scheduler never reported the intended unschedulable Pod'
        self.report['cases'].append({'case': 'startup-failure', 'vdId': identity, 'unschedulablePodUids': sorted(set(unschedulable)), 'generations': snapshot['runtimeHistory'], 'operations': snapshot['operations'], 'resourcesRemaining': 0})
        print('PASS: actual unschedulable VD startup timeout, failed Operation and physical cleanup', flush=True)

    def cleanup(self):
        self.csrf = self.request('csrf')['token']
        for vd in self.created:
            self.request('virtual-devices/' + vd, 'DELETE')
            wait(lambda: self.execution(vd)['current'] is None and not self.resources(vd), 90, 'Scenario VD cleanup incomplete')

    def run(self, restart=None):
        try:
            self.exercise({'mode': 'AUTO'}, 'auto-restart' if restart else 'auto', restart)
            def target():
                for node in self.request('nodes?limit=100')['items']:
                    if node['status'] != 'READY' or node['architecture'] != 'amd64': continue
                    actual = self.kube(['get', 'node', node['name'], '-o', 'json'])
                    if not actual['spec'].get('unschedulable') and not any(t['effect'] in ('NoSchedule', 'NoExecute') for t in actual['spec'].get('taints', [])):
                        assert actual['metadata']['uid'] == node['id']; return node
                return None
            node = wait(target, 45, 'No matching real Node was observed')
            self.exercise({'mode': 'NODE', 'nodeId': node['id']}, 'node')
            self.startup_failure()
        finally:
            self.cleanup()
        self.report_path.parent.mkdir(exist_ok=True, parents=True)
        self.report_path.write_text(json.dumps(self.report, indent=2) + '\n')
        print('PASS: VD lifecycle evidence saved; Task execution and hardware acceptance remain separate', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--context', required=True)
    parser.add_argument('--report', default='.tools/vd-smoke.json')
    args = parser.parse_args()
    scenario = VDScenario(args.context, args.report)
    scenario.run()


if __name__ == '__main__':
    main()
