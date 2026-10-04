"""Verify every restored database artifact/checkpoint reference against an independent storage backup."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import uuid

from postgres_backup import Blocked, Postgres, ROOT, identifier, private_file, read_private
from storage_backup import Client, validate_manifest


# A migration that adds another durable S3 reference must extend this inventory before acceptance.
SUPPORTED_VERSIONS = {str(version) for version in range(1, 35)}
# V34 adds only HTTP audit rows; V33 and V34 have the same complete S3/runtime reference inventory.
def supported_schema(migrations):
    versions={row['version'] for row in migrations}
    return (any(versions=={str(v) for v in range(1,last+1)} and len(migrations)==last for last in (33,34))
            and all(row['success'] is True for row in migrations))
INVENTORY_SQL = """
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT json_build_object(
 'database', current_database(),
 'databaseOid', (SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'restoreIdentity', (SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()),
 'readOnly', current_setting('transaction_read_only'),
 'snapshot', pg_current_snapshot()::text,
 'migrations', (SELECT json_agg(json_build_object('version',version,'success',success))
                FROM edgeai.flyway_schema_history WHERE version IS NOT NULL),
 'references', (SELECT coalesce(json_agg(reference ORDER BY kind,id),'[]'::json) FROM (
   SELECT 'result_artifact' AS kind, id, json_build_object(
     'kind','result_artifact','id',id,'bucket',bucket,'key',object_key,
     'versionId',object_version,'bytes',bytes,'sha256',sha256) AS reference
   FROM edgeai.result_artifact
   UNION ALL
   SELECT 'stream_checkpoint' AS kind, id, json_build_object(
     'kind','stream_checkpoint','id',id,'bucket',bucket,'key',object_key,
     'versionId',object_version,'bytes',bytes,'sha256',sha256) AS reference
   FROM edgeai.stream_checkpoint
 ) AS all_references)
);
COMMIT;
"""


def read_json(path):
    with read_private(path) as source:
        raw = source.read()
    return json.loads(raw), hashlib.sha256(raw).hexdigest()


def verify(pg, client, database, restore_report_path, storage_bundle):
    identifier(database)
    if not database.startswith('edgeai_restore_') or database == 'edgeai_restore_':
        raise ValueError('Reference verification requires a separately restored edgeai_restore_* database')
    restored, restore_digest = read_json(restore_report_path)
    if (restored.get('scope') != 'postgresql-database-only' or restored.get('status') != 'RESTORED_DB_ONLY'
            or restored.get('activated') is not False or restored.get('created') is not True
            or restored.get('targetDatabase') != database
            or not re.fullmatch('[a-f0-9]{32}', restored.get('restoreIdentity',''))
            or not re.fullmatch('[a-f0-9]{64}', restored.get('archiveSha256',''))):
        raise ValueError('A successful inactive database restore report for this database is required')
    manifest, manifest_digest = read_json(Path(storage_bundle)/'manifest.json')
    validate_manifest(manifest)
    if client.deployment('replica') != manifest['targetDeploymentId']:
        raise ValueError('Storage backup deployment identity differs')
    inventory = json.loads(pg.call('psql', ['-X','-q','-A','-t','-v','ON_ERROR_STOP=1','-c',INVENTORY_SQL], database,
        timeout=60, reject_stderr=True))
    if (inventory['database'] != database or inventory['databaseOid'] != str(restored.get('databaseOid'))
            or inventory['restoreIdentity'] != 'edgeai-restore:'+restored['restoreIdentity']
            or inventory['readOnly'] != 'on'):
        raise ValueError('Restored database identity or read-only snapshot differs')
    migrations = inventory['migrations'] or []
    if not supported_schema(migrations):
        raise Blocked('This verifier inventories the complete V33/V34 reference schema; review other migration versions first')
    references = inventory['references']
    versions = {(item['bucket'],item['key'],item['versionId']):item for item in manifest['versions']}
    required = {}
    counts = {'result_artifact':0,'stream_checkpoint':0}
    for reference in references:
        counts[reference['kind']] += 1
        identity = reference['bucket'],reference['key'],reference['versionId']
        actual = versions.get(identity)
        if actual is None:
            raise ValueError('A restored '+reference['kind']+' version is absent from the backup manifest')
        if actual['bytes'] != reference['bytes'] or actual['sha256'] != reference['sha256']:
            raise ValueError('A restored '+reference['kind']+' content proof differs from the backup manifest')
        required[identity] = actual
    # No latest-version fallback: every distinct referenced version is read from the backup itself.
    for item in required.values():
        if client.digest('replica',item) != item['sha256']:
            raise ValueError('A restored reference has different bytes in backup storage')
    encoded = json.dumps(references,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
    return {
        'formatVersion':1, 'scope':'restored-database-storage-references',
        'status':'VERIFIED_DB_STORAGE_REFERENCES', 'activated':False,
        'targetDatabase':database, 'databaseOid':inventory['databaseOid'],
        'databaseSnapshot':inventory['snapshot'], 'schemaVersions':sorted(SUPPORTED_VERSIONS,key=int),
        'databaseArchiveSha256':restored['archiveSha256'], 'restoreReportSha256':restore_digest,
        'storageManifestSha256':manifest_digest, 'targetDeploymentId':manifest['targetDeploymentId'],
        'referenceCounts':counts, 'referenceSha256':hashlib.sha256(encoded).hexdigest(),
        'verifiedVersionCount':len(required), 'verifiedBytes':sum(item['bytes'] for item in required.values()),
        'verifiedAt':datetime.now(timezone.utc).isoformat(),
        'excluded':['live-runtime-reconciliation','service-activation','IAM-and-TLS-identities',
                    'broker-and-device-journals','post-snapshot-database-changes']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--transport',choices=['native','compose'],default='native')
    p.add_argument('--pg-bin',type=Path)
    p.add_argument('--database',required=True)
    p.add_argument('--restore-report',type=Path,required=True)
    p.add_argument('--storage-input',type=Path,required=True)
    p.add_argument('--output',type=Path)
    a = p.parse_args()
    output = a.output or ROOT/'.tools'/('recovery-references-'+uuid.uuid4().hex)
    created = False
    try:
        output = output.absolute()
        output.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
        output.mkdir(mode=0o700,exist_ok=False); created = True
        pg = Postgres(a.transport,a.pg_bin,diagnostics=output/'postgres')
        client = Client(output,source=False)
        report = verify(pg,client,a.database,a.restore_report,a.storage_input)
        with private_file(output/'verification-report.json','w') as destination:
            json.dump(report,destination,indent=2);destination.write('\n')
        print(report['status']+': '+str(sum(report['referenceCounts'].values()))+' references / '+
            str(report['verifiedVersionCount'])+' fixed versions; application and workers were not activated')
        return 0
    except Exception as error:
        status = 'BLOCKED' if isinstance(error,Blocked) else 'FAIL'
        if created:
            with private_file(output/'failure.json','w') as destination:
                json.dump({'status':status,'failureType':type(error).__name__,'message':str(error)},destination)
        print(status+': database/storage reference verification ('+type(error).__name__+'); private diagnostics retained')
        return 2 if status == 'BLOCKED' else 1
    finally:
        if created: print('Private diagnostics: '+str(output))


if __name__ == '__main__':
    raise SystemExit(main())
