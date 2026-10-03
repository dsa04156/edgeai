"""Generate an actual SDK snapshot using control-plane fixture bindings; no credentials."""
import json
from pathlib import Path
import sys

root=Path(__file__).resolve().parents[5]
sys.path.insert(0,str(root/'runner'))
from edgeai_runner.stream_checkpoint import capture,confirm
from edgeai_runner.stream_journal import Journal,Limits
from edgeai_runner.stream_protocol import Binding,Producer,Frame

directory=Path(sys.argv[1]);request=json.loads((directory/'request.json').read_bytes())
bindings=[Binding(b['routeId'],b['generation'],Producer.parse(b['producer'])) for b in request['bindings']]
with Journal(directory/'journal',bindings,[],Limits(max_frames=8),create=True,durability='EXTERNAL') as journal:
    for b,n in zip(bindings,(4,5)):journal.receive(Frame(b,1,'DATA',str(n).encode(),'application/json'))
    journal.commit(0,journal.pending(),b'9')
    snapshot=capture(journal,'a'*64)
    if request.get('advance',False):
        confirm(journal,snapshot.serial,snapshot.sha256)
        journal.receive(Frame(bindings[0],2,'DATA',b'5','application/json'))
        journal.commit(1,journal.pending(),b'14')
        snapshot=capture(journal,'a'*64)
    (directory/'snapshot.json').write_bytes(snapshot.wire)
print('STREAM_CHECKPOINT_API_FIXTURE_PASS')
