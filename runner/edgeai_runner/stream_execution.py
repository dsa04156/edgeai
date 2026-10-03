"""Runner ownership of stream calculation, external checkpoint and finalization barrier.

The authenticated control plane supplies route generations and recovery intent. It
must durably grant FINALIZE for this exact terminal checkpoint before file work runs.
Public STREAM runs use this protocol when explicitly enabled by the deployment.
"""
import hashlib
import os

from edgeai_runner.main import RunnerError, cancelled, integer, port_name
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_checkpoint_client import CheckpointClient, CheckpointUnavailable
from edgeai_runner.stream_checkpoint import state_bytes
from edgeai_runner.stream_journal import Limits
from edgeai_runner.stream_processor import execution_digest
from edgeai_runner.stream_protocol import Producer, _uuid
from edgeai_runner.stream_session import Session


def require(value):
    if not value:
        raise RunnerError('INVALID_RESPONSE')


def fields(value, names):
    require(type(value) is dict and set(value) == set(names.split()))
    return value


def execution_spec(value):
    fields(value, 'command args inputs outputs stepTimeoutSeconds limits')
    for name, maximum in (('command',32),('args',128)):
        items = value[name]
        require(type(items) is list and len(items) <= maximum and
                all(type(v) is str and 0 < len(v) <= 4096 and '\0' not in v for v in items))
    require(value['command'] and value['command'][0].strip())
    for direction in ('inputs','outputs'):
        ports = value[direction]
        require(type(ports) is dict and len(ports) <= 16 and (ports or direction == 'outputs'))
        for port, spec in ports.items():
            port_name(port); fields(spec, 'mediaType maxPayloadBytes')
            from edgeai_runner.stream_protocol import MEDIA_TYPE
            require(type(spec['mediaType']) is str and len(spec['mediaType']) <= 127 and MEDIA_TYPE.fullmatch(spec['mediaType']))
            integer(spec['maxPayloadBytes'],1,262144)
    limits = fields(value['limits'], 'maxFrames maxBufferBytes maxStateBytes')
    integer(value['stepTimeoutSeconds'],1,3600)
    return Limits(integer(limits['maxFrames'],1,4096),integer(limits['maxBufferBytes'],1,67108864),
                  integer(limits['maxStateBytes'],1,1048576))


def execute(runner, assignment):
    spec = assignment['stream']; limits = execution_spec(spec)
    _uuid(assignment['runId']); _uuid(assignment['taskId'])
    require(not set(spec['inputs']) & set(assignment['inputs']) and not set(spec['outputs']) & set(assignment['outputs']))
    # Binding/checkpoint clients enforce TLS and reread projected credentials.
    client = BindingClient(runner.base.rsplit('/internal/',1)[0],
        Producer('TASK_ATTEMPT',runner.attempt,runner.epoch),runner.token_file,
        pod_uid=runner.pod,pod_token_file=runner.pod_token_file)
    while True:
        runner.timeout()
        reply = runner.api('streams/execution',runner.identity)
        if type(reply) is dict and reply == {'state':'WAITING'}:
            cancelled.wait(min(.1,runner.timeout())); continue
        if type(reply) is dict and reply.get('state') == 'FINALIZE':
            return _write_state(runner,_finalized_state(runner,assignment,reply,client,limits))
        fields(reply,'state runId taskId attemptId epoch inputs outputs recovery')
        require(reply['state'] == 'READY' and all(reply[k] == assignment[k] for k in ('runId','taskId','attemptId','epoch')))
        require(reply['recovery'] in ('NEW','RESTORE','HANDOVER'))
        require(type(reply['inputs']) is dict and set(reply['inputs']) == set(spec['inputs']))
        require(type(reply['outputs']) is dict and set(reply['outputs']) == set(spec['outputs']))
        break
    ids = list(reply['inputs'].values())
    for values in reply['outputs'].values():
        require(type(values) is list and values); ids.extend(values)
    checkpoint = CheckpointClient(client,assignment['runId'],ids)
    directory = runner.work/'stream'; directory.mkdir(mode=0o700)
    # fsGroup emptyDir is setgid: mkdir(0700) inherits 02000. This directory
    # was just created by this owner; retain the SDK's exact private-mode check.
    directory.chmod(0o700)
    session = Session(client,assignment['runId'],reply['inputs'],reply['outputs'],spec['command']+spec['args'],
        directory,assignment['parameters'],limits=limits,step_timeout=spec['stepTimeoutSeconds'],
        timeout=runner.timeout(86400),create=True,cancel=cancelled,durability='EXTERNAL',checkpoint_client=checkpoint,
        restore_latest=reply['recovery'] != 'NEW',handover_latest=reply['recovery'] == 'HANDOVER')
    terminal = None
    state = None
    def finalization(timeout):
        try:
            value = runner.api('streams/complete',{**runner.identity,'checkpointId':terminal['id']},
                               max_attempts=1,request_timeout=timeout)
        except RunnerError as error:
            if error.code == 'CONTROL_PLANE_UNAVAILABLE':
                return False
            raise
        fields(value,'state checkpointId')
        require(value['checkpointId'] == terminal['id'] and value['state'] in ('WAITING','FINALIZE'))
        return value['state'] == 'FINALIZE'
    try:
        for direction in ('inputs','outputs'):
            for port, generations in reply[direction].items():
                for identity in ([generations] if direction == 'inputs' else generations):
                    assigned = session.assignments[identity]; declared = spec[direction][port]
                    require(assigned.media_type == declared['mediaType'] and assigned.max_payload_bytes <= declared['maxPayloadBytes'])
        while True:
            runner.timeout()
            if terminal is not None and finalization(min(1,runner.timeout())):
                break
            try:
                session.step()
                receipt = session.terminal_receipt
                if receipt is not None:
                    terminal = receipt
                    state = session.journal.checkpoint().state
                    require(hashlib.sha256(state).hexdigest() == terminal['summary']['stateSha256'])
            except Exception:
                # A peer may already have obtained the component's durable grant,
                # after which route revocation can race this owner's next step.
                # Re-read that exact grant; a local END or expiry never grants it.
                if terminal is not None and finalization(min(1,runner.timeout())):
                    break
                raise
            cancelled.wait(.005)
    finally:
        session.close()
    return _write_state(runner,state)


def _finalized_state(runner, assignment, reply, binding, limits):
    fields(reply,'state runId taskId attemptId epoch checkpointId inputRoutes outputRoutes generationIds'+
           (' checkpointActor' if 'checkpointActor' in reply else ''))
    require(all(reply[k] == assignment[k] for k in ('runId','taskId','attemptId','epoch')))
    require(type(reply['epoch']) is int)
    checkpoint_actor=None
    if 'checkpointActor' in reply:
        source=fields(reply['checkpointActor'],'attemptId epoch')
        checkpoint_actor=Producer('TASK_ATTEMPT',source['attemptId'],integer(source['epoch'],1,9007199254740991))
        require(checkpoint_actor.id != binding.actor.id and checkpoint_actor.epoch < binding.actor.epoch)
    _uuid(reply['checkpointId'])
    spec=assignment['stream'];inputs=reply['inputRoutes'];outputs=reply['outputRoutes']
    require(type(inputs) is dict and set(inputs) == set(spec['inputs']))
    require(type(outputs) is dict and set(outputs) == set(spec['outputs']))
    require(all(type(routes) is list and 1 <= len(routes) <= 16 for routes in outputs.values()))
    incoming=list(inputs.values());outgoing=[route for routes in outputs.values() for route in routes]
    for route in incoming+outgoing:_uuid(route)
    require(len(outgoing) <= 16 and len(set(incoming+outgoing)) == len(incoming+outgoing))
    client=CheckpointClient(binding,assignment['runId'],reply['generationIds'])
    expected=execution_digest(spec['command']+spec['args'],assignment['parameters'],inputs,outputs,spec['stepTimeoutSeconds'])
    while True:
        runner.timeout()
        try:
            value=client.finalized(reply['checkpointId'],timeout=min(1,runner.timeout()),checkpoint_actor=checkpoint_actor)
            receipt=value['checkpoint'];saved=receipt['summary']['manifest']
            require(receipt['taskId'] == assignment['taskId'] and receipt['executionSha256'] == expected)
            require(Limits(**saved['limits']) == limits)
            require({b['routeId'] for b in saved['inputs']} == set(incoming)
                    and {b['routeId'] for b in saved['outputs']} == set(outgoing))
            snapshot=client.download(value,guard=runner.timeout,timeout=min(1,runner.timeout()),checkpoint_actor=checkpoint_actor)
            doc=snapshot.document()
            require(all(row['ended'] and row['received'] == row['committed'] > 0 and not row['frames'] for row in doc['routes']))
            # Cancellation/fencing can happen during S3 I/O. Re-read the exact
            # server grant before writing state or running any finalizer process.
            require(client.finalized(reply['checkpointId'],timeout=min(1,runner.timeout()),checkpoint_actor=checkpoint_actor)['checkpoint'] == receipt)
            runner.timeout()
            return state_bytes(doc['stateBase64'],limits.max_state_bytes)
        except CheckpointUnavailable:
            cancelled.wait(min(.1,runner.timeout()))


def _write_state(runner,state):
    runner.timeout()
    target = runner.work/'stream-state'
    fd = os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
    with os.fdopen(fd,'wb') as file:
        file.write(state);file.flush();os.fsync(file.fileno())
    return target
