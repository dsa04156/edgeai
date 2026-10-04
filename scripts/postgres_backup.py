"""Private PostgreSQL snapshots and restoration into a new, inactive database only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]


class Blocked(Exception):
    pass


def identifier(value):
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', value):
        raise ValueError('Database name must use lowercase letters, digits and underscores, at most 63 characters')
    return '"'+value+'"'


def literal(value):
    if not isinstance(value, str) or '\x00' in value:
        raise ValueError('Invalid database locale metadata')
    return "'"+value.replace("'", "''")+"'"


def private_file(path, mode='wb'):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    return os.fdopen(descriptor, mode)


def read_private(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    info = os.fstat(descriptor)
    if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
        os.close(descriptor)
        raise ValueError('Backup inputs must be regular files with owner-only permissions')
    return os.fdopen(descriptor, 'rb')


class Postgres:
    def __init__(self, transport='native', pg_bin=None, diagnostics=None):
        self.env = os.environ.copy()
        if not all(self.env.get(k) for k in ['EDGEAI_DB_USER', 'EDGEAI_DB_PASSWORD', 'EDGEAI_DB_PORT']):
            raise Blocked('Load the project database environment first')
        self.env.update(PGPASSWORD=self.env['EDGEAI_DB_PASSWORD'], PGCONNECT_TIMEOUT='5',
                        PGAPPNAME='edgeai-postgres-backup', PGOPTIONS='-c timezone=UTC -c standard_conforming_strings=on')
        self.transport = transport
        if transport == 'compose':
            if pg_bin is not None: raise ValueError('pg-bin applies only to native transport')
            if not shutil.which('docker'): raise Blocked('Docker is required for Compose transport')
            self.prefix = ['docker', 'compose', '--project-name', 'edgeai-dev', '--env-file', str(ROOT/'.env'),
                '-f', str(ROOT/'deploy/compose/compose.yaml'), 'exec', '-T', '-e', 'PGPASSWORD',
                '-e', 'PGCONNECT_TIMEOUT', '-e', 'PGAPPNAME', '-e', 'PGOPTIONS', 'postgres']
            self.binaries = {tool: tool for tool in ['psql', 'pg_dump', 'pg_restore']}
            host, port = '127.0.0.1', '5432'
        else:
            local = ROOT/'.tools/postgres/usr/lib/postgresql/16/bin'
            directory = Path(pg_bin) if pg_bin is not None else local if local.exists() else None
            self.binaries = {tool: str(directory/tool) if directory else shutil.which(tool)
                             for tool in ['psql', 'pg_dump', 'pg_restore']}
            if not all(path and os.access(path, os.X_OK) for path in self.binaries.values()):
                raise Blocked('Matching psql, pg_dump and pg_restore are required; select pg-bin or Compose')
            self.prefix = []
            self.env['LD_LIBRARY_PATH'] = str(ROOT/'.tools/postgres/usr/lib/x86_64-linux-gnu')+':'+self.env.get('LD_LIBRARY_PATH', '')
            host, port = self.env.get('EDGEAI_DB_HOST', '127.0.0.1'), self.env['EDGEAI_DB_PORT']
        self.connection = ['-h', host, '-p', port, '-U', self.env['EDGEAI_DB_USER'], '--no-password']
        self.directory = diagnostics or ROOT/'.tools'/('postgres-backup-'+uuid.uuid4().hex)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.log = self.directory/'postgres.log'
        with private_file(self.log): pass

    def call(self, tool, arguments, database=None, source=None, destination=None, timeout=300, reject_stderr=False):
        command = self.prefix+[self.binaries[tool]]
        if database is not None:
            identifier(database)
            command += self.connection+['--dbname', database]
        with self.log.open('ab') as log:
            previous_size = log.tell()
            result = subprocess.run(command+arguments, env=self.env, stdin=source,
                stdout=destination if destination is not None else subprocess.PIPE,
                stderr=log, timeout=timeout)
            diagnostic_bytes = log.tell() - previous_size
        if result.returncode:
            raise RuntimeError(tool+' failed; private diagnostics retained')
        if reject_stderr and diagnostic_bytes:
            raise RuntimeError(tool+' emitted diagnostics; review the private log before accepting a backup')
        return result.stdout

    def sql(self, text, database):
        return self.call('psql', ['-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', text], database, timeout=30).decode().strip()

    def check_versions(self, database):
        server = int(self.sql('SHOW server_version_num', database))
        major = server//10000
        versions = {}
        for tool in ['pg_dump', 'pg_restore']:
            output = self.call(tool, ['--version'], timeout=15).decode()
            match = re.search(r'PostgreSQL\) (\d+)', output)
            if not match or int(match.group(1)) != major:
                raise Blocked('Use dump/restore clients with the same major version as the database server')
            versions[tool] = output.strip()
        if major < 16: raise Blocked('PostgreSQL 16 or newer is required')
        return server, versions


def backup(pg, database, output):
    identifier(database)
    server, clients = pg.check_versions(database)
    if pg.sql("SELECT to_regclass('edgeai.flyway_schema_history') IS NOT NULL", database) != 't':
        raise Blocked('Source database does not contain the EdgeAI migration history')
    metadata = json.loads(pg.sql("SELECT json_build_object('encoding',pg_encoding_to_char(encoding),"
        "'collate',datcollate,'ctype',datctype,'provider',datlocprovider) FROM pg_database WHERE datname=current_database()", database))
    if metadata['provider'] != 'c': raise Blocked('Only libc-locale database snapshots are supported by this restore contract')
    output = Path(output).absolute()
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    output.mkdir(mode=0o700, exist_ok=False)
    archive = output/'database.dump'
    started = datetime.now(timezone.utc).isoformat()
    with private_file(archive) as destination:
        pg.call('pg_dump', ['--format=custom', '--no-privileges', '--lock-wait-timeout=5s'], database,
                destination=destination, reject_stderr=True)
        destination.flush(); os.fsync(destination.fileno())
    with read_private(archive) as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
        source.seek(0)
        pg.call('pg_restore', ['--list'], source=source)
    manifest = {'formatVersion': 1, 'scope': 'postgresql-database-only', 'sourceDatabase': database,
        'sourceServerVersion': server, 'clients': clients, 'databaseLocale': metadata,
        'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
        'archiveBytes': archive.stat().st_size, 'archiveSha256': digest,
        'excluded': ['object-storage-bytes-and-versions', 'cluster-roles-and-acls', 'external-secrets-and-identities',
                     'broker-and-device-journals', 'live-runtime-reconciliation']}
    with private_file(output/'manifest.json', 'w') as f:
        json.dump(manifest, f, indent=2); f.write('\n'); f.flush(); os.fsync(f.fileno())
    return manifest


def restore(pg, bundle, target):
    identifier(target)
    if not target.startswith('edgeai_restore_') or len(target) <= len('edgeai_restore_'):
        raise ValueError('Restore target must be a new edgeai_restore_* database')
    bundle = Path(bundle)
    with read_private(bundle/'manifest.json') as f:
        manifest = json.load(f)
    if manifest.get('formatVersion') != 1 or manifest.get('scope') != 'postgresql-database-only':
        raise ValueError('Unsupported backup manifest')
    created, owned_oid = False, None
    report = {'scope': 'postgresql-database-only', 'status': 'RUNNING', 'targetDatabase': target,
              'created': False, 'failedDatabaseRemoved': False, 'activated': False}
    try:
        with read_private(bundle/'database.dump') as source:
            if (os.fstat(source.fileno()).st_size != manifest['archiveBytes'] or
                    hashlib.file_digest(source, 'sha256').hexdigest() != manifest['archiveSha256']):
                raise ValueError('Archive size/checksum mismatch')
            server, _ = pg.check_versions('postgres')
            if server//10000 != manifest['sourceServerVersion']//10000:
                raise Blocked('Cross-major database restoration is outside this contract')
            source.seek(0)
            pg.call('pg_restore', ['--list'], source=source)
            if pg.sql('SELECT EXISTS(SELECT FROM pg_database WHERE datname='+literal(target)+')', 'postgres') != 'f':
                raise ValueError('Existing database refused; restore never overwrites a database')
            locale = manifest['databaseLocale']
            if locale['provider'] != 'c': raise ValueError('Unsupported locale provider')
            pg.sql('CREATE DATABASE '+identifier(target)+' WITH TEMPLATE template0 LOCALE_PROVIDER libc ENCODING '+
                literal(locale['encoding'])+' LC_COLLATE '+literal(locale['collate'])+' LC_CTYPE '+literal(locale['ctype']), 'postgres')
            created = report['created'] = True
            owned_oid = pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(target), 'postgres')
            report['databaseOid'] = owned_oid
            source.seek(0)
            pg.call('pg_restore', ['--single-transaction', '--exit-on-error', '--no-owner', '--no-privileges'],
                    target, source=source)
            history = json.loads(pg.sql("SELECT json_build_object('total',count(*),'failed',count(*) FILTER(WHERE NOT success)) "
                'FROM edgeai.flyway_schema_history', target))
            if history['total'] < 1 or history['failed'] != 0: raise ValueError('Restored migration history is incomplete')
            report.update(status='RESTORED_DB_ONLY', migrationCount=history['total'], archiveSha256=manifest['archiveSha256'])
    except Exception as error:
        report.update(status='FAIL', failureType=type(error).__name__)
        if created:
            try:
                if owned_oid is None or pg.sql('SELECT oid FROM pg_database WHERE datname='+literal(target), 'postgres') != owned_oid:
                    raise RuntimeError('Restore database identity changed')
                pg.sql('DROP DATABASE '+identifier(target), 'postgres')
                report['failedDatabaseRemoved'] = True
            except Exception as cleanup:
                report['cleanupFailureType'] = type(cleanup).__name__
        raise
    finally:
        with private_file(pg.directory/'restore-report.json', 'w') as f:
            json.dump(report, f, indent=2); f.write('\n')
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['backup', 'restore'])
    p.add_argument('--transport', choices=['native', 'compose'], default='native')
    p.add_argument('--pg-bin', type=Path)
    p.add_argument('--database', default=os.environ.get('EDGEAI_DB_NAME', 'edgeai'))
    p.add_argument('--output', type=Path)
    p.add_argument('--input', type=Path)
    p.add_argument('--target-database')
    a = p.parse_args()
    if a.action == 'backup' and a.output is None: p.error('backup requires --output <new-directory>')
    if a.action == 'restore' and (a.input is None or a.target_database is None):
        p.error('restore requires --input <bundle-directory> --target-database edgeai_restore_<name>')
    pg = None
    try:
        pg = Postgres(a.transport, a.pg_bin)
        if a.action == 'backup':
            value = backup(pg, a.database, a.output)
            print('BACKED_UP_DB_ONLY: archive bytes='+str(value['archiveBytes'])+' sha256='+value['archiveSha256'])
        else:
            value = restore(pg, a.input, a.target_database)
            print('RESTORED_DB_ONLY: '+value['targetDatabase']+'; application and workers were not activated')
        return 0
    except Blocked as error:
        print('BLOCKED: '+str(error)); return 2
    except Exception as error:
        print('FAIL: PostgreSQL '+a.action+' ('+type(error).__name__+'); private diagnostics retained'); return 1
    finally:
        if pg: print('Private diagnostics: '+str(pg.directory))


if __name__ == '__main__':
    raise SystemExit(main())
