"""Actual HTTPS, SQLite and TLS MQTT for automatic same-session reconnect.

The authority lifecycle is a fixture here; the Spring/PG DAG probe separately
exercises server-side fencing, revocation, retry and checkpoint handover.
"""
from dataclasses import replace
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

import test_stream_source as fixture
from test_stream_mqtt import eventually
from test_stream_assignment import GENERATION, POD
from test_stream_journal import A
from edgeai_runner.stream_assignment import AssignmentFenced
from edgeai_runner.stream_device_run import DeviceRunSource, SourceReconnecting
from edgeai_runner.stream_journal import Backpressure, Emission, Journal, Limits
from edgeai_runner.stream_mqtt import Link, MqttError, MqttBrokerRejected
from edgeai_runner.stream_source import SourceError


class DeviceRunSourceTest(unittest.TestCase):
    def setUp(self):
        fixture.StreamSourceTest.setUp(self)
        self.api.duration=20;self.api.deadline=time.monotonic()+20

    def open(self, *, create=True, **options):
        self.source=DeviceRunSource(self.client,POD,[A.route_id],self.directory,create=create,**options)
        return self.source

    pump=fixture.StreamSourceTest.pump
    emit=fixture.StreamSourceTest.emit
    consume=fixture.StreamSourceTest.consume

    def ready(self):
        eventually(self.pump,lambda:self.source.ready)

    def fence(self):
        old=self.source.source
        self.api.status=409;self.api.completion_status=409;self.api.generation_state='FENCED'
        eventually(self.pump,lambda:not self.source.ready)
        self.assertTrue(old.closed);self.assertTrue(old.link.closed)
        self.assertFalse(self.source.closed)

    def advance(self):
        # Explicit broker ACL fixture provisioning; SDK never creates permissions.
        self.link.close();self.sink.close();self.broker.stop()
        acl=self.root/'acl';acl.write_text(acl.read_text().replace('/1/','/2/'))
        self.broker.start()
        self.api.binding=replace(A,generation=2);self.api.generation_id=POD
        self.api.generation_state='ACTIVE';self.api.status=None;self.api.completion_status=None;self.api.deadline=time.monotonic()+20
        self.sink=Journal(self.root/'sink-next',[self.api.binding],[],create=True);self.addCleanup(self.sink.close)
        self.link=Link(self.sink,self.broker.endpoint('processor'),'next-consumer');self.addCleanup(self.link.close)

    def test_waits_for_generation_and_preserves_unacknowledged_samples_on_automatic_handover(self):
        self.api.generation_state=None
        source=self.open(limits=Limits(max_frames=1));source.step()
        self.assertFalse(source.ready);self.assertFalse((self.directory/'journal').exists())
        with self.assertRaises(SourceReconnecting):self.emit()
        self.api.generation_state='ACTIVE';self.ready();self.emit()
        with self.assertRaises(Backpressure):self.emit(b'5',b'offset2')
        self.fence()
        with self.assertRaises(SourceReconnecting):self.emit(b'5',b'offset2')
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_ALREADY_OWNED'):
            DeviceRunSource(self.client,POD,[A.route_id],self.directory)
        self.advance();self.ready()
        self.assertEqual(2,source.connections);self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(1,source.checkpoint().output_sequences[A.route_id])
        eventually(self.pump,lambda:bool(self.sink.pending()))
        frame=self.sink.pending()[0];self.assertEqual((1,b'4',2),(frame.sequence,frame.payload,frame.binding.generation))
        self.consume();eventually(self.pump,lambda:not source.source.journal.outgoing())
        self.emit(b'5',b'offset2');eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual(2,self.sink.pending()[0].sequence);self.assertEqual(b'offset2',source.checkpoint().state)

    def test_discovery_service_outage_then_fence_race_does_not_create_a_journal(self):
        source=self.open();self.api.route_status=503;source.step()
        self.assertFalse(source.closed);self.assertFalse((self.directory/'journal').exists())
        self.api.route_status=None;self.api.status=409
        eventually(source.step,lambda:len([p for p in self.api.paths if p.endswith('/streams')])>0)
        self.assertFalse(source.closed);self.assertFalse((self.directory/'journal').exists())
        self.api.status=None;self.ready();self.assertEqual(1,source.connections)

    def test_discovery_rejects_replaced_session_and_cancelled_run_without_new_authority(self):
        source=self.open();self.ready();self.emit();self.fence()
        self.api.route_status=409
        with self.assertRaises(AssignmentFenced):eventually(source.step,lambda:source.ready)
        self.assertTrue(source.closed)
        with Journal(self.directory/'journal',[],[A]) as journal:
            self.assertEqual(b'offset1',journal.checkpoint().state)
        self.api.route_status=None;self.api.run_state='CANCELLED'
        source=self.open(create=False)
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_RUN_TERMINATED'):source.step()
        self.assertTrue(source.closed)

    def test_authentication_failure_and_invalid_assignment_are_terminal(self):
        source=self.open();self.api.status=401
        with self.assertRaises(AssignmentFenced):source.step()
        self.assertTrue(source.closed);self.assertFalse((self.directory/'journal').exists())
        self.api.status=None;source=self.open()
        self.api.on_routes=lambda value:value['items'][0]['generation'].update(number=2)
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_SCOPE_MISMATCH'):source.step()
        self.assertTrue(source.closed);self.assertFalse((self.directory/'journal').exists())

    def test_local_lease_expiry_rolls_back_sample_and_reconnects_with_fresh_authority(self):
        source=self.open();self.ready();self.emit()
        child=source.source
        expired=replace(next(iter(child.assignments.values())),deadline=time.monotonic()-1)
        child.assignments={GENERATION:expired}
        with self.assertRaises(SourceReconnecting):self.emit(b'5',b'offset2')
        self.assertTrue(child.closed);self.assertFalse(source.ready)
        self.ready();self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(1,source.checkpoint().output_sequences[A.route_id])

    def test_same_volume_restart_automatically_hands_over_without_resetting_sensor_cursor(self):
        source=self.open();self.ready();self.emit();source.close()
        self.advance();source=self.open(create=False);self.ready()
        self.assertEqual(b'offset1',source.checkpoint().state)
        eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual((1,b'4'),(self.sink.pending()[0].sequence,self.sink.pending()[0].payload))

    def end(self):
        self.source.emit([Emission(A.route_id,b'',None,'END')],b'last-offset')
        eventually(self.pump,lambda:bool(self.sink.pending()));self.consume()
        eventually(self.pump,lambda:self.source.source.intent is not None)

    def test_closed_terminal_intent_recovers_actual_grant_without_mqtt_authority(self):
        source=self.open();self.ready();self.end();source.close()
        self.api.completion_state='FINALIZE';self.api.status=409;self.api.generation_state='CLOSED';self.api.run_state='SUCCEEDED'
        self.broker.stop();count=len(self.api.paths)
        source=self.open(create=False);eventually(source.step,lambda:source.completed)
        self.assertIsNone(source.source.link);self.assertEqual(b'last-offset',source.checkpoint().state)
        self.assertTrue(all(p.endswith(('/routes','/complete')) for p in self.api.paths[count:]))

    def test_waiting_terminal_intent_survives_journal_handover_before_intent_rewrite(self):
        source=self.open();self.ready();self.end();self.fence();self.advance()
        # Model the atomic journal commit followed by process death before the
        # separate completion file is replaced. Old intent and new DB must agree
        # on cursors, then the new authenticated generation can be reported.
        with Journal(self.directory/'journal',[],[self.api.binding],source_handover=True,guard=lambda:None):pass
        source.close();source=self.open(create=False);self.ready()
        self.assertEqual(b'last-offset',source.checkpoint().state)
        self.assertEqual(POD,source.source.intent['routes'][0]['generationId'])
        self.api.completion_state='FINALIZE';eventually(source.step,lambda:source.completed)

    def test_cancel_timeout_missing_volume_and_terminal_without_intent_fail_closed(self):
        source=self.open();source.cancel.set()
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_CANCELLED'):source.step()
        with self.assertRaisesRegex(SourceError,'STREAM_EXPLICIT_SOURCE_RECOVERY_REQUIRED'):self.open(create=False)
        source=self.open(timeout=.02);time.sleep(.03)
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_TIMEOUT'):source.step()
        self.api.run_state='SUCCEEDED';source=self.open()
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_COMPLETION_MISSING'):source.step()
        self.assertFalse((self.directory/'journal').exists())

    def test_paged_discovery_is_bounded_and_does_not_open_until_selected_route_is_found(self):
        source=self.open()
        def first_page(value):
            original=value['items'][0]
            self.api.route_items=[dict(original,routeId=f'00000000-0000-4000-8000-{n:012d}') for n in range(100)]+[original]
            value['items']=self.api.route_items[:100];value['nextOffset']=100;self.api.on_routes=None
        self.api.on_routes=first_page
        source.step();self.assertFalse(source.ready);self.assertFalse((self.directory/'journal').exists())
        source.step();self.assertTrue(source.ready)
        self.assertEqual(2,len([p for p in self.api.paths if p.endswith('/routes')]))

    def test_transport_configuration_errors_are_not_hidden_as_reconnect(self):
        source=self.open();self.ready()
        with patch.object(source.source,'step',side_effect=MqttError('MQTT TLS verification failed')):
            with self.assertRaises(MqttError):source.step()
        self.assertTrue(source.closed)

    def test_broker_revoke_before_heartbeat_rechecks_authority_and_preserves_source(self):
        source=self.open();self.ready();self.emit()
        eventually(self.pump,lambda:source.source.link.ready)
        old=source.source
        # Force the real broker to revoke first, before the next HTTP heartbeat.
        old.next_at={identity:time.monotonic()+10 for identity in old.next_at}
        self.api.status=409;self.api.generation_state='FENCED'
        passwords=self.root/'passwords';original=passwords.read_text()
        self.broker.stop()
        passwords.write_text(''.join(line for line in original.splitlines(keepends=True) if not line.startswith('source-a:')))
        self.broker.start()
        try:
            eventually(source.step,lambda:source.source is None)
            self.assertFalse(source.closed);self.assertTrue(old.closed);self.assertTrue(old.link.closed)
        finally:
            passwords.write_text(original)
        self.advance();self.ready()
        self.assertEqual(2,source.connections)
        self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(1,source.checkpoint().output_sequences[A.route_id])
        eventually(self.pump,lambda:bool(self.sink.pending()))
        frame=self.sink.pending()[0]
        self.assertEqual((1,b'4',2),(frame.sequence,frame.payload,frame.binding.generation))

    def test_actual_broker_restart_reconnects_live_assignment_without_replacing_source(self):
        source=self.open();self.ready();self.emit()
        eventually(self.pump,lambda:source.source.link.ready)
        old=source.source
        self.broker.stop()
        eventually(source.step,lambda:not old.link._socket_open)
        self.broker.start()
        eventually(self.pump,lambda:old.link.ready and bool(self.sink.pending()))
        self.assertIs(old,source.source);self.assertFalse(source.closed)
        self.assertEqual(1,source.connections)
        self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual((1,b'4'),(self.sink.pending()[0].sequence,self.sink.pending()[0].payload))

    def reject_before_heartbeat(self, status):
        source=self.open();self.ready();self.emit()
        old=source.source
        old.next_at={identity:time.monotonic()+10 for identity in old.next_at}
        self.api.status=status
        old.link.error=MqttBrokerRejected('MQTT publication rejected')
        return source,old

    def test_broker_rejection_with_still_valid_authority_remains_terminal(self):
        source,old=self.reject_before_heartbeat(None)
        with self.assertRaises(MqttBrokerRejected):source.step()
        self.assertTrue(source.closed);self.assertTrue(old.closed);self.assertTrue(old.link.closed)
        self.assertEqual(1,source.connections)
        with Journal(self.directory/'journal',[],[A]) as journal:
            self.assertEqual(b'offset1',journal.checkpoint().state)

    def test_broker_rejection_cannot_hide_http_identity_failure(self):
        source,old=self.reject_before_heartbeat(401)
        with self.assertRaises(AssignmentFenced) as result:source.step()
        self.assertEqual(401,result.exception.status)
        self.assertTrue(source.closed);self.assertTrue(old.link.closed)

    def test_broker_rejection_during_api_outage_closes_transport_before_rediscovery(self):
        source,old=self.reject_before_heartbeat(503)
        source.step()
        self.assertFalse(source.closed);self.assertIsNone(source.source);self.assertTrue(old.link.closed)
        with self.assertRaises(SourceReconnecting):self.emit(b'5',b'offset2')
        self.api.route_status=503
        eventually(source.step,lambda:source.retries>=2)
        self.assertIsNone(source.source);self.assertEqual(1,source.connections)
        self.api.route_status=None;self.api.status=409;self.api.generation_state='FENCED'
        self.advance();self.ready()
        self.assertEqual(b'offset1',source.checkpoint().state)
        self.assertEqual(1,source.checkpoint().output_sequences[A.route_id])

    def test_fanout_waits_for_every_selected_generation_before_opening_any_transport(self):
        self.source=DeviceRunSource(self.client,POD,[A.route_id,POD],self.directory,create=True)
        def fanout(value):
            original=value['items'][0]
            value['items']=sorted([original,dict(original,routeId=POD,generation=None)],key=lambda row:row['routeId'])
        self.api.on_routes=fanout
        self.source.step()
        self.assertFalse(self.source.ready);self.assertFalse((self.directory/'journal').exists())
        self.assertTrue(all(path.endswith('/routes') for path in self.api.paths))
        self.source.cancel.set()
        with self.assertRaises(SourceError):self.source.step()

    def test_terminal_intent_cannot_recover_under_changed_discovery_bindings(self):
        source=self.open();self.ready();self.end();source.close()
        self.api.generation_state='CLOSED';self.api.completion_state='FINALIZE'
        self.api.on_routes=lambda value:value['items'][0]['generation'].update(number=2)
        source=self.open(create=False)
        with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_SCOPE_MISMATCH'):source.step()
        self.assertTrue(source.closed);self.assertFalse(source.completed)

    def test_sigkill_releases_owner_and_new_process_resumes_pending_source_volume(self):
        code='''import sys,time
from pathlib import Path
from edgeai_runner.stream_device_run import DeviceRunSource
from edgeai_runner.stream_assignment import BindingClient
from edgeai_runner.stream_journal import Emission
from test_stream_journal import A
from test_stream_assignment import POD
url,token,ca,directory=sys.argv[1:]
client=BindingClient(url,A.producer,token,ca_file=ca)
with DeviceRunSource(client,POD,[A.route_id],directory,create=True) as source:
 while not source.ready:source.step();time.sleep(.005)
 source.emit([Emission(A.route_id,b'4','application/json')],b'offset1')
 Path(directory,'emitted').touch()
 while True:source.step();time.sleep(.005)
'''
        sdk=Path(__file__).resolve().parents[1]
        env={**os.environ,'PYTHONPATH':os.pathsep.join(str(p) for p in (sdk,sdk/'tests',sdk/'integration'))}
        child=subprocess.Popen([sys.executable,'-c',code,self.api.url,str(self.root/'device.token'),str(self.broker.ca),str(self.directory)],
                               env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        try:
            eventually(lambda:self.assertIsNone(child.poll()),lambda:(self.directory/'emitted').exists(),5)
            with self.assertRaisesRegex(SourceError,'STREAM_SOURCE_ALREADY_OWNED'):self.open(create=False)
            child.kill();self.assertEqual(-9,child.wait(5));self.assertEqual(b'',child.stderr.read())
        finally:
            if child.poll() is None:child.kill();child.wait(5)
            child.stderr.close()
        self.advance();source=self.open(create=False);self.ready()
        self.assertEqual(b'offset1',source.checkpoint().state)
        eventually(self.pump,lambda:bool(self.sink.pending()))
        self.assertEqual(1,self.sink.pending()[0].sequence)


if __name__=='__main__':unittest.main()
