"""Actual Python client -> authenticated Spring API -> real fixed-version S3."""
import json
from pathlib import Path
import sys

root=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(root/'runner'))
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_checkpoint import Snapshot,capture
from edgeai_runner.stream_checkpoint_client import CheckpointClient,CheckpointPublisher
from edgeai_runner.stream_journal import Journal,Limits
from edgeai_runner.stream_protocol import Binding,Producer,Frame

directory=Path(sys.argv[1]);config=json.loads((directory/'request.json').read_bytes())
binding=BindingClient(config['origin'],Producer('TASK_ATTEMPT',config['attemptId'],1),directory/'claim',
                      pod_uid=config['podUid'],pod_token_file=directory/'pod',allow_http_loopback=True)
client=CheckpointClient(binding,config['runId'],config['generationIds'],allow_http_loopback=True)
snapshot=Snapshot((directory/'snapshot.json').read_bytes())
assert client.latest()['checkpoint'] is None
doc=snapshot.document();manifest=doc['manifest']
inputs=[Binding(b['routeId'],b['generation'],Producer.parse(b['producer'])) for b in manifest['inputs']]
assert not manifest['outputs']
put=client.put
def tracked_put(*args,**kwargs):
    version=put(*args,**kwargs)
    # Retain owned version identity even if later validation fails.
    (directory/'version.txt').write_text(version)
    return version
client.put=tracked_put
with Journal(directory/'publisher',inputs,[],Limits(**manifest['limits']),create=True,durability='EXTERNAL') as journal:
    for b,n in zip(inputs,(4,5)):journal.receive(Frame(b,1,'DATA',str(n).encode(),'application/json'))
    journal.commit(0,journal.pending(),b'9')
    assert capture(journal,doc['executionSha256']).wire==snapshot.wire
    publisher=CheckpointPublisher(client,journal,doc['executionSha256'],lambda:None)
    for _ in range(3):
        publisher.step()
        assert all(position==0 for position in journal.processing_sequences().values())
    assert client.latest()['checkpoint'] is None
    publisher.step();assert publisher.confirmations==1
    assert all(position==1 for position in journal.processing_sequences().values())
    receipt=publisher.last_receipt
assert client.latest()['checkpoint']==receipt
assert client.upload(snapshot,None)['checkpoint']==receipt
assert client.commit(snapshot,None,receipt['versionId'])==receipt
(directory/'receipt.json').write_text(json.dumps(receipt))
print('STREAM_CHECKPOINT_CLIENT_PASS')
