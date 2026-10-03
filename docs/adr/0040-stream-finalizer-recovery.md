# ADR 0040: 공동 완료 허가 후 Runner의 최종 상태 복구

상태: 내부 서버·SDK 구현과 실제 Spring/S3/Runner 통합 검증. 공개 STREAM은 비활성.

## 문제와 결정

Task의 terminal checkpoint에 공동 완료 허가가 기록된 뒤에는 인접 참여자가 MQTT 경로를
닫을 수 있다. 이때 재시작한 Runner가 ACTIVE generation 배정을 기다리면 최종 파일을
만들 수 없다. 영속 완료 허가를 먼저 확인하고 최종 파일 생성만 재개하는 경로를 제공한다.

`streams/execution`의 FINALIZE 응답은 현재 Run/Task/Attempt/epoch, 허가된 checkpoint ID,
고정된 논리 입출력 경로와 역사적 generation ID를 반환한다. 이 응답은 MQTT 배정이나
lease 갱신이 아니다. `streams/checkpoints/finalized`는 같은 checkpoint의 고정 S3 version을
읽는 제한된 권한이다. 두 경로 모두 현재 인증된 producer와 활성 Run을 요구한다.
최신 checkpoint·허가 ID·Attempt/epoch·Pod UID·runtime이 모두 같아야 한다.
취소, producer 만료, 다른 checkpoint 또는 새 Attempt에는 이전 허가를 적용하지 않는다.

Runner는 새 빈 작업 디렉터리에서 이 응답을 처리한다. 실행 명령/인수·매개변수·논리 포트·
step timeout digest와 journal 한도를 대조하고, S3 bytes/SHA256·snapshot·END/ACK 커서를
검증한다. S3 읽기가 끝난 뒤 동일 허가를 다시 조회한 다음0600 상태 파일을 fsync한다.
MQTT Link·Session·지속 계산 모델은 생성하지 않는다. 기존 최종 파일 명령과 업로드·Result
commit을 실행하며, Result API도 현재 producer와 완료 허가를 다시 검증한다.

이 복구는 같은 현재 Attempt/epoch/Pod/runtime에 한정한다. 새 Pod/Attempt로의 전환,
임의의 기존 작업 디렉터리 재사용, offload 전체 수용을 의미하지 않는다.

## 실제 HTTP 표현 수정

실제 Runner 통합에서 STREAM `/claim`이400으로 거절됐다. 서비스가 응답에 넣은 중첩
`StreamExecutionSpec` record를 컨트롤러의 bounded canonical JSON 변환기가 지원하지 않았다.
일반 Jackson 변환기를 사용하던 기존 시험으로는 이 차이가 드러나지 않았다.
서비스에서 명시적인 JSON map으로 변환하고 기존 시험도 실제 컨트롤러 변환기를 사용한다.
순수 도메인과 canonical JSON 변환기의 책임·지원 값 범위는 유지한다.

## 수용 범위

[검증 기록](../evidence/m7-finalizer-recovery.md)은 실제 TLS Spring/MinIO·PG·MQTT·장치 SDK·
모델·Runner 최종 파일 실행을 연결한다. Pod 생성/신원과 완료 후 peer 경로 회수의 시작은
시험 fixture이며 broker 권한 회수는 실제 worker가 수행한다. 운영 TLS/Kubernetes 수용은 별도다.

공개 route 생성·그룹 동시 시작·인접 Task 인계와 실제 Kubernetes 다중 장치 종단은 남는다.
공개 STREAM501, M5 잔여 및 M8–M10 범위를 유지한다. V1–V24는 수정하지 않는다.
