# ADR0032 — 인증된 스트림 체크포인트 확정

상태: 내부 HTTP·PostgreSQL·실제 S3 연결 구현. 자동 Session 저장·새 Attempt/세대 인계와
공개 STREAM 실행은 후속이다. [검증 범위](../evidence/m7-stream-checkpoint-api.md).

## 계약과 저장

Runner claim과 실제 Pod/VD 신원을 사용하는 내부 API 세 개를 추가한다.
`/internal/v1/attempts/{attemptId}/streams/checkpoints/{uploads|commit|latest}`의 POST다.
요청은256KiB 이하이며 실제 snapshot은 S3로 직접 전송한다.
한국어 설명과 요청/응답은 `contracts/openapi/stream-api.yaml`에 유지한다.

후보는 이전 확정 ID, 별도 snapshot serial, 내용 SHA-256/크기, 실행 설정 digest와
Task의 전체 입출력 generation ID 집합으로 식별한다. 서버는 Run/Task/Attempt/runtime/epoch,
producer Pod UID와 불변 SERVICE ProfileVersion을 함께 저장한다. 원본 프레임·상태·자격·
서명 URL은 DB에 저장하지 않는다. V22의 append-only 이력에는 고정 bucket/key/version과
최대64KiB의 검증된 manifest·계산 revision·커서·상태 해시/크기만 보존한다.

동일 최신 후보의 재전송은 첫 검증 version의 receipt를 반환한다. 중복 PUT으로 version이
추가되어도 확정본을 교체하지 않는다. 최신 이후 과거 후보 재전송과 잘못된 previous ID는409다.
업로드 성공만으로 SDK의 확인 위치를 전진시킬 수 없으며 authenticated commit receipt를
후보의 serial/SHA와 대조해야 한다. latest의 download URL은 확정한 S3 version을 지정한다.
latest는 현재 Task의 이력을 읽을 뿐 새 producer에 복원 권한을 부여하지 않는다.

## 잠금과 실제 내용 검증

1. 짧은 트랜잭션에서 VD → 모든 source Device(정렬) → Run → route 순서로 잠근다.
   Device를 잠그기 전/후 route membership을 비교하고 추가·불완전·중복 세대 집합을 거절한다.
   모든 ACTIVE generation, 양쪽 현재 주체·기한, Runner/VD runtime 권한을 검증한다.
2. 잠금을 해제한 뒤 실제 S3 fixed version의 bytes/media/SHA를 확인하고 private 임시 파일로
   읽는다. 기존 ArtifactFiles도 고정 version의 실제 내용을 다시 검증한다. 검증 동시성은
   인스턴스당2개, 파일은72MiB 이하이며 초과 동시 요청은429로 재시도한다.
3. JSON은 프레임 하나씩 파싱한다. canonical JSON digest, 정확한 입력/출력 binding,
   limits·순번 연속성·payload SHA/base64·END·커서를 검사한다. 전체72MiB JSON 트리를
   API heap에 만들지 않는다. 후보의 실행 digest와 serial도 문서와 대조한다.
4. 다시 같은 권한을 잠그고 검증한다. 취소·만료·교체·경로 변경·다른 최신 확정이 발생하면
   거절한다. receipt 삽입과 이전 확정본 비교는 Run 잠금 아래 원자적으로 처리한다.
   임시 파일은 성공/실패 모두 정리한다.

계산 revision·수신/처리 커서는 감소할 수 없고 END는 취소할 수 없다. 계산 revision이
같으면 상태 해시, 입력 처리 위치와 출력 생성 위치가 바뀔 수 없다. 입력 수신과 출력 ACK는
계산 없이 전진할 수 있다. 이 조건은 서비스와 DB insert trigger에서 검사한다.
DB는 UPDATE/DELETE/TRUNCATE, 불완전한 generation 집합과 잘못된 이력 연결도 거절한다.

## 경계와 다음 단계

V1–V21은 변경하지 않았다. V22 적용 후에도 migration을 수정하지 않는다.
동일 Task의 새 Attempt·다른 generation·실행 digest 변경은 명시적 handover가 없으면409다.
새 producer가 snapshot의 actor를 임의로 재작성하지 않는다. 실제 서비스 이미지/설정의
실행을 검증하는 역할은 기존 SERVICE/Runner 경로이며 클라이언트가 제출한 상태가 계산상
정답임을 이 API가 증명하지는 않는다.

현재 시험의 Pod 신원 gateway와 broker 활성화 receipt는 명시적 fixture다. SDK가 실제
snapshot을 생성하고 Java HTTP client가 실제 API·DB·S3를 호출한다. Session의 자동 업로드/
확정 client, 실제 Kubernetes Pod 인증까지 포함한 스트리밍 종단과 새 Pod handover는 남는다.
공개 STREAM501과 M5/M7–M10의 미완료 판정을 유지한다.
