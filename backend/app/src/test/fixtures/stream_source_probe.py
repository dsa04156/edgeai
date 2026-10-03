"""Real Spring-authenticated Device source and TLS route-generation handover.

The Java parent fences/revokes the real broker generation and supplies the next
generation only after activation. The original consumer has not committed data.
"""
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT/'runner'))
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_journal import Emission, Journal, JournalError
from edgeai_runner.stream_mqtt import Link
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_session import Session
from edgeai_runner.stream_source import DeviceSource

phase = 'configuration'


def until(action, condition, seconds=8):
    end = time.monotonic()+seconds
    while time.monotonic() < end:
        action()
        if condition(): return
        time.sleep(.005)
    raise RuntimeError('Private probe deadline')


def run(root):
    global phase
    config = json.loads((root/'request.json').read_text())
    device = BindingClient(config['origin'], Producer('DEVICE_SESSION',config['sessionId'],config['deviceEpoch'],config['deviceId']),
                           root/'device.token',allow_http_loopback=True)
    task = BindingClient(config['origin'],Producer('TASK_ATTEMPT',config['attemptId'],config['attemptEpoch']),root/'runner.token',
                         pod_uid=config['podUid'],pod_token_file=root/'pod.token',allow_http_loopback=True)
    source_root = root/'device-source';source_root.mkdir(mode=0o700)
    old = config['generationId'];consumer = task.fetch(old)
    phase = 'old-generation-delivery-without-processing-ack'
    with DeviceSource(device,config['runId'],[old],source_root,create=True,timeout=30,completion=False) as source, \
            Journal(root/'uncommitted-consumer',[consumer.binding],[],create=True) as inbox:
        link = Link.from_assignments(inbox,[consumer])
        try:
            route = consumer.binding.route_id
            source.emit([Emission(route,b'4','application/json')],b'adapter-offset-1')
            source.emit([Emission(route,b'5','application/json')],b'adapter-offset-2')
            def pump(): source.step();link.step(.001)
            until(pump,lambda:inbox.usage()[0]==2)
            assert inbox.checkpoint().input_sequences[route]==0 and len(source.journal.outgoing())==2
            saved = source.checkpoint();serial = source.journal.snapshot_serial
        finally: link.close(force=True)
    # All old connections/owners are closed before the control plane switches routes.
    (root/'ready').write_text('SOURCE_CLOSED_WITH_TWO_UNCONFIRMED_FRAMES')
    phase = 'wait-for-real-broker-revocation-and-activation'
    until(lambda:None,lambda:(root/'next.json').exists(),15)
    current = json.loads((root/'next.json').read_text())['generationId']
    try:
        device.fetch(old)
        raise AssertionError('Old generation unexpectedly authenticated')
    except AssignmentError: pass
    phase = 'explicit-source-handover'
    try:
        DeviceSource(device,config['runId'],[current],source_root,completion=False)
        raise AssertionError('Implicit handover unexpectedly opened')
    except JournalError: pass
    session_root=root/'new-consumer';session_root.mkdir(mode=0o700)
    # No input was committed in the old generation; replay begins at sequence1.
    with DeviceSource(device,config['runId'],[current],source_root,handover=True,timeout=30,completion=False) as source, \
            Session(task,config['runId'],{'input':current},{},[sys.executable,str(ROOT/'runner/examples/stream_sum.py')],
                    session_root,create=True,timeout=30) as session:
        assert source.checkpoint()==saved and source.journal.snapshot_serial==serial+1
        assert len(source.journal.outgoing())==2
        assert all(frame.binding.generation>consumer.binding.generation for frame in source.journal.outgoing())
        def pump(): source.step();session.step()
        phase = 'new-generation-replay-and-calculation'
        until(pump,lambda:session.journal.checkpoint().state==b'9' and not source.journal.outgoing())
        source.emit([Emission(route,b'2','application/json')],b'adapter-offset-3')
        source.emit([Emission(route,b'3','application/json')],b'adapter-offset-4')
        until(pump,lambda:session.journal.checkpoint().state==b'14' and not source.journal.outgoing())
        phase = 'end-and-processing-ack'
        source.emit([Emission(route,b'',None,'END')],b'adapter-offset-5')
        until(pump,lambda:source.settled and session.settled)
        assert session.journal.checkpoint().input_sequences[route]==5
        assert source.checkpoint().output_sequences[route]==5 and source.heartbeats>=2
        final_serial=source.journal.snapshot_serial
        secret=next(iter(source.assignments.values())).connection.secret.encode()
        assert all(secret not in p.read_bytes() for p in (source_root/'journal').iterdir())
    phase = 'same-generation-restart'
    with DeviceSource(device,config['runId'],[current],source_root,handover=True,completion=False) as source:
        assert source.settled and source.checkpoint().state==b'adapter-offset-5'
        assert source.journal.snapshot_serial==final_serial


if __name__=='__main__':
    try: run(Path(sys.argv[1]))
    except BaseException as error:
        print(f'FAIL phase={phase} category={type(error).__name__}; private details suppressed',flush=True)
        sys.exit(1)
    print('PASS actual Spring Device source generation handover and TLS calculation 9-to-14',flush=True)
