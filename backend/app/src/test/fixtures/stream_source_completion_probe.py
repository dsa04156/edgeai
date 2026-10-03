"""Real DeviceSource -> TLS MQTT -> model -> Spring/PG/S3 completion and Result.

The Java owner supplies already-running Pod identities. No completion/checkpoint
reply or MQTT authority is fabricated here; HTTP is confined to isolated loopback.
"""
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request

repo=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(repo/'runner'))
from edgeai_runner.stream_assignment import AssignmentError,BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient
from edgeai_runner.stream_journal import Emission,Limits
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_session import Session
from edgeai_runner.stream_source import DeviceSource

folder=Path(sys.argv[1]);config=json.loads((folder/'request.json').read_bytes())
identity={'epoch':1,'podUid':config['podUid']}
runner=BindingClient(config['origin'],Producer('TASK_ATTEMPT',config['attemptId'],1),folder/'claim',
                     pod_uid=config['podUid'],pod_token_file=folder/'pod',allow_http_loopback=True)
base=config['origin']+'/internal/v1/attempts/'+config['attemptId']+'/'


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
        status=error.code;error.close()
        assert status==expected
        return None


def wait(predicate,tick=lambda:None):
    end=time.monotonic()+30
    while not predicate():
        assert time.monotonic()<end,'Completion probe deadline exceeded'
        tick();time.sleep(.005)


def main():
    with ExitStack() as stack:
        assigned=post('streams/execution',identity)
        assert assigned['state']=='READY' and assigned['recovery']=='NEW' and not assigned['outputs']
        clients={};sources={};directories={}
        for name,value in config['sources'].items():
            client=BindingClient(config['origin'],Producer('DEVICE_SESSION',value['sessionId'],value['epoch'],value['deviceId']),
                                 folder/(name+'.token'),allow_http_loopback=True)
            clients[name]=client
            directory=folder/('source-'+name);directory.mkdir(mode=0o700);directories[name]=directory
            sources[name]=stack.enter_context(DeviceSource(client,config['runId'],[value['generationId']],directory,create=True,timeout=90))
        generations=list(assigned['inputs'].values())
        checkpoint=CheckpointClient(runner,config['runId'],generations,allow_http_loopback=True)
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
            artifact=json.dumps({'sum':14},separators=(',',':')).encode()
            manifest={'port':'result','bytes':len(artifact),'sha256':hashlib.sha256(artifact).hexdigest(),'mediaType':'application/json'}
            grant=post('uploads',{**identity,'outputs':[manifest]})['outputs'][0]
            request=urllib.request.Request(grant['url'],data=artifact,method='PUT',headers=grant['headers'])
            with runner.http.open(request,timeout=5) as response:
                assert response.status==200
                version=response.headers['x-amz-version-id'];assert version and version!='null'
            post('commit',{**identity,'outputs':[{**manifest,'versionId':version}]},201)
            (folder/'committed').touch()
            wait(lambda:(folder/'routes-closed').exists())
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
        main()
    except Exception as error:
        import traceback
        frames=traceback.extract_tb(error.__traceback__)
        locations=",".join(Path(f.filename).name+":"+str(f.lineno) for f in frames)
        print("STREAM_SOURCE_COMPLETION_FAILED "+type(error).__name__+" "+locations)
        sys.exit(1)
