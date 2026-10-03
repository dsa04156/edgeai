# ADR0031 — 외부 체크포인트와 스트림 전송 확정 범위

상태: SDK·실제 SQLite/TLS MQTT/S3 구성 요소 구현. 인증된 체크포인트 API/DB 이력과
새 Attempt·generation 전환 수용은 후속이며 공개 STREAM 실행은 계속501이다.

## 저장 단위

`edgeai.stream-checkpoint/v1`은 최대72MiB의 canonical ASCII JSON이다. 계산 revision/state,
입출력 route의 전체 binding, 수신/처리 위치·END와 미처리 입력·미확인 출력 전체를 저장한다.
기존 journal limits와 Processor의 command/parameters/port/timeout digest도 보존한다.
다운로드한 SQLite 파일을 열거나 SQL/pickle을 실행하지 않는다. 중복 JSON 키, 잘못된
base64/SHA, 순번 공백·END 모순·다른 binding·경로별 count/byte 초과를 복원 전에 거절한다.
형식은 [schema](../../contracts/streams/checkpoint.schema.json)와
[실제 export 예제](../../contracts/streams/checkpoint.example.json)를 따른다.

계산 revision만으로 전체 상태를 식별하지 않는다. 입력 수신과 출력 ACK도 복구해야 할
journal 내용을 바꾸므로 별도 snapshot serial을 같은 SQLite 트랜잭션에서 증가시킨다.
롤백과 무변경 중복은 serial을 증가시키지 않는다. confirm 메타데이터도 serial을 증가시키지
않아 자기 자신을 다시 저장해야 하는 무한 반복을 피한다.

## 외부 확정 전 전송 제한

`Journal`/`Session`의 durability는 LOCAL 또는 EXTERNAL이며 기존 journal의 모드를 암묵적으로
바꾸지 않는다. 기존 로컬 journal은 추가 테이블로 열 수 있으나 EXTERNAL로 자동 승격하지 않는다.
LOCAL은 기존 같은 볼륨 계약이다. EXTERNAL은 외부 확정한 입력 처리 위치까지만 ACK하고,
확정한 출력 위치까지만 MQTT로 발행한다. 로컬 계산/수신은 계속하지만 기존 용량 제한을 지킨다.
아직 확정하지 않은 출력에 대한 consumer ACK도 거절한다.

`capture`는 pending snapshot 하나를 SQLite에 고정한다. 새 입력이나 계산이 추가되어도 그
후보의 serial/SHA/bytes는 바꾸지 않는다. 소유 프로세스 재시작과 응답 유실 뒤 동일 bytes를
재시도할 수 있다. `confirm`은 정확한 후보 serial/SHA와 일치하는 신뢰된 제어 응답만 호출자가
전달해야 한다. 이 함수 자체는 외부 인증 API가 아니며 S3 PUT 성공을 검증 완료로 취급하지 않는다.
확정한 frontier와 후보 삭제는 원자적이며 현재 lease guard를 commit 직전에도 확인한다.
동일 확인 재전송은 멱등이고 과거/다른 SHA는 거절한다. 오래된 중복 확인이 새 후보를 지우지 않는다.

## 복원과 권한

`restore`는 신뢰된 메타데이터의 기대 SHA와 실행 digest, 정확한 전체 binding/limits가
일치할 때만 새로운 private journal을 생성한다. 상태/남은 입력/출력/END/serial과 Processor
pin을 복구한다. 확인된 snapshot의 frontier까지만 발행할 수 있으며 이후 계산은 새 확정이 필요하다.
원본 볼륨 삭제·이미 입력을 폐기한 upstream·중복 output 재생·9→14의 실제 모델 재개를 시험한다.

현재 restore는 동일 binding만 허용한다. Snapshot 파일만 보고 새로운 producer, Attempt,
generation 또는 Device session으로 바꾸지 않는다. 복원 경로는 신뢰된 최신 snapshot 선택에
의존한다. 과거 snapshot을 임의 선택하면 이미 ACK한 데이터가 사라질 수 있다.
모델의 외부 HTTP/파일 부작용은 이 journal로 원자화되지 않는다.

## 남은 제어 경로

- 현재 Run/Task/Attempt·Pod/VD 신원과 SERVICE 이미지/profile digest를 영속 메타데이터에
  연결한다. Processor digest만으로 이미지 또는 실행 주체의 동일성을 증명하지 않는다.
- 권한 확인 → 실제 S3 fixed version/bytes/SHA 검증 → 권한 재검사/DB 확정 순서를 연결한다.
  lease/취소/전환 중 외부 I/O가 끝나도 과거 producer의 snapshot을 확정하지 않는다.
- 영속 단조 순번·이전 체크포인트 참조·멱등 확인과 최신 확정본 조회/복원 grant를 제공한다.
  raw frame은 PostgreSQL에 모두 저장하지 않는다. V1–V21은 보존하고 DB 변경은 V22+로 한다.
- 모든 관련 Device 잠금을 정렬해 획득한 뒤 Run/route를 잠근다. 다중 route의 두 번째 Device를
  Run 잠금 뒤에 잡지 않는다. 이전 권한 fence와 새 세대 재매핑을 명시적인 handover로 검증한다.
- Session의 자동 저장·heartbeat 예산, 종료 확인, SERVICE/Runner 공개 실행과 실제 Kubernetes
  다중 Device 장애 복원으로 확장한다. 이 구성 요소로 M5/M7 전체 완료를 판정하지 않는다.

실제 S3 시험은 독립 Python 프로세스의 export→원본 볼륨 삭제→고정 version의 download→restore를
사용한다. 버킷은 시험 전용이고 모든 object version을 정리한다. 이 시험의 확정 주체는 Java
fixture이며 실제 PostgreSQL checkpoint 레코드나 공개 제어 API의 증거는 아니다.
