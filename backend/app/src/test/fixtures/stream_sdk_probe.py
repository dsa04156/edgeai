"""Actual Python SDK -> Spring HTTP -> TLS MQTT -> SQLite processing/ack probe.

The parent owns the API/database/broker. Runtime Pod attestation remains an explicit
Java gateway fixture. Only a fixed success/failure marker is written to output.
"""
import json
from pathlib import Path
import sys
import time

sys.path.insert(0,str(Path(__file__).resolve().parents[5]/'runner'))
from edgeai_runner.stream_assignment import AssignmentError, BindingClient
from edgeai_runner.stream_protocol import Producer
from edgeai_runner.stream_journal import Emission, Journal
from edgeai_runner.stream_mqtt import Link

phase='configuration'

class ProbeFailure(RuntimeError):
    """Only fixture-authored codes and counts; never application data."""


def run(root):
    global phase
    value=json.loads((root/'request.json').read_text())
    source=Producer('DEVICE_SESSION',value['sessionId'],value['deviceEpoch'],value['deviceId'])
    consumer=Producer('TASK_ATTEMPT',value['attemptId'],value['attemptEpoch'])
    device_client=BindingClient(value['origin'],source,root/'device.token',allow_http_loopback=True)
    runner_client=BindingClient(value['origin'],consumer,root/'runner.token',pod_uid=value['podUid'],
                                pod_token_file=root/'pod.token',allow_http_loopback=True)
    phase='device-discovery'
    producer=device_client.fetch(value['generationId'])
    phase='runner-discovery'
    incoming=runner_client.fetch(value['generationId'])
    if producer.binding != incoming.binding or producer.direction!='PRODUCER' or incoming.direction!='CONSUMER':
        raise RuntimeError('Invalid actual SDK bindings')
    phase='journal'
    with Journal(root/'source',[],[producer.binding],create=True) as source_journal, Journal(root/'consumer',[incoming.binding],[],create=True) as sink:
        source_link=Link.from_assignments(source_journal,[producer])
        sink_link=Link.from_assignments(sink,[incoming])
        def pump_until(condition):
            until=time.monotonic()+8
            while time.monotonic()<until:
                source_link.step(.001);sink_link.step(.001)
                if condition():return
                time.sleep(.005)
            raise ProbeFailure(f'progress timeout source_ready={source_link.ready} sink_ready={sink_link.ready} '
                f'source_pending={len(source_journal.outgoing())} sink_pending={len(sink.pending())} '
                f'rejected={sink_link.rejected} gaps={sink_link.gaps} backpressure={sink_link.backpressured}')
        try:
            phase='mqtt-connect'
            pump_until(lambda:source_link.ready and sink_link.ready)
            phase='bilateral-heartbeat'
            # Both real authenticated Spring endpoints renew the same generation.
            # Carry the original links past their original deadline before computing.
            deadline=max(producer.deadline,incoming.deadline)
            producer_sequence=device_client.heartbeat(value['generationId'],0).sequence
            consumer_sequence=runner_client.heartbeat(value['generationId'],0).sequence
            while time.monotonic()<deadline+.2:
                producer_sequence+=1;consumer_sequence+=1
                device_client.heartbeat(value['generationId'],producer_sequence)
                reply=runner_client.heartbeat(value['generationId'],consumer_sequence)
                source_link.refresh([device_client.heartbeat(value['generationId'],producer_sequence).assignment])
                sink_link.refresh([reply.assignment])
                until=time.monotonic()+.4
                while time.monotonic()<until:
                    source_link.step(.001);sink_link.step(.001);time.sleep(.005)
            phase='mqtt-data'
            source_journal.commit(0,[],b'',[
                Emission(producer.binding.route_id,b'4','application/json'),
                Emission(producer.binding.route_id,b'5','application/json')])
            # A real reference calculation, then an atomic journal commit and
            # consumer processing acknowledgement, distinct from broker PUBACK.
            for sequence in (1,2):
                pump_until(lambda:len(sink.pending())==1)
                if sequence==1 and len(source_journal.outgoing())!=2:raise RuntimeError('Output discarded before processing')
                pending=sink.pending();checkpoint=sink.checkpoint()
                if pending[0].sequence!=sequence:raise RuntimeError('Unexpected actual SDK sequence')
                total=str(int(checkpoint.state or b'0')+int(pending[0].payload)).encode()
                sink.commit(checkpoint.revision,pending,total)
            if total!=b'9':raise RuntimeError('Unexpected actual SDK calculation')
            phase='processing-ack'
            pump_until(lambda:not source_journal.outgoing())
            if sink.checkpoint().state!=b'9':raise RuntimeError('Actual SDK state not committed')
            for assignment,directory in [(producer,root/'source'),(incoming,root/'consumer')]:
                for file in directory.iterdir():
                    if assignment.connection.secret.encode() in file.read_bytes():raise RuntimeError('Credential persisted in journal')
        finally:
            source_link.close();sink_link.close()


if __name__=='__main__':
    try:
        run(Path(sys.argv[1]))
    except BaseException as error:
        reason=str(error) if type(error) in (AssignmentError,ProbeFailure) else 'private details suppressed'
        print(f'FAIL phase={phase} category={type(error).__name__} reason={reason}',flush=True)
        sys.exit(1)
    print('PASS actual Spring/Python TLS stream SDK calculation and processing acknowledgement',flush=True)
