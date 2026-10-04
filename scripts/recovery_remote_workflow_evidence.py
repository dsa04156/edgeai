"""Fresh reference-provider authority for mixed Kubernetes/Remote workflow recovery."""
from pathlib import Path
from types import SimpleNamespace

from postgres_backup import Blocked
from recovery_remote_inventory import compare
from recovery_remote_storage import private_json
from recovery_remote_start_receipts import observe as observe_starts


def observe(catalog, args):
    path=getattr(args,'remote_connection',None)
    use_starts=getattr(args,'remote_start_receipts',False)
    if use_starts and (path is None or not getattr(args,'offloads',False)):
        raise ValueError('--remote-start-receipts requires --offloads and --remote-connection')
    if path is None:return None,{}
    if not getattr(args,'offloads',False):raise ValueError('Remote workflow evidence requires --offloads')
    value,digest=private_json(path)
    fields={'endpoint','caFile','certificateSha256','providerId','providerKey','recoveryTokenFile'}
    if not isinstance(value,dict) or set(value)!=fields or any(not isinstance(v,str) or not v for v in value.values()):
        raise ValueError('An explicit private Remote connection with all six fields is required')
    for key in ('caFile','recoveryTokenFile'):
        if not Path(value[key]).is_absolute():raise ValueError('Remote authority paths must be absolute')
    connection=SimpleNamespace(database=args.database,endpoint=value['endpoint'],ca_file=Path(value['caFile']),
        certificate_sha256=value['certificateSha256'],provider_id=value['providerId'],provider_key=value['providerKey'],
        recovery_token_file=Path(value['recoveryTokenFile']),recovery_id=args.recovery_id,timeout=min(300,max(10,args.timeout)),page_size=100)
    snapshot={**catalog['remoteSnapshot'],'restoreReportSha256':catalog['restoreReportSha256']}
    inventory=compare(snapshot,connection)
    if inventory['status']!='OBSERVED_REMOTE_INVENTORY':
        raise Blocked('Complete Remote allocation and frozen binding inventory must match')
    starts=observe_starts(inventory,connection) if use_starts else None
    contexts={c['allocationId']:c for c in catalog['remoteContexts']}
    outcomes={}
    for item in inventory['allocations']:
        row,observed=item['database'],item['provider']
        context=contexts.get(item['allocationId'])
        if (context is None or row['observation']!={k:v for k,v in observed.items() if k!='executions'} or
                row['provider_revision']!=observed['revision'] or row['provider_state']!=observed['state'] or
                context['desiredState']!='STOPPED' or context['runtimeState']!='TERMINATED' or context['pendingCommands'] or
                context['attempt']['mode']!='REMOTE' or any(context['attempt']['remote_'+key]!=row[key]
                    for key in ('provider_key','configuration_digest','source_mode'))):
            raise Blocked('Retire every Remote runtime against its exact provider observation before workflow reconciliation')
        if context['namespace']==args.namespace:
            outcomes[context['runtimeId']]={'state':observed['state'],'failureReason':observed['failureReason']}
            if starts is not None:outcomes[context['runtimeId']]['startReceipt']=starts[item['allocationId']]
    if private_json(path)[1]!=digest:raise Blocked('Remote connection changed during observation')
    evidence={'connectionSha256':digest,'providerId':connection.provider_id,'recoveryId':args.recovery_id,
        'providerInventorySha256':inventory['providerInventorySha256'],'binding':inventory['selectedTarget'],
        'providerStatus':inventory['providerStatus'],'provenRuntimes':sorted(outcomes)}
    if starts is not None:evidence['startReceipts']=starts
    return evidence,outcomes
