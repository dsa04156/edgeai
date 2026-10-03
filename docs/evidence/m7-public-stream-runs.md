# M7 공개 STREAM 실행·그룹 배정 검증

2026-10-03. 전체 M7 완료가 아닌 공개 실행 연결의 검증 기록이다. 기본 공개 STREAM 비활성 유지.
구현 계약은 [ADR0041](../adr/0041-public-stream-runs.md)이다.

| 검사 | 실행 ID | 확인한 범위 |
|---|---|---|
| 최초 단위시험 | `20261003T091503Z-8c6bcc01` | 새 서비스/저장소 컴파일 및 기존 단위 회귀 PASS |
| 공개 MVC·실제 PostgreSQL | `20261003T092851Z-e5aed1d8` | 신규12개 PASS, 동시 생성/claim 장벽/그룹 해제·실패·취소/세션 pin·불변 제약 |
| 전체 PostgreSQL | `20261003T093310Z-b05933e4` | 176개 PASS, 기존 BATCH/Remote/VD/스트림 회귀 포함 |
| 공개 실행→실제 TLS/S3/MQTT | `20261003T093904Z-78e23fc2` | 2개 PASS, 실제 scheduled route 준비·권한 발급·DeviceSource 계산·공동 허가·Runner 고정 S3 복원/Result 및 취소 |
| 전체 UI | `20261003T093904Z-8253eb62` | lint/type/build·38개 PC/모바일 PASS, 입력 재전송/오류·경로 페이지·출처·Next proxy 허용/거절 |

최종 코드에는 NODE 정책 검증과 기존 반응형 dl 행 구조를 포함한다.

| 최종 검사 | 실행 ID | 결과 |
|---|---|---|
| 전체 실제 PostgreSQL | `20261003T094231Z-c92ce48b` | 177개 PASS, 공개 Run13개 포함. AUTO/NODE claim 신원·REMOTE/VD/재시도 거절 |
| 최종 화면·Next proxy | `20261003T094233Z-cbc70513` | lint/build·타입 검사 및 영향 있는 PC/모바일4개 PASS, 스크린샷 직접 확인 |
| 전체 S3/DB 통합 | `20261003T094624Z-4351289b` | 32개 PASS, 공개 DeviceSource/Runner2개 및 기존 Result/Remote/VD/checkpoint 회귀 |
| 단위 회귀 | `20261003T094709Z-9824fdc3` | PASS |
| 계약·MVC·Swagger 자산 | `20261003T094727Z-5d46440c` | 5개 OpenAPI·MVC26개 PASS, 생성 타입과 패키징 YAML byte 일치 |
| 실제 API/UI/Swagger | `20261003T094850Z-6745c2c5` | PC/모바일10개 PASS·53.6초. 관리41/내부12 operation, 인증/CSRF, 실제 빈 경로 GET |

V1–V24는 커밋 원본 bytes와 같고, V25는 최초 격리 적용 이후 SHA-256을 유지한다.
실제 프로젝트 DB도 version25·checksum `-1766729663`·success=true를 확인했다.
최종 화면의 입력·경로 스크린샷4개는 `runs/20261003T094233Z-cbc70513/`에 보존했다.

## 증거가 의미하는 범위

`StreamRunIntegrationTest`는 실제 DB와 공개 MVC 인증·CSRF를 사용한다. public 입력으로 모든 Run,
Task, Attempt, route, pin, runtime 명령을 만든다. 동시에 보낸 같은 요청은1개 Run만 생성한다.
STREAM producer 하나만 claim하면 generation은0개이며, 모두 claim하고 worker가 재시작해도 route당1개다.
한 그룹의 한 작업에 BATCH 선행이 있으면 다른 구성원도 대기한다. 상태만 SUCCEEDED로 만든 fixture로는
시작하지 않으며 봉인된 Result가 생겨야 전체 그룹이 시작한다. 해당 시험의 Pod/저장소 receipt는 fixture다.
Device 교체, consumer 취소, 세대 회수 때 upstream peer와 후속 그룹은 정리되고 독립 분기는 유지된다.

`StreamSourceCompletionIntegrationTest`는 공개 Run MVC와 실제 Spring scheduler를 사용한다.
Run/route/runtime를 직접 SQL로 심거나 시험에서 generation을 수동 준비하던 경로를 제거했다.
두 DeviceSource의 실제 TLS MQTT→지속 계산→외부 checkpoint→공동 허가→현재 Runner의 새 빈 작업 폴더
복원→최종 파일14→실제 MinIO 고정 version 검증/Result를 확인한다. 취소 시 grant/Result를 만들지 않는다.
내부 Runner/Device 요청과 S3 전송은 실제 TLS다. 관리 Run 생성은 MockMvc다.
Kubernetes Pod 생성/신원과 완료한 peer의 경로 회수 시작은 여전히 명시적 fixture다.
따라서 다중 실제 Runner 간 데이터 전달·Kubernetes 전체 수용을 이 시험으로 대체하지 않는다.
후속 [다중 Runner DAG 시험](m7-stream-dag.md)은 실제 독립 Runner 사이의 STREAM 데이터와
BATCH 하위 실행을 추가했다. 위 단일 Runner 복구 시험의 경계와 실제 Kubernetes 잔여 범위는 유지한다.

## 검증 중 발견한 항목

- `092815Z-539c68be`: 시험에서 존재하지 않는 closeSession 메서드를 사용한 컴파일 오류.
  실제 Device release 경로로 비활성 원본을 만들어 고쳤다.
- `092925Z-92228605`: 기존 legacy metadata 시험이 새 public 생성 단계의501에 먼저 걸렸다.
  공개501/생성 없음과 직접 구성한 legacy Run의 dispatch501/Runtime 없음 두 경계를 각각 보존했다.
- `093653Z-3735d8e0`: 로컬 broker 실행 파일 환경이 없는 호출로 fixture 초기화가 실패했다.
  기존 로컬 Mosquitto/SDK 경로를 명시한 동일 시험은 `093904Z-78e23fc2`에서 통과했다.
- 모바일 경로 표의 상세 확장 시 좁아진 상태 열을 확인해 기존 history-list와 반응형 dl 행으로 변경했다.

## 선행 커밋의 CI·배포

ADR0040 source `e422b6ad1736ebea7b763ad0e38289038c72480a`의 CI37111365153은5jobs와17개
결과JSON이 PASS/0이다. Runner 컨테이너108개·TLS MQTT70개, 실제kind BATCH/Retry/Offload/
TLSRemote/VD, 고정 S3 결과20+5와 소유 cluster `edgeai-ci-f81c7ecc1de5` 삭제까지 확인했다.
GitOps `0ed9671ad5f5ce01e5002a1329e90f1c0fb084bf`, 배포 `20261003T093744Z-63ace2a5`에서 정확한
API/dashboard/MinIO imageID·Ready·PVCBound·ArgoSynced·VD활성화를 확인했다.
공유 Ingress로 aggregate health는Progressing이다. 이는 ADR0041 신규 코드의 CI·배포 근거가 아니다.
