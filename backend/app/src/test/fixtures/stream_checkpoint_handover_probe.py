"""Actual authenticated handover and S3 restore into the new Attempt, then advance."""
import json
import base64
from pathlib import Path
import sys
import time

root=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(root/'runner'))
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient,CheckpointPublisher
from edgeai_runner.stream_journal import Limits
from edgeai_runner.stream_protocol import Binding,Producer,Frame
from edgeai_runner.stream_workload import WorkloadProcess

directory=Path(sys.argv[1]);config=json.loads((directory/'request.json').read_bytes())
binding=BindingClient(config['origin'],Producer('TASK_ATTEMPT',config['attemptId'],config['epoch']),directory/'claim',
                      pod_uid=config['podUid'],pod_token_file=directory/'pod',allow_http_loopback=True)
client=CheckpointClient(binding,config['runId'],config['generationIds'],allow_http_loopback=True)
value=client.handover('a'*64);receipt=value['checkpoint'];manifest=receipt['summary']['manifest']
assert receipt['previousCheckpointId']==config['sourceId'] and receipt['serial']==config['sourceSerial']+1
inputs=[Binding(b['routeId'],b['generation'],Producer.parse(b['producer'])) for b in manifest['inputs']]
assert not manifest['outputs']
with client.recover(directory/'restored',inputs,[],Limits(**manifest['limits']),'a'*64,guard=lambda:None,handover=True) as journal:
    assert journal.checkpoint().state==b'9'
    for b,n in zip(inputs,(2,3)):journal.receive(Frame(b,2,'DATA',str(n).encode(),'application/json'))
    pending=journal.pending();work=directory/'model';work.mkdir(mode=0o700)
    process=WorkloadProcess([sys.executable,str(root/'runner/examples/stream_sum.py')],work,time.monotonic()+5)
    try:
        offered={str(index):json.loads(frame.encode()) for index,frame in enumerate(pending)}
        process.request(json.dumps({'apiVersion':'edgeai.stream-workload/v1','revision':journal.checkpoint().revision,
            'stateBase64':base64.b64encode(journal.checkpoint().state).decode(),'inputPorts':list(offered),
            'endedInputs':[],'inputs':offered,'outputs':{},'parameters':{'mode':'zip'}}).encode(),3)
        response=None
        while response is None:
            response=process.step()
            if response is None:time.sleep(.005)
        result=json.loads(response);state=base64.b64decode(result['stateBase64'])
        assert result['status']=='COMMIT' and result['consumed']=={name:2 for name in offered} and state==b'14'
        journal.commit(1,pending,state)
    finally:process.close()
    publisher=CheckpointPublisher(client,journal,'a'*64,lambda:None)
    for _ in range(4):publisher.step()
    assert publisher.confirmations==1 and publisher.last_receipt['previousCheckpointId']==receipt['id']
    assert all(position==2 for position in journal.processing_sequences().values())
assert client.latest()['checkpoint']['revision']==2
print('STREAM_CHECKPOINT_HANDOVER_PASS')
