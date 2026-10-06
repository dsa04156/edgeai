# ADR0021 — 스트림 메시지의 형식·신원·크기 경계

상태: 첫 구성 요소 구현·단위 검증. M7 실행·브로커 통합 수용은 아직 아니다.
원문 범위와 미정 항목은 [M7 요구사항](../requirements/m7-requirements.md)을 따른다.

## 메시지 계약

`contracts/streams/frame.schema.json`과 Python `stream_protocol` codec은 `edgeai.stream/v1`
메시지를 정의한다. route UUID와 generation, producer의 종류/ID/epoch, sequence를 포함한다.
Device producer는 Device ID와 session ID를 함께 요구한다. Task producer는 Attempt ID를
사용하며 Device session의 필드와 혼합할 수 없다. UUID는 소문자 표준형이고 카운터는1부터
2^53−1까지의 정수다. codec은 boolean과 JSON 실수 표기 카운터도 거절한다.

`DATA`는 payload의 정확한 bytes를 표준 base64로 운반한다. SHA-256도 JSON 재직렬화한 값이
아닌 그 bytes에서 계산한다. mediaType은 소문자 type/subtype이며 별도 parameters를 허용하지
않는다. 실제 payload의 schema/unit/range 검사는 SERVICE/DEVICE 소비 계약의 책임이다.
`END`는 빈 payload·빈 bytes의 digest·null mediaType을 갖고 빈 DATA와 구별한다. 이후 consumer는
순번의 연속성과 누락 없는 처리를 확인해야 하며 END 파싱만으로 Task/Result를 완료하지 않는다.

payload 최대256KiB, wire 최대512KiB이며 wire 제한을 JSON 파싱 전에 검사한다. 중복 JSON 키,
NaN/Infinity, 미지 필드/버전, 비표준 base64, 잘못된 checksum을 거절한다. JSON Schema는 구조를,
codec은 bytes·정수 표현·checksum까지 검사한다. 오류 메시지에 원문 payload나 식별자를 넣지 않는다.

## 신뢰 경계와 후속 작업

소비자의 Binding은 인증된 제어 서버 배정에서 가져와야 한다. 수신 메시지에서 Binding을
만들어 스스로 검증하는 방식은 허용하지 않는다. Binding 검사는 route/generation과 전체 producer
identity를 대조한다. checksum과 identity 일치는 transport 인증을 대신하지 않으며 MQTT client/topic
권한과 실제 producer fencing은 후속 adapter/controller에서 함께 구현해야 한다.

codec은 순번 증가·중복 제거·buffer·ack·checkpoint를 구현하지 않는다. QoS 전송 확인을 처리
완료로 바꾸지도 않는다. 이 조건들은 DataRoute 수명·flow control·Runner 실행을 연결하는 다음
계약에서 정한다. 아직 공개 STREAM 실행501이나 기존 BATCH/VD 실행 경로는 바꾸지 않는다.

## 검증

`bash scripts/collect-evidence.sh stream-protocol python3 -m unittest discover -s runner/tests -p test_stream_protocol.py -v`

실행 `20261002T203728Z-90a8cafd`:9개 PASS/0. 정확한 binary/UTF-8 bytes, Device/Task 분리,
세대/session/epoch/route 불일치, 최대 크기와 파싱 전 차단, 위조 bytes/digest, 중복/미지 필드,
카운터·base64·END 조건을 검증했다. 브로커 전달·프로세스 복원·실제 다중 장치 계산의 증거는 아니다.
후속 `20261002T204036Z-88b8aed4`에서 Draft202012Validator로 schema 자체와 유효4개/무효6개
벡터를 검사했다. 기존 Runner/VD를 포함한37개도 `20261002T203949Z-f320294a` PASS/0이다.
