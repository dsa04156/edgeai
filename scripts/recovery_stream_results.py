"""Verify a restored STREAM completion barrier before restoring original Results.

This consumes a barrier already sealed in the backup. It never invents a missing
grant or reopens a route, and retains both database and producer quarantine.
"""
import hashlib
import json
from types import SimpleNamespace

from postgres_backup import Blocked
from recovery_runtime_starts import instant_ns
from recovery_remote_storage import Storage, private_json, header, version
from recovery_work_digest import canonical

TABLES=('device','device_session','stream_run_configuration','stream_run_binding','stream_device_binding',
        'route_generation','route_heartbeat','stream_checkpoint','stream_task_completion',
        'stream_device_completion','stream_finalization_recovery')


def catalog_query():
    names={'runs':'workflow_run','configurations':'stream_run_configuration','bindings':'stream_run_binding',
           'routes':'data_route','generations':'route_generation','completions':'stream_task_completion',
           'deviceCompletions':'stream_device_completion','checkpoints':'stream_checkpoint',
           'inheritance':'stream_finalization_recovery','tasks':'task','attempts':'task_attempt','runtimes':'runtime_instance',
           'commands':'runtime_command','allocations':'vd_task_allocation'}
    contracts="""'contracts',(SELECT coalesce(jsonb_agg(jsonb_build_object('taskId',t.id,
        'serviceProfileVersionId',d.service_profile_version_id,
        'workJson',jsonb_build_object('spec',p.spec,'parameters',d.parameters||w.parameters)::text) ORDER BY t.id),'[]'::jsonb)
        FROM edgeai.task t JOIN edgeai.task_definition d ON d.id=t.definition_id
        JOIN edgeai.profile_version p ON p.id=d.service_profile_version_id JOIN edgeai.workflow_run w ON w.id=t.run_id)"""
    return 'SELECT jsonb_build_object('+contracts+','+','.join("'"+key+"',(SELECT coalesce(jsonb_agg(to_jsonb(x)"+
        ("-'claim_nonce'" if table=='runtime_instance' else '')+" ORDER BY to_jsonb(x)::text),'[]'::jsonb) FROM edgeai."+table+' x)'
        for key,table in names.items())+')'


def checkpoint_object(store,manifest,cp,terminal=True):
    from edgeai_runner import stream_checkpoint as codec
    if (not 0<cp['bytes']<=codec.MAX_BYTES or cp['object_key']!='tasks/'+cp['task_id']+'/attempts/'+cp['attempt_id']+'/stream-checkpoint/'+cp['sha256']):
        raise Blocked('Sealed checkpoint size or key is outside the original task scope')
    item={'bucket':cp['bucket'],'key':cp['object_key'],'versionId':cp['object_version'],'bytes':cp['bytes'],'sha256':cp['sha256']}
    captured=[v for v in manifest['versions'] if all(v[k]==item[k] for k in ('bucket','key','versionId'))]
    if len(captured)!=1 or any(captured[0][k]!=item[k] for k in ('bytes','sha256')):
        raise Blocked('Sealed checkpoint version is absent from the independent backup')
    code,headers,wire=store.request('GET','/'+item['bucket']+'/'+item['key'],{'versionId':version(item['versionId'])},max_bytes=codec.MAX_BYTES)
    if (code!=200 or header(headers,'x-amz-version-id')!=item['versionId'] or
            header(headers,'Content-Length')!=str(item['bytes']) or len(wire)!=item['bytes'] or
            hashlib.sha256(wire).hexdigest()!=item['sha256'] or
            any(k.lower() in ('content-encoding','transfer-encoding') for k,v in headers)):
        raise Blocked('Original sealed checkpoint bytes are missing or changed')
    try:
        value=codec.validate(wire)
        state=codec.state_bytes(value['stateBase64'],value['manifest']['limits']['max_state_bytes'])
    except (ValueError,TypeError,KeyError) as error:raise Blocked('Invalid sealed checkpoint document') from error
    summary={'manifest':value['manifest'],'revision':value['revision'],
        'routes':[{k:v for k,v in row.items() if k!='frames'} for row in value['routes']],
        'stateSha256':hashlib.sha256(state).hexdigest(),'stateBytes':len(state)}
    if (summary!=cp['summary_json'] or value['serial']!=cp['serial'] or value['revision']!=cp['state_revision'] or
            value['executionSha256']!=cp['execution_sha256'] or
            terminal and any(not row['ended'] or row['received']<1 or row['received']!=row['committed'] for row in value['routes'])):
        raise Blocked('Checkpoint does not preserve the recorded terminal computation')
    return {'checkpointId':cp['id'],**item}


def inherited_grant(task,attempt,grant,attempts,runtimes,links,run):
    """Follow the recorded finalizer chain back to the original sealed actor."""
    cursor=attempt;seen=set()
    while cursor['id']!=grant['attempt_id']:
        link=links.get(cursor['id']);previous=None if link is None else attempts.get(link['predecessor_attempt_id'])
        if (cursor['id'] in seen or link is None or previous is None or link['granted_attempt_id']!=grant['attempt_id'] or
                cursor['task_id']!=task['id'] or previous['task_id']!=task['id'] or previous['state']!='FAILED' or
                cursor['cause']!='RETRY' or cursor['mode'] not in ('AUTO','NODE','VD') or
                cursor['epoch']!=previous['epoch']+1 or cursor['number']!=previous['number']+1 or
                instant_ns(link['created_at'])!=instant_ns(cursor['created_at']) or
                instant_ns(link['created_at'])<instant_ns(grant['granted_at'])):
            raise Blocked('Finalizer Result does not inherit the original sealed grant')
        previous_runtime=runtimes.get(previous['id'])
        first=min((a for a in attempts.values() if a['task_id']==task['id']),key=lambda a:a['number'])
        retry_count=sum(a['task_id']==task['id'] and a['number']<=cursor['number'] and a['cause']!='OFFLOAD' for a in attempts.values())
        if (previous_runtime is None or previous_runtime['failure_reason'] not in run['retry_on'] or
                retry_count>run['retry_max_attempts'] or
                instant_ns(cursor['created_at'])>=instant_ns(first['created_at'])+run['retry_max_elapsed_seconds']*10**9 or
                instant_ns(cursor['created_at'])<instant_ns(previous['updated_at'])+run['retry_backoff_seconds']*10**9):
            raise Blocked('Finalizer successor exceeds its original retry policy')
        seen.add(cursor['id']);cursor=previous
    if {a for a in links if attempts[a]['task_id']==task['id']}!=seen:
        raise Blocked('Unaccounted finalizer inheritance cannot authorize a Result')
    return sorted(seen)


def observe(pg,args,catalog,retired,evidence,entries):
    import recovery_stream_retire as broker
    from recovery_stream_workflows import components
    required=('broker_digest','mqtt_state_directory','mqtt_ca_file','mqtt_original_password_file')
    if not all(getattr(args,k,None) for k in required):raise Blocked('STREAM Results require current original broker revocation proof')
    try:authority=broker.prepare(pg,args)
    except ValueError as error:raise Blocked('Original STREAM broker recovery identity is invalid') from error
    if catalog['brokerGuard']!=authority['beforeGuard']:
        raise Blocked('STREAM state changed during broker observation')
    state=catalog['stream'];runs={r['id']:r for r in state['runs']};tasks={t['id']:t for t in state['tasks']}
    attempts={a['id']:a for a in state['attempts']};runtimes={r['attempt_id']:r for r in state['runtimes']}
    checkpoints={c['id']:c for c in state['checkpoints']};links={l['attempt_id']:l for l in state['inheritance']}
    contracts={c['taskId']:c for c in state['contracts']}
    proven=set(retired['selected']['runtimes']+retired['selected']['vdTasks'])
    proofs={p['uid']:p for p in retired['evidence']['pods']}
    manifest,manifest_hash=private_json(args.runtime_start_backup/'manifest.json')
    if manifest_hash!=evidence['startEvidence']['storageBackupSha256']:raise Blocked('STREAM and Result backup snapshots differ')
    store=Storage(SimpleNamespace(certificate_sha256=args.runtime_start_certificate_sha256,timeout=args.timeout))
    if store.identity()!=manifest['targetDeploymentId']:raise Blocked('STREAM backup storage identity differs')
    objects={};groups=[];barriers=[]
    for run_id in sorted({e['runId'] for e in entries}):
        run=runs[run_id];routes=[r for r in state['routes'] if r['run_id']==run_id]
        configurations=[c for c in state['configurations'] if c['run_id']==run_id]
        bindings=[b for b in state['bindings'] if b['run_id']==run_id]
        route_digest='sha256:'+hashlib.sha256(b'edgeai-stream-membership-v1\n'+canonical(sorted(r['id'] for r in routes)).encode()).hexdigest()
        if (len(configurations)!=1 or len(bindings)!=1 or bindings[0]['route_digest']!=route_digest or
                configurations[0]['namespace']!=args.namespace or configurations[0]['broker_digest']!=args.broker_digest):
            raise Blocked('Exact frozen STREAM membership and original configuration are required')
        history=[g for g in state['generations'] if g['run_id']==run_id]
        parts=components(routes);covered={t for part in parts for t in part}
        groups.extend({'runId':run_id,'taskIds':part} for part in parts)
        groups.extend({'runId':run_id,'taskIds':[t['id']]} for t in state['tasks'] if t['run_id']==run_id and t['id'] not in covered)
        selected={e['taskId']:e for e in entries if e['runId']==run_id}
        for part in parts:
            if not set(part)&selected.keys():continue
            connected=[r for r in routes if r['consumer_task_id'] in part];sealed={};times=set();grant_rows=[]
            route_ids={r['id'] for r in connected}
            if any(g['route_id'] in route_ids and (g['broker_digest']!=args.broker_digest or
                    g['closed_at'] is None or g['fenced_at'] is None) for g in history):
                raise Blocked('Every original component route must already be fenced and closed')
            members={r['id']:r for r in state['runtimes'] if r['task_id'] in part}
            if (any(rid not in proven or r['desired_state']!='STOPPED' or r['observed_state']!='TERMINATED' for rid,r in members.items()) or
                    any(c['runtime_id'] in members and (not c['completed'] or c['lease_owner'] is not None or c['lease_until'] is not None) for c in state['commands']) or
                    any(a['runtime_id'] in members and a['closed_at'] is None for a in state['allocations'])):
                raise Blocked('Every recorded component producer needs retirement and retained termination evidence')
            for task_id in part:
                completions=[c for c in state['completions'] if attempts[c['attempt_id']]['task_id']==task_id and c['granted_at'] is not None]
                if len(completions)!=1:raise Blocked('Entire STREAM group requires its original sealed completion barrier')
                grant=completions[0];cp=checkpoints[grant['checkpoint_id']];source=runtimes.get(grant['attempt_id'])
                all_cp=[c for c in checkpoints.values() if c['task_id']==task_id]
                if (source is None or source['producer_pod_uid'] is None or cp['id']!=max(all_cp,key=lambda c:c['serial'])['id'] or
                        cp['service_profile_version_id']!=contracts[task_id]['serviceProfileVersionId'] or
                        any(cp[k]!=v for k,v in {'run_id':run_id,'task_id':task_id,'attempt_id':grant['attempt_id'],
                            'runtime_id':source['id'],'epoch':source['epoch'],'producer_pod_uid':source['producer_pod_uid']}.items()) or
                        instant_ns(grant['created_at'])<instant_ns(cp['created_at']) or instant_ns(grant['granted_at'])<instant_ns(grant['created_at'])):
                    raise Blocked('Sealed STREAM grant differs from its latest checkpoint and original producer')
                proof=proofs.get(source['producer_pod_uid'])
                containers=[] if proof is None else [c for c in proof['containers'] if c['name']==('vd-supervisor' if source['runtime_kind']=='VD' else 'runner')]
                if len(containers)!=1 or not instant_ns(containers[0]['startedAt'])<=instant_ns(cp['created_at'])<=instant_ns(grant['granted_at'])<instant_ns(containers[0]['finishedAt'])+10**9:
                    raise Blocked('Sealed STREAM barrier is outside the original producer lifetime')
                objects[cp['id']]=checkpoint_object(store,manifest,cp)
                verify_execution(cp,contracts[task_id],connected)
                sealed[task_id]=cp;times.add(instant_ns(grant['granted_at']));grant_rows.append(grant)
                if task_id in selected:
                    entry=selected[task_id]
                    inherited_grant(tasks[task_id],attempts[entry['attemptId']],grant,attempts,runtimes,links,run)
                    if instant_ns(entry['committedAt'])<instant_ns(grant['granted_at']):raise Blocked('Result predates its original STREAM completion grant')
            chosen={}
            for route in connected:
                consumer=sealed[route['consumer_task_id']]
                candidates=[g for g in history if g['route_id']==route['id'] and g['id'] in consumer['generation_ids']]
                if len(candidates)!=1:raise Blocked('Sealed consumer checkpoint must select one original route generation')
                g=candidates[0];chosen[route['id']]=g
                if g['consumer_attempt_id']!=consumer['attempt_id'] or g['consumer_epoch']!=consumer['epoch']:
                    raise Blocked('Sealed consumer differs from its original route actor')
                if route['source_device_id'] is not None:
                    producer={'kind':'DEVICE_SESSION','sessionId':g['producer_session_id'],'epoch':g['producer_epoch'],'deviceId':route['source_device_id']}
                    done=[c for c in state['deviceCompletions'] if c['generation_id']==g['id']]
                    if len(done)!=1 or done[0]['granted_at'] is None or instant_ns(done[0]['granted_at']) not in times:
                        raise Blocked('Device END requires the same original group grant')
                    emitted=done[0]['sequence']
                else:
                    source=sealed[route['source_task_id']]
                    if g['producer_attempt_id']!=source['attempt_id'] or g['producer_epoch']!=source['epoch']:
                        raise Blocked('Sealed producer differs from its original route actor')
                    producer={'kind':'TASK_ATTEMPT','attemptId':source['attempt_id'],'epoch':source['epoch']}
                    emitted=cursor(source,route['id'])
                if emitted!=cursor(consumer,route['id']):raise Blocked('STREAM terminal producer and consumer cursors disagree')
                expected={'routeId':route['id'],'generation':g['generation'],'producer':producer}
                for task_id,side in [(route['consumer_task_id'],'inputs')]+([] if route['source_task_id'] is None else [(route['source_task_id'],'outputs')]):
                    actual=[b for b in sealed[task_id]['summary_json']['manifest'][side] if b['routeId']==route['id']]
                    if actual!=[expected]:raise Blocked('Sealed checkpoint binding differs from the original route generation')
            if len(times)!=1:raise Blocked('STREAM group was not granted atomically')
            for task_id,cp in sealed.items():
                task_routes=[r for r in connected if task_id in (r['source_task_id'],r['consumer_task_id'])]
                if (set(cp['generation_ids'])!={chosen[r['id']]['id'] for r in task_routes} or
                        {r['routeId'] for r in cp['summary_json']['routes']}!={r['id'] for r in task_routes}):
                    raise Blocked('Sealed checkpoint omits or adds a group route')
            barriers.append({'runId':run_id,'taskIds':part,'grants':sorted(grant_rows,key=lambda c:c['attempt_id'])})
    if private_json(args.runtime_start_backup/'manifest.json')[1]!=manifest_hash or store.identity()!=manifest['targetDeploymentId']:
        raise Blocked('STREAM backup changed during observation')
    store.versioned(args.runtime_start_bucket)
    return {'broker':authority['broker'],'barriers':barriers,'checkpoints':sorted(objects.values(),key=lambda c:c['checkpointId']),
            'readyGroups':sorted(groups,key=lambda g:(g['runId'],g['taskIds']))}


def cursor(cp,route):
    rows=[r for r in cp['summary_json']['routes'] if r['routeId']==route]
    if len(rows)!=1:raise Blocked('Sealed checkpoint lacks its terminal route cursor')
    return rows[0]['committed']


def verify_execution(cp,contract,routes):
    from edgeai_runner.stream_processor import execution_digest
    work=json.loads(contract['workJson']);spec=work['spec'].get('stream')
    if not isinstance(spec,dict):raise Blocked('Sealed checkpoint requires its original STREAM SERVICE')
    incoming={r['consumer_port']:r['id'] for r in routes if r['consumer_task_id']==cp['task_id']}
    outgoing={}
    for r in routes:
        if r['source_task_id']==cp['task_id']:outgoing.setdefault(r['source_port'],[]).append(r['id'])
    for values in outgoing.values():values.sort()
    limits={a:spec['limits'][b] for a,b in [('max_frames','maxFrames'),('max_buffer_bytes','maxBufferBytes'),('max_state_bytes','maxStateBytes')]}
    expected=execution_digest(spec['command']+spec['args'],work['parameters'],incoming,outgoing,spec['stepTimeoutSeconds'])
    if (set(incoming)!=set(spec['inputs']) or set(outgoing)!=set(spec['outputs']) or
            cp['execution_sha256']!=expected or cp['summary_json']['manifest']['limits']!=limits):
        raise Blocked('Sealed checkpoint execution or limits differ from its immutable work contract')
