"""Read-only comparison of a restored DB with one fenced reference Remote installation."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import http.client
import json
from pathlib import Path
import re
from urllib.parse import urlencode
import uuid

from postgres_backup import Blocked, Postgres, private_file
from recovery_kubernetes import database_inventory
from recovery_remote_fence import Client, token

TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}
DATABASE_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'database',current_database(),
 'oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'marker',(SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),
 'readOnly',current_setting('transaction_read_only'),'snapshot',pg_current_snapshot()::text,
 'migrations',(SELECT json_agg(json_build_object('version',version,'success',success))
   FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),
 'allocations',(SELECT coalesce(json_agg(to_jsonb(a) ORDER BY id),'[]'::json) FROM
   (SELECT a.id,a.provider_key,a.configuration_digest,a.source_mode,a.request_digest,
     a.provider_revision,a.provider_state,a.observation,a.work->'identity' AS work_identity,
     a.work->>'expiresAt' AS expires_at,r.id AS runtime_id,r.run_id,r.task_id,r.attempt_id,r.epoch,
     r.observed_state AS runtime_state,t.id AS result_id
    FROM edgeai.remote_allocation a JOIN edgeai.runtime_instance r ON r.id=a.runtime_id
    LEFT JOIN edgeai.task_result t ON t.remote_allocation_id=a.id AND t.committed) a),
 'targets',(SELECT coalesce(json_agg(to_jsonb(t)),'[]'::json) FROM
   (SELECT remote_provider_key AS provider_key,remote_configuration_digest AS configuration_digest,
     remote_source_mode AS source_mode,count(*) AS attempts
    FROM edgeai.task_attempt WHERE mode='REMOTE'
    GROUP BY remote_provider_key,remote_configuration_digest,remote_source_mode) t)
);
COMMIT;
"""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def binding_digest(args):
    # Exactly RemoteProvider/JsonDocuments' frozen trust/origin/protocol binding, never bearer bytes.
    value = {'origin': args.endpoint, 'protocol': 'edgeai.remote.reference/v1',
             'trust': hashlib.sha256(args.ca_file.read_bytes()).hexdigest(), 'sourceMode': 'SYNTHETIC'}
    return 'sha256:' + hashlib.sha256(b'edgeai-remote-provider-v1\n' + canonical(value)).hexdigest()


def uid(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise Blocked('Invalid Remote identity')


def validate_item(value):
    fields = {'identity', 'requestDigest', 'state', 'revision', 'failureReason', 'expiresAt', 'sourceMode', 'outputs', 'executions'}
    if not isinstance(value, dict) or set(value) != fields or value['state'] not in TERMINAL or value['sourceMode'] != 'SYNTHETIC':
        raise Blocked('Unsupported Remote inventory entry')
    identity = value['identity']
    if not isinstance(identity, dict) or set(identity) != {'allocationId', 'runId', 'taskId', 'attemptId', 'epoch'}:
        raise Blocked('Invalid Remote allocation identity')
    for key in ('allocationId', 'runId', 'taskId', 'attemptId'):
        uid(identity[key])
    for number in (identity['epoch'], value['revision']):
        if type(number) is not int or not 1 <= number <= 9007199254740991:
            raise Blocked('Invalid Remote revision')
    if type(value['executions']) is not int or not 0 <= value['executions'] <= 1:
        raise Blocked('Unexpected reference execution count')
    if value['requestDigest'] is None:
        if value['expiresAt'] is not None or value['state'] != 'CANCELLED' or value['executions'] != 0 or value['revision'] != 1:
            raise Blocked('Invalid Remote tombstone')
    else:
        if not isinstance(value['requestDigest'], str) or not re.fullmatch('sha256:[a-f0-9]{64}', value['requestDigest']):
            raise Blocked('Invalid Remote request digest')
        expiry = datetime.fromisoformat(value['expiresAt'].replace('Z', '+00:00'))
        if expiry.tzinfo is None or expiry.utcoffset().total_seconds() != 0:
            raise Blocked('Invalid Remote deadline')
    failure = value['failureReason']
    if (value['state'] == 'FAILED') != (failure is not None) or (failure is not None and failure not in
            {'LEASE_EXPIRED', 'WORKLOAD_FAILED', 'PROVIDER_RESTART', 'INPUT_INVALID', 'OUTPUT_INVALID'}):
        raise Blocked('Invalid Remote terminal outcome')
    outputs = value['outputs']
    if not isinstance(outputs, list) or len(outputs) > 16 or (value['state'] == 'SUCCEEDED') != bool(outputs):
        raise Blocked('Invalid Remote output inventory')
    ports = set()
    for output in outputs:
        if (not isinstance(output, dict) or set(output) != {'port', 'bytes', 'sha256', 'mediaType'} or
                not isinstance(output['port'], str) or not re.fullmatch('[a-z][a-z0-9]*([._-][a-z0-9]+)*', output['port']) or
                output['port'] in ports or type(output['bytes']) is not int or not 0 <= output['bytes'] <= 1048576 or
                not isinstance(output['sha256'], str) or not re.fullmatch('[a-f0-9]{64}', output['sha256']) or
                output['mediaType'] != 'application/json'):
            raise Blocked('Invalid Remote output metadata')
        ports.add(output['port'])


def provider_inventory(client, credential, args):
    before = client.status(credential)
    if not before['quiescent'] or before['recoveryId'] != args.recovery_id:
        raise Blocked('Provider must already be fenced and quiescent for this recovery')
    after, items = '', []
    for _ in range(1000):
        path = '/reference/v1/recovery/allocations?' + urlencode({
            'providerId': args.provider_id, 'recoveryId': args.recovery_id, 'after': after, 'limit': args.page_size})
        code, page = client.request(credential, path=path, max_bytes=262144)
        if (code != 200 or not isinstance(page, dict) or set(page) !=
                {'apiVersion', 'providerId', 'recoveryId', 'allocationCount', 'after', 'nextAfter', 'items'} or
                page['apiVersion'] != before['apiVersion'] or page['providerId'] != before['providerId'] or
                page['recoveryId'] != before['recoveryId'] or type(page['allocationCount']) is not int or
                page['allocationCount'] != before['allocationCount'] or page['after'] != after or
                not isinstance(page['items'], list) or len(page['items']) > args.page_size):
            raise Blocked('Remote inventory page identity or count differs')
        cursor = after
        for item in page['items']:
            validate_item(item)
            current = item['identity']['allocationId']
            if current <= cursor:
                raise Blocked('Repeated or unordered Remote allocation')
            cursor = current
            items.append(item)
        continuation = page['nextAfter']
        if continuation is None:
            break
        if not page['items'] or continuation != cursor or len(page['items']) != args.page_size:
            raise Blocked('Invalid Remote continuation')
        after = continuation
    else:
        raise Blocked('Remote inventory page limit exceeded')
    if len(items) != before['allocationCount'] or dict(Counter(i['state'] for i in items)) != {k:v for k,v in before['states'].items() if v}:
        raise Blocked('Remote inventory is incomplete')
    if client.status(credential) != before:
        raise Blocked('Remote inventory changed while paging')
    return before, items


def classify(expected, actual):
    if expected is None:
        return 'ABSENT_FROM_RESTORED_DATABASE'
    if actual is None:
        return 'ABSENT_FROM_PROVIDER'
    identity = {'allocationId': expected['id'], 'runId': expected['run_id'], 'taskId': expected['task_id'],
                'attemptId': expected['attempt_id'], 'epoch': expected['epoch']}
    if actual['identity'] != identity or expected['work_identity'] != identity:
        return 'IDENTITY_CONFLICT'
    if actual['requestDigest'] is None:
        return 'CANCELLED_BEFORE_RESERVATION' if expected['provider_revision'] == 0 and expected['result_id'] is None else 'OBSERVATION_CONFLICT'
    if actual['requestDigest'] != expected['request_digest'] or actual['expiresAt'] != expected['expires_at']:
        return 'REQUEST_CONFLICT'
    observation = {k:v for k,v in actual.items() if k != 'executions'}
    if (actual['revision'] < expected['provider_revision'] or
            ((expected['provider_state'] in TERMINAL or actual['revision'] == expected['provider_revision']) and
             observation != expected['observation']) or
            (expected['result_id'] is not None and actual['state'] != 'SUCCEEDED')):
        return 'OBSERVATION_CONFLICT'
    return 'MATCHED_TERMINAL'


def inspect(pg, args):
    uid(args.provider_id); uid(args.recovery_id)
    if not re.fullmatch('[a-z][a-z0-9]*(-[a-z0-9]+)*', args.provider_key) or len(args.provider_key) > 63 or not 1 <= args.page_size <= 100:
        raise ValueError('Explicit provider key and page size 1..100 required')
    catalog = database_inventory(pg, args.database, args.restore_report, DATABASE_SQL)
    target = {'provider_key': args.provider_key, 'configuration_digest': binding_digest(args), 'source_mode': 'SYNTHETIC'}
    selected = lambda row: all(row[key] == value for key, value in target.items())
    expected = {row['id']: row for row in catalog['allocations'] if selected(row)}
    all_database = {row['id']: row for row in catalog['allocations']}
    client = Client(args)
    credential = token(args.recovery_token_file)
    status, items = provider_inventory(client, credential, args)
    actual = {row['identity']['allocationId']: row for row in items}
    classified = []
    for identity in sorted(set(expected) | set(actual)):
        stored, remote = all_database.get(identity), actual.get(identity)
        classification = 'BINDING_CONFLICT' if stored is not None and not selected(stored) else classify(stored, remote)
        classified.append({'allocationId': identity, 'classification': classification,
                           'database': stored, 'provider': remote})
    outside = [row for row in catalog['targets'] if not selected(row)]
    counts = dict(Counter(row['classification'] for row in classified))
    attention = any(key not in {'MATCHED_TERMINAL', 'CANCELLED_BEFORE_RESERVATION'} for key in counts) or bool(outside)
    return {'formatVersion': 1, 'scope': 'restored-database-reference-remote-inventory',
            'status': 'REVIEW_REQUIRED' if attention else 'OBSERVED_REMOTE_INVENTORY',
            'activated': False, 'databaseModified': False, 'globalQuiescenceProven': False,
            'targetDatabase': args.database, 'databaseOid': catalog['oid'], 'databaseSnapshot': catalog['snapshot'],
            'restoreReportSha256': catalog['restoreReportSha256'], 'selectedTarget': target,
            'providerStatus': status, 'providerInventorySha256': hashlib.sha256(canonical(items)).hexdigest(),
            'counts': counts, 'otherTargets': outside, 'allocations': classified,
            'verifiedAt': datetime.now(timezone.utc).isoformat(),
            'excluded': ['provider-output-bytes', 'database-reconciliation-writes', 'service-activation',
                         'external-provider-contract', 'other-provider-installations', 'device-journals',
                         'post-snapshot-database-changes']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('database', 'endpoint', 'certificate-sha256', 'provider-id', 'recovery-id', 'provider-key'):
        parser.add_argument('--' + name, required=True)
    for name in ('restore-report', 'ca-file', 'recovery-token-file', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--transport', choices=['native', 'compose'], default='native')
    parser.add_argument('--pg-bin', type=Path)
    parser.add_argument('--timeout', type=int, default=120)
    parser.add_argument('--page-size', type=int, default=100)
    args = parser.parse_args()
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    try:
        pg = Postgres(args.transport, args.pg_bin, diagnostics=args.output / 'postgres')
        report = inspect(pg, args)
        with private_file(args.output / 'inventory.json', 'w') as out:
            json.dump(report, out, indent=2); out.write('\n')
        print(report['status'] + ': restored DB and reference Remote inventory; private report retained')
        return 2 if report['status'] == 'REVIEW_REQUIRED' else 0
    except Exception as error:
        blocked = isinstance(error, (Blocked, OSError, http.client.HTTPException))
        with private_file(args.output / 'failure.json', 'w') as out:
            json.dump({'status': 'BLOCKED' if blocked else 'FAIL', 'failureType': type(error).__name__, 'activated': False}, out)
        print('BLOCKED' if blocked else 'FAIL', ': Remote inventory; private diagnostics retained')
        return 2 if blocked else 1


if __name__ == '__main__':
    raise SystemExit(main())
