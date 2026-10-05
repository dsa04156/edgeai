"""Real DeviceSource -> TLS MQTT -> model -> Spring/PG/S3 -> Runner finalizer.

The Java owner supplies already-running Pod identities. No completion/checkpoint
reply or MQTT authority is fabricated here. API and S3 use real test TLS proxies.
"""
from contextlib import ExitStack
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

repo=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(repo/'runner'))
from edgeai_runner.stream_assignment import AssignmentError,AssignmentUnavailable,BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient
from edgeai_runner.stream_journal import Emission,Limits
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_session import Session
from edgeai_runner.stream_source import DeviceSource

def post(operation,body,expected=200):
    request=urllib.request.Request(base+operation,data=json.dumps(body).encode(),method='POST',headers={
        'Authorization':'Bearer '+(folder/'claim').read_text(),'X-EdgeAI-Pod-Token':(folder/'pod').read_text(),
        'Content-Type':'application/json'})
    try:
        with runner.http.open(request,timeout=5) as response:
            assert response.status==expected
            assert response.headers.get('Cache-Control')=='no-store'
            return json.loads(response.read(262145))
    except urllib.error.HTTPError as error:
        status=error.code
        if status!=expected:
            try:code=json.loads(error.read(8192)).get('code','')
            except (ValueError,AttributeError):code=''
            if not re.fullmatch('[A-Z_]+',str(code)):code='UNKNOWN'
            print('CONTROL_REJECTED '+operation+' '+str(status)+' '+code,flush=True)
        error.close()
        assert status==expected
        return None


def wait(predicate,tick=lambda:None):
    end=time.monotonic()+30
    while not predicate():
        assert time.monotonic()<end,'Completion probe deadline exceeded'
        tick();time.sleep(.005)


def open_source(client,run_id,generations,directory,tick,*,timeout=90,cancel=None):
    # Only bootstrap fetch failures are retried. DeviceSource validates every
    # assignment before creating a journal, and closes a failed constructor.
    end=time.monotonic()+timeout
    while True:
        remaining=end-time.monotonic()
        assert remaining>0,'Completion source bootstrap deadline exceeded'
        try:
            return DeviceSource(client,run_id,generations,directory,create=True,timeout=remaining,cancel=cancel)
        except AssignmentUnavailable:
            assert not (directory/'journal').exists(),'Bootstrap changed the source journal'
            tick()  # Keep existing peers alive while this source is unavailable.
            time.sleep(min(.05,max(0,end-time.monotonic())))


def main(path):
    global folder,runner,base
    folder=path;config=json.loads((folder/'request.json').read_bytes())
    identity={'epoch':1,'podUid':config['podUid']}
    runner=BindingClient(config['origin'],Producer('TASK_ATTEMPT',config['attemptId'],1),folder/'claim',
                         pod_uid=config['podUid'],pod_token_file=folder/'pod')
    base=config['origin']+'/internal/v1/attempts/'+config['attemptId']+'/'
    with ExitStack() as stack:
        assigned=post('streams/execution',identity)
        assert assigned['state']=='READY' and assigned['recovery']=='NEW' and not assigned['outputs']
        clients={};sources={};directories={}
        def tick_sources():
            for source in sources.values():source.step()
        for name,value in config['sources'].items():
            client=BindingClient(config['origin'],Producer('DEVICE_SESSION',value['sessionId'],value['epoch'],value['deviceId']),
                                 folder/(name+'.token'))
            clients[name]=client
            directory=folder/('source-'+name);directory.mkdir(mode=0o700);directories[name]=directory
            sources[name]=stack.enter_context(open_source(client,config['runId'],[value['generationId']],directory,tick_sources))
        generations=list(assigned['inputs'].values())
        checkpoint=CheckpointClient(runner,config['runId'],generations)
        spec=config['stream'];limits=spec['limits']
        directory=folder/'consumer';directory.mkdir(mode=0o700)
        consumer=stack.enter_context(Session(runner,config['runId'],assigned['inputs'],{},spec['command']+spec['args'],directory,
            {'mode':'zip'},limits=Limits(limits['maxFrames'],limits['maxBufferBytes'],limits['maxStateBytes']),
            timeout=90,create=True,durability='EXTERNAL',checkpoint_client=checkpoint))
        for name,numbers in [('a',(4,2)),('b',(5,3))]:
            source=sources[name];route=config['sources'][name]['routeId']
            for number in numbers:source.emit([Emission(route,str(number).encode(),'application/json')])
            source.emit([Emission(route,b'',None,'END')])

        def step():
            for source in sources.values():source.step()
            consumer.step()

        wait(lambda:consumer.terminal_receipt is not None and all(s.intent is not None for s in sources.values()),step)
        receipt=consumer.terminal_receipt
        assert consumer.journal.checkpoint().state==b'14'
        assert receipt==checkpoint.latest()['checkpoint']
        assert all(row['ended'] and row['received']==row['committed']==3 for row in receipt['summary']['routes'])
        for name,source in sources.items():
            assert source.settled and not source.completed
            assert clients[name].complete(config['sources'][name]['generationId'],3).state=='WAITING'
        # The task has not reported yet. Its sealed checkpoint alone cannot grant a source.
        (folder/'waiting').touch()
        wait(lambda:(folder/'continue').exists())

        if config['mode']=='cancel':
            assert post('streams/complete',{**identity,'checkpointId':receipt['id']},409) is None
            for source in sources.values():
                def reject():
                    try:source.step()
                    except AssignmentError:pass
                    assert not source.completed
                wait(lambda:source.closed,reject)
                assert source.closed and not source.completed
            print('STREAM_SOURCE_COMPLETION_CANCELLED')
        else:
            # Lose one source process's live state before the task releases the group.
            for source in sources.values():source.close()
            assert post('streams/complete',{**identity,'checkpointId':receipt['id']})=={'state':'FINALIZE','checkpointId':receipt['id']}
            consumer.close()
            (folder/'granted').touch()
            wait(lambda:(folder/'routes-closed').exists())
            current={'attemptId':config['attemptId'],'epoch':1,'podUid':config['podUid']}
            credentials=folder
            if config['mode']=='finalizer-retry':
                credentials=folder/'successor';current=json.loads((credentials/'identity.json').read_bytes())
                assert current['attemptId']!=config['attemptId'] and current['epoch']==2
            else:
                assert post('streams/execution',identity)['state']=='FINALIZE'
                assert post('claim',identity)['attemptId']==config['attemptId']
            # A fresh work volume has neither a journal nor model state. The real
            # Runner downloads the sealed S3 version, rechecks the grant and commits.
            work=folder/'runner-work';work.mkdir(mode=0o700)
            env=os.environ.copy()
            env.update(EDGEAI_ATTEMPT_ID=current['attemptId'],EDGEAI_ATTEMPT_EPOCH=str(current['epoch']),EDGEAI_POD_UID=current['podUid'],
                EDGEAI_CONTROL_PLANE_URL=config['origin'],EDGEAI_CLAIM_FILE=str(credentials/'claim'),
                EDGEAI_POD_TOKEN_FILE=str(credentials/'pod'),EDGEAI_WORK_DIR=str(work))
            result=subprocess.run([sys.executable,'-W','error::ResourceWarning',str(repo/'runner'/'runner.py')],
                env=env,capture_output=True,timeout=25)
            if result.returncode:
                for line in result.stdout.decode(errors='replace').splitlines():
                    if re.fullmatch(r'RUNNER_FAILED [A-Z_]+',line):print(line,flush=True)
                raise AssertionError('Actual Runner failed')
            assert result.stderr==b''
            assert result.stdout==b'RUNNER_WORKLOAD_START\nRUNNER_RESULT_COMMITTED\n'
            assert (work/'stream-state').read_bytes()==b'14' and not (work/'stream').exists()
            assert json.loads((work/'outputs'/'result').read_bytes())=={'sum':14}
            (folder/'committed').touch()
            # There is no MQTT authority after Result/route closure. A durable intent
            # can only query the authenticated completion receipt, never open a Link.
            for name,value in config['sources'].items():
                try:clients[name].fetch(value['generationId'])
                except AssignmentError:pass
                else:raise AssertionError('Closed route unexpectedly authorized MQTT')
                with DeviceSource(clients[name],config['runId'],[value['generationId']],directories[name],timeout=10) as recovered:
                    assert recovered.link is None and not recovered.assignments
                    wait(lambda:recovered.completed,recovered.step)
                    assert recovered.link is None and recovered.checkpoint().output_sequences[value['routeId']]==3
            print('STREAM_SOURCE_COMPLETION_PASS')


if __name__=="__main__":
    try:
        main(Path(sys.argv[1]))
    except Exception as error:
        import traceback
        frames=traceback.extract_tb(error.__traceback__)
        locations=",".join(Path(f.filename).name+":"+str(f.lineno) for f in frames)
        print("STREAM_SOURCE_COMPLETION_FAILED "+type(error).__name__+" "+locations)
        sys.exit(1)
