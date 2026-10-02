# M5 Remote 자동 worker와 공개 실행 선택 — 로컬 검증

ADR0012/V12의 범위다. 공개 REMOTE Run/Offload 요청, 고정 제공자 binding, RemoteWorker,
직접 S3 파일 전송과 PC/모바일 화면을 연결했다. M0–M4 완료 판정은 유지하며 M5는 진행 중이다.

## 확인한 결과

| testRunId (2026-10-02) | 범위와 결과 |
|---|---|
| 20261002T131004Z-721e0104 | 단위/MVC60개. 제공자 설정/비활성·토큰 회전·binding 검사 포함. PASS/0 |
| 20261002T125810Z-897028e1 | 실제 PostgreSQL80개. V12 적용, 기존 Kube/Remote lifecycle 회귀. PASS/0 |
| 20261002T131634Z-a932fbf4 | 실제 PostgreSQL/MinIO/참조 제공자14개. Worker11개+기존 결과3개. PASS/0 |
| 20261002T132412Z-1f512356 | OpenAPI3개 생성·MVC19개·생성 타입/패키징 계약 일치. PASS/0 |
| 20261002T132412Z-32b1fac7 | Dashboard lint/typecheck/build·PC/모바일26개. PASS/0 |
| 20261002T132448Z-5fdba197 | 실제 API/DB PC·모바일8개·Swagger·DB 장애503와 동일 앱 프로세스 복구. PASS/0 |

PostgreSQL80, worker/storage14, MVC19의 JUnit failures/errors/skipped는 모두0이다.
PC/모바일 Remote 요청·실행·전환 화면을 확인했고 가로 넘침도 없다. UI 시험은 HTTP 응답 fixture를
사용하며 실제 서버/파일 검증은 별도 아래 시험이 담당한다. 생성 API 타입은 원본 OpenAPI에서 재생성했다.
Result manifestDigest의 기존 문서 패턴도 실제 DB 계약인 `sha256:` 접두사에 맞췄다.

재현: `test-unit.sh`, `test-integration.sh`, `test-runtime-results.sh`, `test-contract.sh`,
`test-ui.sh`, `test-profiles-stack.sh local`. 실제 MinIO는 소스 빌드 바이너리를 소유한 loopback 서버로
실행했다. 시험이 만든 제공자 프로세스·임시 파일·버킷은 종료/제거했고 프로젝트 PostgreSQL은 유지했다.

## 실제 공개 HTTP·제공자·S3 경로

- 실제 Spring Boot HTTP의 Basic/CSRF와 공개 Run 생성·재전송·조회·취소·전환 API를 사용한다.
  별도 Python/SQLite 제공자와 실제 MinIO/PostgreSQL이 데이터 경로에 있다.
- 공개 REMOTE BATCH가 root와 child에서 score8.0을 계산한다. 하위 작업은 자기 parameters보다
  확정된 선행 object version의 실제 bytes를 사용한다. 두 Result 모두 RemoteAllocation을 가지며 Pod UID는 없다.
- worker를 새 인스턴스로 교체하면서 DB 명령을 처리해도 완료한다. 별도 시험은 Spring의 실제
  ScheduledAnnotationBeanPostProcessor/ThreadPoolTaskScheduler로 worker를 실행하며 공개 요청 뒤
  직접 worker 메서드를 호출하지 않고 BATCH 완료를 기다린다. 전체 API 프로세스 재시작 시험은 아니다.
- 제공자 프로세스를 SIGKILL하고 같은 SQLite/포트로 재시작하면 RUNTIME_LOST 재시도에서 새 Attempt2와
  새 allocation으로 완료한다. provider binding과 Task ID는 유지한다.
- CREATE 전 취소는 실제 tombstone을 남기고 작업/하위 실행을 시작하지 않는다. 실제 S3 업로드 뒤
  commit 전 취소는 Result와 하위 BATCH 해제를 막는다.
- 고정 origin과 다른 설정의 worker는 새 제공자 I/O를 하지 않는다. 원래 설정 복원 후 완료하고,
  같은 Idempotency-Key 재전송은 기존 Run을 반환한다.
- 실제 제공자 출력 파일을 변조하면 OUTPUT_INVALID로 실패하며 Result를 만들지 않는다.
  직접 S3 다운로드는 잘못된 SHA의 파일을 공개하지 않고 기존 destination을 덮어쓰지 않는다.
  업로드는 symlink/변조된 bytes를 거절한다. Runner용 URL은 고의로 도달 불가하게 설정했어도 성공한다.
- 동시8개 명령 worker와8개 관측 worker가 하나의 Result를 확정한다. 실제 제공자 SQLite의 executions도1이다.
- 공개 NODE→REMOTE 전환은 source 종료 확인 전 새 Attempt를 만들지 않고 실제 Remote 결과까지 완료한다.
  REMOTE→NODE도 실제 취소 종료 뒤 새 Attempt/claim으로 Operation이 성공한다. 이 두 시험의
  Kubernetes 신원·종료·claim은 명시적 fixture이며 실제 Kubernetes Runner 양방향 전환 증거가 아니다.

## 발견한 실패와 수정

- 20261002T125506Z-a7730255: MinIO SDK stream 인자의 Long 타입 컴파일 오류. `-1L`로 수정했다.
- 20261002T130354Z-af545231: provider digest에 `sha256:` 접두사를 두 번 붙여 실제 Spring context가 실패했다.
  JsonDocuments가 반환하는 표준 digest를 그대로 사용한 뒤 통과했다.
- 20261002T130506Z-c34f486c: 전환 fixture가 먼저 쌓인 다른 Kubernetes CREATE를 집어 시험1개가 실패했다.
  테스트의 고유 namespace에서 자신의 합성 Kubernetes 명령만 소진하도록 수정했다.
- 20261002T131003Z-a4cd9c7b: 드롭다운 label 선택자에 option 텍스트가 포함되어 UI6개가 실패했다.
  실제 접근성 역할/이름에 맞게 combobox 선택자를 수정했다. 제품 접근성 이름은 유지했다.

수정 후 저장소11개→스케줄러/파일 검증13개→동시성 포함14개가 차례로 통과했다. 최신 결과는 위 표다.

## CI·배포 및 남은 게이트

이전 source6009136의 CI37008176219와 실제75c6632 이미지 pin 확인은
[영속 Remote 연결 기록](m5-remote-runtime.md)에 보존한다. 현재 worker 코드의 새 CI·이미지·배포는
다음 확인 대상이며 이전 성공을 새 코드 성공으로 취급하지 않는다. Remote는 배포 기본 비활성이다.

실제 kind Kubernetes↔Remote 전환, API 프로세스 재시작 중 명령 복구·취소, 외부 endpoint/auth/API 계약과
상태형 복원은 남는다. 현재 제공자 하나의 참조 프로토콜·SYNTHETIC 계산 검증이며 실제 OCI/모델/장비
수용이나 성능/대규모 부하 증거가 아니다. 장시간 전송 lease와 미확정 S3 version/강제 종료 임시 파일
정리는 추가 검증·운영 과제다. 전체 플랫폼 LOCAL_VERIFIED/FULL_ACCEPTANCE로 판정하지 않는다.
