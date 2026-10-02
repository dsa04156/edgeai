"""Wire boundary/fencing fixtures, not a broker or streaming execution acceptance test."""
from dataclasses import replace
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from edgeai_runner.stream_protocol import Acknowledgement, Binding, Frame, Producer, StreamProtocolError, decode, decode_ack, MAX_COUNTER, MAX_FRAME_BYTES, MAX_PAYLOAD_BYTES

ROUTE = '11111111-1111-4111-8111-111111111111'
SESSION = '22222222-2222-4222-8222-222222222222'
DEVICE = '33333333-3333-4333-8333-333333333333'
OTHER = '44444444-4444-4444-8444-444444444444'


class StreamProtocolTest(unittest.TestCase):
    def setUp(self):
        self.source = Producer('DEVICE_SESSION', SESSION, 2, DEVICE)
        self.binding = Binding(ROUTE, 3, self.source)
        self.frame = Frame(self.binding, 7, 'DATA', b'{"reading":12.50,"unit":"C"}', 'application/json')

    def wire(self, **updates):
        value = json.loads(self.frame.encode()); value.update(updates)
        return json.dumps(value, separators=(',', ':')).encode()

    def test_device_and_task_frames_preserve_exact_bytes_and_identity(self):
        for producer in (self.source, Producer('TASK_ATTEMPT', OTHER, 9)):
            for payload in (self.frame.payload, b'', bytes(range(256)), '온도 23.5'.encode()):
                with self.subTest(producer=producer.kind, size=len(payload)):
                    frame = replace(self.frame, binding=replace(self.binding, producer=producer), payload=payload)
                    restored = decode(frame.encode())
                    self.assertEqual(frame, restored)
                    self.assertEqual(frame.encode(), restored.encode())

    def test_end_is_distinct_from_empty_data_and_cannot_smuggle_payload(self):
        end = Frame(self.binding, 8, 'END', b'', None)
        self.assertEqual(end, decode(end.encode()))
        self.assertNotEqual(end, replace(end, kind='DATA', media_type='application/json'))
        for value in (self.wire(kind='END'), self.wire(kind='END', mediaType=None)):
            with self.assertRaises(StreamProtocolError): decode(value)

    def test_changed_payload_or_digest_never_reaches_binding_verification(self):
        for value in (self.wire(payloadBase64='e30='), self.wire(sha256='0'*64), self.wire(sha256='A'*64)):
            with self.assertRaises(StreamProtocolError): decode(value)

    def test_old_generation_session_epoch_device_attempt_and_route_are_fenced(self):
        self.assertIs(self.frame, self.binding.verify(self.frame))
        bindings = [replace(self.binding, generation=2), replace(self.binding, route_id=OTHER),
                    replace(self.binding, producer=replace(self.source, id=OTHER)),
                    replace(self.binding, producer=replace(self.source, device_id=OTHER)),
                    replace(self.binding, producer=replace(self.source, epoch=1)),
                    replace(self.binding, producer=Producer('TASK_ATTEMPT', SESSION, 2))]
        for binding in bindings:
            with self.subTest(binding=binding), self.assertRaises(StreamProtocolError): binding.verify(self.frame)

    def test_maximum_payload_is_valid_but_wire_size_is_checked_before_parsing(self):
        frame = replace(self.frame, payload=b'x'*MAX_PAYLOAD_BYTES)
        self.assertEqual(MAX_PAYLOAD_BYTES, len(decode(frame.encode()).payload))
        with self.assertRaises(StreamProtocolError): replace(frame, payload=b'x'*(MAX_PAYLOAD_BYTES+1))
        with patch('edgeai_runner.stream_protocol.json.loads', side_effect=AssertionError('Must not parse oversized frame')):
            with self.assertRaises(StreamProtocolError): decode(b' '*(MAX_FRAME_BYTES+1))

    def test_counters_are_positive_safe_integers_and_not_booleans_or_floats(self):
        for field in ('sequence', 'generation'):
            for value in (0, -1, MAX_COUNTER+1, True, 1.0, '1', None):
                with self.subTest(field=field,value=value), self.assertRaises(StreamProtocolError): decode(self.wire(**{field:value}))
        self.assertEqual(MAX_COUNTER, decode(self.wire(sequence=MAX_COUNTER)).sequence)
        for value in (0, True, 1.0, MAX_COUNTER+1):
            with self.assertRaises(StreamProtocolError): Producer('TASK_ATTEMPT', OTHER, value)

    def test_duplicate_nested_unknown_fields_bad_json_and_versions_are_rejected(self):
        payloads = [b'{"sequence":1,"sequence":2}', b'{"x":{"epoch":1,"epoch":2}}', b'[]', b'null',
                    b'{"x":NaN}', b'\xff', b'['*2000+b']'*2000, self.wire(extra='ignored?'),
                    self.wire(apiVersion='edgeai.stream/v2'), self.wire(producer={'kind':'TASK_ATTEMPT','attemptId':OTHER,'epoch':1,'deviceId':DEVICE})]
        for payload in payloads:
            with self.subTest(size=len(payload)), self.assertRaises(StreamProtocolError): decode(payload)

    def test_base64_has_one_canonical_representation_and_media_type_is_bounded(self):
        frame = replace(self.frame, payload=b'f')
        original = json.loads(frame.encode())
        for bad in ('Zh==', 'Zg', 'Zg===', 'Zg==\n', 'é'):
            original['payloadBase64'] = bad
            with self.assertRaises(StreamProtocolError): decode(json.dumps(original).encode())
        for media in ('text/plain; charset=utf-8', 'a\n/b', 'A/B', 'a/'+'b'*128, None):
            with self.assertRaises(StreamProtocolError): decode(self.wire(mediaType=media))

    def test_identifiers_have_one_canonical_representation_and_producer_kinds_do_not_mix(self):
        for value in (ROUTE.replace('-', ''), '{'+ROUTE+'}', 1, None, 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'):
            with self.assertRaises(StreamProtocolError): decode(self.wire(routeId=value))
        for producer in ({'kind':'DEVICE_SESSION','sessionId':SESSION,'epoch':1},
                         {'kind':'DEVICE_SESSION','deviceId':DEVICE,'sessionId':SESSION,'epoch':1,'attemptId':OTHER},
                         {'kind':'TASK_ATTEMPT','attemptId':OTHER,'epoch':1,'sessionId':SESSION}):
            with self.assertRaises(StreamProtocolError): decode(self.wire(producer=producer))

    def test_processing_ack_has_bounded_canonical_identity_and_nonnegative_watermark(self):
        for sequence in (0, 1, MAX_COUNTER):
            ack = Acknowledgement(self.binding, sequence)
            self.assertEqual(ack, decode_ack(ack.encode()))
        for sequence in (-1, True, 1.0, MAX_COUNTER + 1):
            with self.assertRaises(StreamProtocolError): Acknowledgement(self.binding, sequence)
        good = json.loads(Acknowledgement(self.binding, 1).encode())
        for patch in ({'extra': 'value'}, {'sequence': True}, {'generation': 0}, {'apiVersion': 'unknown'}):
            with self.assertRaises(StreamProtocolError): decode_ack(json.dumps({**good, **patch}).encode())
        for raw in (b' ' * 2049, b'{"sequence":0,"sequence":1}', b'[]', b'\xff', b'{"x":NaN}'):
            with self.assertRaises(StreamProtocolError): decode_ack(raw)


if __name__ == '__main__': unittest.main()
