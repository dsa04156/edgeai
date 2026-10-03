"""Observe real VD Pods, processes and immutable DB barriers in an owned TLS fixture."""
import json
import uuid
from vd_acceptance import wait


class VDStreamObserver:
    def __init__(self, read, call, query, snapshot, resources, runner_digest, trust_name, restart):
        self.read, self.call, self.query = read, call, query
        self.snapshot, self.resources = snapshot, resources
        self.digest, self.trust, self.restart = runner_digest, trust_name, restart
        self.pods = snapshot.setdefault('observedVdPods', {})
        self.proofs = snapshot.setdefault('vdStream', {})

    def observe(self, current):
        for vd, runtime in current['vdTargets'].items():
            for pod in self.resources(vd):
                if pod['kind'] != 'Pod':
                    continue
                meta, spec = pod['metadata'], pod['spec']
                status = pod.get('status', {}).get('containerStatuses', [])
                if not status or not status[0].get('imageID'):
                    continue
                assert meta['labels']['app.kubernetes.io/managed-by'] == 'edgeai-vd-controller'
                if meta['uid'] != runtime['podUid'] and current['case'] in ('vd-shared-replace', 'vd-distinct-pod-recover'):
                    # The driver still reports the prior phase while awaiting public replacement readiness.
                    # The next checkpoint barrier must identify and validate the new generation explicitly.
                    continue
                assert meta['uid'] == runtime['podUid'] and meta['labels']['edgeai.io/vd-runtime-id'] == runtime['id']
                assert status[0]['imageID'].endswith('@' + self.digest) and status[0]['restartCount'] == 0
                assert spec['serviceAccountName'] == 'edgeai-runner' and spec['automountServiceAccountToken'] is False
                container = spec['containers'][0]
                assert container['command'] == ['python3', '/opt/edgeai/vd.py'] and container['securityContext']['readOnlyRootFilesystem']
                assert {'name': 'SSL_CERT_FILE', 'value': '/var/run/edgeai-trust/ca.crt'} in container['env']
                trust = next(v for v in spec['volumes'] if v['name'] == 'edgeai-trust')['configMap']
                assert trust['name'] == self.trust
                node = self.read(['get', 'node', spec['nodeName'], '-o', 'json'])
                assert node['metadata']['uid'] == runtime['nodeUid']
                self.pods[meta['uid']] = {'vdId': vd, 'runtimeId': runtime['id'], 'name': meta['name'], 'nodeUid': node['metadata']['uid'],
                    'imageID': status[0]['imageID'], 'trustedTls': True, 'restartCount': 0}

    def processes(self, pod, attempts, kill=None):
        # Inspect only exact child Runner command/cwd, never print environment or claim files.
        script = """import json,os,signal,sys
from pathlib import Path
value=json.load(sys.stdin);found={a:[] for a in value['attempts']}
for p in Path('/proc').iterdir():
 if not p.name.isdigit():continue
 try:
  command=(p/'cmdline').read_bytes().split(b'\\0')
  if command[1:3]!=[b'-m',b'edgeai_runner.main']:continue
  cwd=str((p/'cwd').resolve(strict=True))
  for a in found:
   if cwd=='/work/attempts/'+a:found[a].append(int(p.name))
 except (OSError,RuntimeError):pass
if value.get('kill'):
 ids=found[value['kill']];assert len(ids)==1
 os.kill(ids[0],signal.SIGKILL)
print(json.dumps({'counts':{a:len(ids) for a,ids in found.items()},'killed':bool(value.get('kill'))}))
"""
        return json.loads(self.call(['-n', 'edgeai-runtimes', 'exec', '-i', pod['name'], '--', 'python3', '-c', script],
                                   {'attempts': attempts, 'kill': kill}))

    def boundary(self, current):
        name, phase = current['case'], current['phase']
        run = str(uuid.UUID(current['runId']))
        proof = self.proofs.setdefault(name, {})
        self.observe(current)
        if phase.endswith('-first'):
            rows = self.query("SELECT t.id,a.id,r.producer_pod_uid,v.vd_runtime_id FROM edgeai.task t JOIN edgeai.task_attempt a ON a.task_id=t.id JOIN edgeai.runtime_instance r ON r.attempt_id=a.id LEFT JOIN edgeai.vd_task_allocation v ON v.runtime_id=r.id WHERE t.run_id='" + run + "' ORDER BY t.id").splitlines()
            assert len(rows) == 2
            attempts, children = {}, {}
            for row in rows:
                task, attempt, pod_uid, vd_runtime = row.split('|')
                key = next(k for k, v in current['tasks'].items() if v == task)
                target = current['taskExecutions'][key]
                attempts[key] = attempt
                if target['mode'] == 'VD':
                    actual = self.pods[pod_uid]
                    assert actual['runtimeId'] == vd_runtime and actual['vdId'] == target['vdId']
                    children.setdefault(pod_uid, []).append(attempt)
            for pod_uid, ids in children.items():
                assert self.processes(self.pods[pod_uid], ids)['counts'] == dict.fromkeys(ids, 1)
            if name.startswith('vd-shared'):
                assert len(children) == 1 and len(next(iter(children.values()))) == 2
            proof.update(initialAttempts=attempts, children=children, actualChildProcesses=sum(map(len, children.values())), initialVdTargets=current['vdTargets'])
            if name == 'vd-distinct':
                before = sorted(self.pods[p['podUid']]['runtimeId'] for p in current['vdTargets'].values())
                proof['apiRestart'] = self.restart()
                self.observe(current)
                assert before == sorted(self.pods[p['podUid']]['runtimeId'] for p in current['vdTargets'].values())
                for pod_uid, ids in children.items():
                    assert self.processes(self.pods[pod_uid], ids)['counts'] == dict.fromkeys(ids, 1)
                proof['apiRestart']['childrenPreserved'] = True
            if name.endswith('-pod-recover'):
                runtime = current['vdTargets'][current['taskExecutions']['sink']['vdId']]
                pod = self.pods[runtime['podUid']]
                actual = next(p for p in self.resources(pod['vdId']) if p['kind'] == 'Pod' and p['metadata']['uid'] == runtime['podUid'])
                assert actual['metadata']['labels']['edgeai.io/vd-runtime-id'] == runtime['id']
                self.call(['delete', '--raw', '/api/v1/namespaces/edgeai-runtimes/pods/' + pod['name'], '-f', '-'],
                          {'apiVersion': 'v1', 'kind': 'DeleteOptions', 'preconditions': {'uid': runtime['podUid']}})
                proof['deletedPodUid'] = runtime['podUid']
            elif name.endswith('-recover'):
                pod_uid = next(p for p, ids in children.items() if attempts['sink'] in ids)
                proof['childFault'] = self.processes(self.pods[pod_uid], [attempts['sink']], attempts['sink'])
                assert proof['childFault']['killed']
        elif phase.endswith('-recovered'):
            old = ','.join("'" + str(uuid.UUID(v)) + "'" for v in proof['initialAttempts'].values())
            rows = self.query('SELECT r.desired_state,r.observed_state,a.close_reason FROM edgeai.runtime_instance r JOIN edgeai.vd_task_allocation a ON a.runtime_id=r.id WHERE r.attempt_id IN(' + old + ')').splitlines()
            assert len(rows) == 2 and all(r in ('STOPPED|TERMINATED|PROCESS_EXIT', 'STOPPED|TERMINATED|POD_GONE') for r in rows)
            if name not in ('vd-shared-replace', 'vd-distinct-pod-recover'):
                assert rows == ['STOPPED|TERMINATED|PROCESS_EXIT'] * 2
            assert self.query("SELECT count(*) FROM edgeai.stream_checkpoint c JOIN edgeai.stream_checkpoint old ON old.id=c.handover_from_id WHERE c.run_id='" + run + "' AND c.serial=old.serial+1 AND c.state_revision=old.state_revision AND c.summary_json->>'stateSha256'=old.summary_json->>'stateSha256' AND c.object_key<>old.object_key") == '2'
            generations = ','.join("'" + str(uuid.UUID(r['generation']['id'])) + "'" for r in current['routes'])
            assert int(self.query('SELECT count(*) FROM edgeai.route_generation WHERE id IN(' + generations + ') AND closed_at IS NOT NULL')) == len(current['routes'])
            for pod_uid, ids in proof['children'].items():
                pod = self.pods[pod_uid]
                if any(p['kind'] == 'Pod' and p['metadata']['uid'] == pod_uid for p in self.resources(pod['vdId'])):
                    assert self.processes(pod, ids)['counts'] == dict.fromkeys(ids, 0)
                else:
                    assert name in ('vd-shared-replace', 'vd-distinct-pod-recover')
            if name in ('vd-shared-replace', 'vd-distinct-pod-recover'):
                before, after = current['replacement']['before'], current['replacement']['after']
                assert before == proof['initialVdTargets'][current['taskExecutions']['sink']['vdId']]
                assert after['podUid'] in self.pods and self.pods[after['podUid']]['runtimeId'] == after['id']
                assert not any(p['metadata']['uid'] == before['podUid'] for p in self.resources(self.pods[after['podUid']]['vdId']))
                assert self.query("SELECT count(*) FROM edgeai.vd_runtime old JOIN edgeai.vd_runtime new ON new.vd_id=old.vd_id WHERE old.id='" + str(uuid.UUID(before['id'])) + "' AND new.id='" + str(uuid.UUID(after['id'])) + "' AND old.observed_state='TERMINATED' AND old.updated_at<=new.created_at AND new.generation=old.generation+1") == '1'
                proof.update(replacement=current['replacement'], oldSupervisorPhysicallyGone=True, nextGenerationAfterTermination=True)
            proof.update(oldChildrenExited=True, oldGenerationsClosed=True, verifiedStateHandovers=2)
        elif phase.endswith('-finalizer-granted'):
            attempt = str(uuid.UUID(proof['initialAttempts']['sink']))
            runtime = current['vdTargets'][current['taskExecutions']['sink']['vdId']]
            pod = self.pods[runtime['podUid']]
            folder = '/work/attempts/' + attempt
            wait(lambda: self.call(['-n', 'edgeai-runtimes', 'exec', pod['name'], '--', 'python3', '-c',
                "from pathlib import Path;print(int(Path('" + folder + "/finalizer-entered').exists()))"]).strip() == b'1', 90, 'Original VD finalizer did not enter')
            grant = self.query("SELECT checkpoint_id,granted_at FROM edgeai.stream_task_completion WHERE attempt_id='" + attempt + "' AND granted_at IS NOT NULL")
            assert grant and self.query("SELECT count(*) FROM edgeai.task_result WHERE task_id='" + str(uuid.UUID(current['tasks']['sink'])) + "'") == '0'
            proof['finalizer'] = {'oldAttemptId': attempt, 'grant': grant, 'preservedResultId': current['preservedResult']['id'],
                'checkpointIds': self.query("SELECT id FROM edgeai.stream_checkpoint WHERE run_id='" + run + "' ORDER BY id").splitlines()}
            proof['finalizer']['childFault'] = self.processes(pod, [attempt], attempt)
        elif phase.endswith('-finalizer-restoring'):
            fault = proof['finalizer']
            task = str(uuid.UUID(current['tasks']['sink']))
            attempt = wait(lambda: self.query("SELECT id FROM edgeai.task_attempt WHERE task_id='" + task + "' AND number=2 AND state='RUNNING'"), 90, 'New VD finalizer did not claim')
            attempt = str(uuid.UUID(attempt))
            runtime = current['vdTargets'][current['taskExecutions']['sink']['vdId']]
            pod = self.pods[runtime['podUid']]
            folder = '/work/attempts/' + attempt
            def entered():
                flags = self.call(['-n', 'edgeai-runtimes', 'exec', pod['name'], '--', 'python3', '-c',
                    "from pathlib import Path;p=Path('" + folder + "');print(int((p/'finalizer-entered').exists()),int((p/'stream').exists()))"]).strip()
                if flags.startswith(b'0 '): return False
                assert flags == b'1 0', 'Finalizer retry opened a stream computation directory'
                return True
            wait(entered, 90, 'Restored VD finalizer did not enter')
            old = fault['oldAttemptId']
            assert self.query("SELECT desired_state,observed_state FROM edgeai.runtime_instance WHERE attempt_id='" + old + "'") == 'STOPPED|TERMINATED'
            assert self.processes(pod, [old, attempt])['counts'] == {old: 0, attempt: 1}
            assert self.query("SELECT predecessor_attempt_id,granted_attempt_id FROM edgeai.stream_finalization_recovery WHERE attempt_id='" + attempt + "'") == old + '|' + old
            assert self.query("SELECT checkpoint_id,granted_at FROM edgeai.stream_task_completion WHERE attempt_id='" + old + "'") == fault['grant']
            assert self.query("SELECT count(*) FROM edgeai.stream_task_completion WHERE attempt_id='" + attempt + "'") == '0'
            assert self.query("SELECT id FROM edgeai.stream_checkpoint WHERE run_id='" + run + "' ORDER BY id").splitlines() == fault['checkpointIds']
            assert self.query("SELECT count(*) FROM edgeai.route_generation WHERE run_id='" + run + "'") == '3'
            assert self.query("SELECT count(*) FROM edgeai.route_generation WHERE run_id='" + run + "' AND generation=1 AND closed_at IS NOT NULL") == '3'
            fault.update(newAttemptId=attempt, oldRuntimeStopped=True, originalGrantPreserved=True, checkpointHistoryPreserved=True, newComputationOpened=False)
            self.call(['-n', 'edgeai-runtimes', 'exec', pod['name'], '--', 'touch', folder + '/finalizer-release'])
        elif phase.endswith('-children-exited'):
            wait(lambda: self.query("SELECT count(*) FROM edgeai.vd_task_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id WHERE r.run_id='" + run + "' AND a.closed_at IS NULL") == '0', 90, 'Actual VD children did not exit')
            for runtime in current['vdTargets'].values():
                pod = self.pods[runtime['podUid']]
                found = self.resources(pod['vdId'])
                assert any(p['kind'] == 'Pod' and p['metadata']['uid'] == runtime['podUid'] for p in found)
                result = self.call(['-n', 'edgeai-runtimes', 'exec', pod['name'], '--', 'python3', '-c',
                                   "from pathlib import Path;assert Path('/work/.vd-ready').exists();assert not list(Path('/work/attempts').iterdir());print('EMPTY')"])
                assert result.strip() == b'EMPTY'
            proof['childrenReclaimedBeforeDrain'] = True
        elif phase.endswith('-done'):
            wait(lambda: all(not self.resources(vd) for vd in current['vdTargets']), 90, 'Actual VD resources did not drain')
            proof['resourcesRemaining'] = 0

    def result(self, case, row):
        result = row['result']
        target = case['taskExecutions'][row['task']]
        pod = self.pods[result['producerPodUid']]
        assert target['mode'] == 'VD' and pod['vdId'] == target['vdId'] and pod['runtimeId'] == result['vdRuntimeId']
        task, attempt = str(uuid.UUID(case['tasks'][row['task']])), str(uuid.UUID(result['attemptId']))
        assert self.query("SELECT count(*) FROM edgeai.runtime_instance r JOIN edgeai.vd_task_allocation a ON a.runtime_id=r.id WHERE r.task_id='" + task + "' AND r.attempt_id='" + attempt + "' AND a.vd_runtime_id='" + pod['runtimeId'] + "' AND r.producer_pod_uid='" + result['producerPodUid'] + "' AND a.closed_at IS NOT NULL") == '1'
