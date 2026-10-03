"""S3 test fixture: independent processes export and restore actual SQLite state."""
from pathlib import Path
import shutil
import sys

root=Path(__file__).resolve().parents[5]
sys.path[:0]=[str(root/'runner'),str(root/'runner/tests')]
from test_stream_journal import A,B,OUT,data
from edgeai_runner.stream_checkpoint import Snapshot,capture,restore
from edgeai_runner.stream_journal import Journal,Limits,Emission

directory=Path(sys.argv[2]); execution='a'*64
if sys.argv[1]=='export':
    with Journal(directory/'volume',[A,B],[OUT],Limits(max_frames=6),create=True,durability='EXTERNAL') as journal:
        journal.receive(data());journal.receive(data(B,payload=b'5'))
        journal.commit(0,journal.pending(),b'9',[Emission(OUT.route_id,b'9','application/json')])
        journal.receive(data(sequence=2,payload=b'2'))
        snapshot=capture(journal,execution)
        (directory/'snapshot.json').write_bytes(snapshot.wire)
        assert not journal.outgoing(confirmed_only=True)
    shutil.rmtree(directory/'volume')
elif sys.argv[1]=='restore':
    snapshot=Snapshot((directory/'downloaded.json').read_bytes())
    with restore(directory/'restored',snapshot,[A,B],[OUT],Limits(max_frames=6),
                 expected_sha256=sys.argv[3],execution_sha256=execution) as journal:
        assert journal.checkpoint().state==b'9'
        assert journal.checkpoint().revision==1
        assert journal.pending()==(data(sequence=2,payload=b'2'),)
        assert journal.outgoing(confirmed_only=True)==(data(OUT,payload=b'9'),)
        assert journal.receive(data())=='COMMITTED'
        journal.receive(data(B,sequence=2,payload=b'3'))
        total=int(journal.checkpoint().state)+sum(int(f.payload) for f in journal.pending())
        journal.commit(1,journal.pending(),str(total).encode(),[Emission(OUT.route_id,str(total).encode(),'application/json')])
        assert journal.checkpoint().state==b'14'
        assert len(journal.outgoing())==2 and len(journal.outgoing(confirmed_only=True))==1
    assert not (directory/'volume').exists()
else:
    raise ValueError('Invalid checkpoint probe phase')
print('STREAM_CHECKPOINT_'+sys.argv[1].upper()+'_PASS')
