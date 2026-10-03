"""Authenticated checkpoint control and bounded fixed-version S3 transport.

Control credentials are reread for every request and never sent to storage. All
errors are fixed messages; grants remain in memory. A PUT is not a confirmation.
"""
import base64
import hashlib
import http.client
import json
import math
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

from edgeai_runner.stream_assignment import BindingClient, AssignmentError, _token, instant
from edgeai_runner.stream_checkpoint import Snapshot, MAX_BYTES, MEDIA_TYPE, capture, confirm, encode, state_bytes, restore
from edgeai_runner.stream_journal import JournalError, Limits, manifest
from edgeai_runner.stream_protocol import Binding, Producer, _uuid, _unique_object, _invalid_constant


class CheckpointError(RuntimeError):
    """A permanent checkpoint identity, protocol or authority failure."""


class CheckpointUnavailable(CheckpointError):
    """Retry the same candidate within existing authority; never confirm on this error."""


def require(condition):
    if not condition:
        raise CheckpointError('Invalid stream checkpoint response')


def fields(value, names):
    require(type(value) is dict and set(value) == set(names.split()))
    return value


def counter(value, minimum=0, maximum=9007199254740991):
    require(type(value) is int and minimum <= value <= maximum)
    return value


def text(value, maximum):
    require(type(value) is str and 0 < len(value) <= maximum and all(32 < ord(c) < 127 for c in value))
    return value


def sha(value):
    require(type(value) is str and len(value) == 64 and all(c in '0123456789abcdef' for c in value))
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise CheckpointError('Checkpoint redirect rejected')


class CheckpointClient:
    def __init__(self, binding_client, run_id, generation_ids, *, storage_ca_file=None, allow_http_loopback=False):
        require(isinstance(binding_client, BindingClient) and binding_client.actor.kind == 'TASK_ATTEMPT')
        _uuid(run_id)
        require(type(generation_ids) in (list, tuple) and 1 <= len(generation_ids) <= 32)
        for identity in generation_ids:
            _uuid(identity)
        require(len(set(generation_ids)) == len(generation_ids))
        require(type(allow_http_loopback) is bool)
        self.client, self.run_id = binding_client, run_id
        self.generation_ids = sorted(generation_ids)
        self.allow_http_loopback = allow_http_loopback
        try:
            context = ssl.create_default_context(cafile=storage_ca_file)
        except (ValueError, OSError):
            raise CheckpointError('Invalid checkpoint storage trust configuration') from None
        self.storage = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                   urllib.request.HTTPSHandler(context=context))

    @staticmethod
    def _timeout(timeout):
        require(type(timeout) in (int, float) and math.isfinite(timeout) and .01 <= timeout <= 5)
        return timeout

    def _post(self, operation, payload, timeout):
        timeout = self._timeout(timeout)
        try:
            client = self.client
            body = {'epoch':client.actor.epoch, 'podUid':client.pod_uid, **payload}
            headers = {'Content-Type':'application/json', 'Accept':'application/json',
                       'Authorization':'Bearer '+_token(client.token_file,1024,projected=True),
                       'X-EdgeAI-Pod-Token':_token(client.pod_token_file,16384,projected=True)}
            request = urllib.request.Request(client.url+'/checkpoints/'+operation,
                                             data=encode(body), headers=headers, method='POST')
            with client.http.open(request,timeout=timeout) as response:
                require(response.status in ((200,201) if operation == 'commit' else (200,)))
                require(response.headers.get_content_type() == 'application/json'
                        and 'no-store' in [v.strip().lower() for v in response.headers.get('Cache-Control','').split(',')])
                encoded = response.read(262145)
            require(0 < len(encoded) <= 262144)
            return json.loads(encoded,object_pairs_hook=_unique_object,parse_constant=_invalid_constant)
        except urllib.error.HTTPError as failure:
            status = failure.code; failure.close()
            if status in (429,500,502,503,504):
                raise CheckpointUnavailable('Checkpoint service unavailable') from None
            raise CheckpointError('Checkpoint request rejected or fenced') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise CheckpointUnavailable('Checkpoint service unavailable') from None
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError, AssignmentError):
            raise CheckpointError('Invalid checkpoint control response or credential') from None

    def candidate(self, snapshot, previous_id):
        require(type(snapshot) is Snapshot)
        if previous_id is not None:
            _uuid(previous_id)
        return {'previousCheckpointId':previous_id, 'serial':snapshot.serial, 'sha256':snapshot.sha256,
                'bytes':len(snapshot.wire), 'executionSha256':snapshot.document()['executionSha256'],
                'generationIds':self.generation_ids.copy()}

    def receipt(self, value, snapshot=None, previous_id=None):
        try:
            fields(value, 'id runId taskId attemptId epoch serviceProfileVersionId previousCheckpointId serial revision sha256 bytes executionSha256 generationIds versionId summary createdAt')
            for key in ('id','runId','taskId','attemptId','serviceProfileVersionId'):
                _uuid(value[key])
            if value['previousCheckpointId'] is not None:
                _uuid(value['previousCheckpointId'])
            counter(value['epoch'],1);counter(value['serial']);counter(value['revision'],0,value['serial'])
            counter(value['bytes'],1,MAX_BYTES);sha(value['sha256']);sha(value['executionSha256'])
            text(value['versionId'],1024);require(value['versionId'] != 'null');instant(value['createdAt'])
            require(value['runId'] == self.run_id and value['attemptId'] == self.client.actor.id
                    and value['epoch'] == self.client.actor.epoch and value['generationIds'] == self.generation_ids)
            summary = fields(value['summary'],'manifest revision routes stateSha256 stateBytes')
            require(summary['revision'] == value['revision'] and type(summary['revision']) is int)
            sha(summary['stateSha256']);counter(summary['stateBytes'],0,1048576)
            require(len(encode(summary)) <= 65536)
            manifest=fields(summary['manifest'],'version inputs outputs limits')
            require(type(manifest['version']) is int and manifest['version'] == 1)
            fields(manifest['limits'],'max_frames max_buffer_bytes max_state_bytes')
            limits=Limits(**manifest['limits']);routes=set()
            for direction in ('inputs','outputs'):
                entries=manifest[direction];require(type(entries) is list and len(entries) <= 16)
                for entry in entries:
                    fields(entry,'routeId generation producer')
                    binding=Binding(entry['routeId'],entry['generation'],Producer.parse(entry['producer']))
                    require(binding.route_id not in routes);routes.add(binding.route_id)
                    require(direction != 'outputs' or binding.producer == self.client.actor)
            require(len(routes) == len(self.generation_ids) and limits.max_frames >= len(routes)
                    and limits.max_buffer_bytes >= len(routes) and summary['stateBytes'] <= limits.max_state_bytes)
            require(type(summary['routes']) is list and len(summary['routes']) == len(routes))
            for cursor in summary['routes']:
                fields(cursor,'routeId received committed ended');require(type(cursor['routeId']) is str and cursor['routeId'] in routes)
                routes.remove(cursor['routeId']);counter(cursor['received']);counter(cursor['committed'],0,cursor['received'])
                require(type(cursor['ended']) is bool and (not cursor['ended'] or cursor['received'] > 0))
            # A concrete candidate supplies the exact manifest/cursors/state identity.
            if snapshot is not None:
                request = self.candidate(snapshot,previous_id)
                require(all(value[key] == expected for key,expected in request.items()))
                doc = snapshot.document()
                expected = {'manifest':doc['manifest'], 'revision':doc['revision'],
                            'routes':[{k:v for k,v in row.items() if k != 'frames'} for row in doc['routes']],
                            'stateSha256':hashlib.sha256(state_bytes(doc['stateBase64'],1048576)).hexdigest(),
                            'stateBytes':len(state_bytes(doc['stateBase64'],1048576))}
                require(encode(summary) == encode(expected))
            return value
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError, AssignmentError, JournalError):
            raise CheckpointError('Invalid checkpoint receipt') from None

    def _url(self, value):
        try:
            text(value,16384);url=urllib.parse.urlsplit(value)
            require(url.hostname and url.username is None and url.password is None and not url.fragment
                    and (url.port is None or 1 <= url.port <= 65535))
            require(url.scheme == 'https' or self.allow_http_loopback and url.scheme == 'http' and url.hostname == '127.0.0.1')
            return value
        except ValueError:
            raise CheckpointError('Invalid checkpoint storage URL') from None

    def upload(self, snapshot, previous_id, *, timeout=1):
        value = self._post('uploads',{'checkpoint':self.candidate(snapshot,previous_id)},timeout)
        require(type(value) is dict)
        if set(value) == {'checkpoint'}:
            self.receipt(value['checkpoint'],snapshot,previous_id)
        else:
            fields(value,'upload');grant=fields(value['upload'],'url headers expiresAt')
            self._url(grant['url']);instant(grant['expiresAt'])
            require(grant['headers'] == {'Content-Type':MEDIA_TYPE, 'x-amz-meta-sha256':snapshot.sha256,
                    'x-amz-checksum-sha256':base64.b64encode(bytes.fromhex(snapshot.sha256)).decode('ascii')})
        return value

    def put(self, snapshot, grant, *, timeout=1):
        self._timeout(timeout)
        # Revalidate a caller-held grant before sending any snapshot bytes.
        fields(grant,'url headers expiresAt');instant(grant['expiresAt'])
        require(grant['headers'] == {'Content-Type':MEDIA_TYPE, 'x-amz-meta-sha256':snapshot.sha256,
                'x-amz-checksum-sha256':base64.b64encode(bytes.fromhex(snapshot.sha256)).decode('ascii')})
        try:
            request=urllib.request.Request(self._url(grant['url']),data=snapshot.wire,headers=grant['headers'],method='PUT')
            with self.storage.open(request,timeout=timeout) as response:
                require(response.status == 200)
                version=text(response.headers.get('x-amz-version-id'),1024);require(version != 'null')
            return version
        except urllib.error.HTTPError as failure:
            status=failure.code;failure.close()
            if status in (403,429,500,502,503,504):
                raise CheckpointUnavailable('Checkpoint storage unavailable or grant expired') from None
            raise CheckpointError('Checkpoint storage rejected') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise CheckpointUnavailable('Checkpoint storage unavailable') from None

    def commit(self, snapshot, previous_id, version_id, *, timeout=1):
        text(version_id,1024);require(version_id != 'null')
        value=self._post('commit',{'checkpoint':self.candidate(snapshot,previous_id),'versionId':version_id},timeout)
        fields(value,'checkpoint')
        return self.receipt(value['checkpoint'],snapshot,previous_id)

    def latest(self, *, timeout=1):
        value=self._post('latest',{'generationIds':self.generation_ids.copy()},timeout)
        require(type(value) is dict)
        if value.get('checkpoint') is None:
            fields(value,'checkpoint')
        else:
            fields(value,'checkpoint download');receipt=self.receipt(value['checkpoint'])
            self._download_grant(value['download'],receipt)
        return value

    def _download_grant(self, grant, receipt):
        fields(grant,'url expiresAt');instant(grant['expiresAt'])
        url=urllib.parse.urlsplit(self._url(grant['url']))
        require(urllib.parse.parse_qs(url.query,keep_blank_values=True).get('versionId') == [receipt['versionId']])

    def download(self, value, *, guard, timeout=1):
        """Read only the authenticated fixed version, within its exact byte budget."""
        timeout=self._timeout(timeout);require(callable(guard));guard()
        fields(value,'checkpoint download');receipt=self.receipt(value['checkpoint'])
        self._download_grant(value['download'],receipt)
        try:
            request=urllib.request.Request(value['download']['url'],method='GET',headers={'Accept-Encoding':'identity'})
            wire=bytearray();digest=hashlib.sha256();expected=receipt['bytes']
            with self.storage.open(request,timeout=timeout) as response:
                guard()
                require(response.status == 200)
                require(response.headers.get_all('x-amz-version-id') == [receipt['versionId']])
                require(response.headers.get_all('Content-Length') == [str(expected)])
                require(response.headers.get_all('Content-Type') == [MEDIA_TYPE])
                require(response.headers.get_all('Content-Encoding') in (None,['identity']))
                while True:
                    guard()
                    chunk=response.read(min(65536,expected-len(wire)+1))
                    guard()
                    if not chunk:break
                    require(len(wire)+len(chunk) <= expected)
                    wire.extend(chunk);digest.update(chunk)
            require(len(wire) == expected and digest.hexdigest() == receipt['sha256'])
            snapshot=Snapshot(bytes(wire))
            self.receipt(receipt,snapshot,receipt['previousCheckpointId'])
            guard()
            return snapshot
        except urllib.error.HTTPError as failure:
            status=failure.code;failure.close()
            if status in (403,429,500,502,503,504):
                raise CheckpointUnavailable('Checkpoint storage unavailable or grant expired') from None
            raise CheckpointError('Checkpoint storage rejected') from None
        except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException):
            raise CheckpointUnavailable('Checkpoint storage unavailable') from None
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError, JournalError):
            raise CheckpointError('Invalid checkpoint snapshot') from None

    def recover(self, directory, inputs, outputs, limits, execution_sha256, *, guard, timeout=1):
        """Explicit new-volume recovery under unchanged Attempt and route authority.

        No missing checkpoint, old local journal or new generation is silently used.
        Recheck authenticated latest after storage I/O, before creating the journal.
        """
        require(callable(guard));guard();sha(execution_sha256)
        value=self.latest(timeout=timeout);guard();receipt=value['checkpoint']
        require(receipt is not None and receipt['executionSha256'] == execution_sha256)
        require(receipt['summary']['manifest'] == json.loads(manifest(inputs,outputs,limits)))
        snapshot=self.download(value,guard=guard,timeout=timeout)
        current=self.latest(timeout=timeout)['checkpoint'];guard()
        require(current == receipt)
        return restore(directory,snapshot,inputs,outputs,limits,expected_sha256=receipt['sha256'],
                       execution_sha256=execution_sha256,guard=guard)


class CheckpointPublisher:
    """One control/storage request per step, on the journal's owning thread.

On restart the authenticated latest receipt must match either local confirmed
state or the immutable pending candidate. No old volume is silently promoted.
"""
    def __init__(self, client, journal, execution_sha256, guard):
        require(isinstance(client,CheckpointClient) and journal.durability == 'EXTERNAL' and callable(guard))
        sha(execution_sha256)
        self.client,self.journal,self.execution_sha256,self.guard=client,journal,execution_sha256,guard
        self.phase='latest';self.snapshot=None;self.previous_id=None;self.grant=None;self.version=None
        self.next_at=0.;self.retries=0;self.confirmations=0;self.last_receipt=None

    def _accept(self, receipt):
        self.guard()
        confirm(self.journal,receipt['serial'],receipt['sha256'])
        self.previous_id=receipt['id'];self.last_receipt=receipt
        self.snapshot=self.grant=self.version=None;self.phase='capture';self.confirmations+=1

    def step(self, *, timeout=1):
        self.guard()
        timeout=self.client._timeout(timeout)
        if time.monotonic() < self.next_at:
            return
        try:
            if self.phase == 'latest':
                value=self.client.latest(timeout=timeout)['checkpoint'];self.guard()
                serial,digest=self.journal.db.execute('SELECT confirmed_serial,digest FROM durability WHERE id=1').fetchone()
                self.snapshot=capture(self.journal,self.execution_sha256)
                if value is None:
                    require(serial == -1 and digest is None);self.phase='upload'
                elif value['serial'] == serial and value['sha256'] == digest:
                    require(value['executionSha256'] == self.execution_sha256)
                    self.previous_id=value['id'];self.last_receipt=value;self.phase='upload' if self.snapshot else 'capture'
                else:
                    require(self.snapshot is not None)
                    self.client.receipt(value,self.snapshot,value['previousCheckpointId'])
                    self._accept(value)
            else:
                if self.phase == 'capture':
                    self.snapshot=capture(self.journal,self.execution_sha256)
                    if self.snapshot is None:
                        self.next_at=time.monotonic()+.05
                        return
                    self.phase='upload'
                if self.phase == 'upload':
                    value=self.client.upload(self.snapshot,self.previous_id,timeout=timeout);self.guard()
                    if 'checkpoint' in value:self._accept(value['checkpoint'])
                    else:self.grant=value['upload'];self.phase='put'
                elif self.phase == 'put':
                    self.version=self.client.put(self.snapshot,self.grant,timeout=timeout);self.guard()
                    self.grant=None;self.phase='commit'
                elif self.phase == 'commit':
                    receipt=self.client.commit(self.snapshot,self.previous_id,self.version,timeout=timeout);self.guard()
                    self._accept(receipt)
            self.retries=0;self.next_at=0.
        except CheckpointUnavailable:
            self.guard()
            # PUT may have succeeded without a reply. Request a fresh grant for the
            # same candidate; commit retries keep their exact version and identity.
            if self.phase == 'put':self.phase='upload';self.grant=None
            self.retries+=1
            self.next_at=time.monotonic()+min(timeout,.05*2**min(self.retries,5))
