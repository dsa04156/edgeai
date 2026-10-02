# M3 Workflow DAG / Run / Task / Attempt

2026-10-02. M3 구현·로컬·CI·클러스터 배포·실제 Ingress 검증을 완료했다.
전체 목표 M0–M10은 계속 진행 중이다. M3는 실행 요청 관리이며 실제 Runner 성공을 의미하지 않는다.

## 구현

- Workflow 4개, Run 4개, Task 2개 API와 한국어 Swagger(전체 27개 operation).
- 불변 DAG와 SERVICE Profile 참조, cycle/self/dangling/duplicate-input-port 검증.
- Flyway V4의 원자적 발행/seal, 하위 정의 불변성 및 동일 버전 FK.
- UUID Idempotency-Key로 Run/Task/root Attempt 원자적 생성. 취소 뒤 재전송도 같은 Run.
- Run 행 잠금과 task별 활성 Attempt partial UNIQUE. 작업 취소·하위 SKIPPED·독립 분기 보존.
- AUTO/NODE 요청 저장, root READY/QUEUED, 하위 WAITING. STREAM 실행은 501.
- /workflows의 등록·발행·버전 조회·실행 요청·Attempt·취소, PC/모바일 화면 및 lossless JSON.
- 계층형 controller/service/domain/repository, Basic/CSRF 및 고정 upstream 프록시 유지.

상세 계약은 [ADR 0004](../adr/0004-workflow-run-task.md),
[OpenAPI](../../contracts/openapi/platform-api.yaml), [검증 기준](../verification-matrix.md)을 따른다.

## 로컬 증거

원시 로그/JSON은 docs/evidence/runs/<testRunId>. Linux x86_64 / JDK21 / Node22 / PostgreSQL16.15.
기존 M0–M2 시험을 포함한 결과다.

| testRunId | 검증 | 결과 |
|---|---|---|
| 20261002T052141Z-882e5ab9 | 실제 PostgreSQL/Flyway 통합 19개, M3 6개 포함 | PASS/0 |
| 20261002T053028Z-0220c323 | 단위·MVC 25개 | PASS/0 |
| 20261002T053257Z-936d1f19 | 생성 타입·패키징 YAML byte 일치·계약 MVC | PASS/0 |
| 20261002T053256Z-4cfc72ed | UI lint/typecheck/build + 오프라인 PC/모바일 14개 | PASS/0 |
| 20261002T054130Z-7597df41 | 실제 HTTP·PC/모바일 8개 + DB 중단503·같은 앱 프로세스 복구 | PASS/0 |
| 20261002T054403Z-856619bf | 최종 HTTP smoke(Profile/Device/Workflow), CLI 범위 옵션 추가 후 | PASS/0 |

8개 동시 발행·실행 요청에서 생성 한 개와 정확한 Task/Attempt 수를 확인했다.
동일 실행 키에 다른 입력이 경쟁하면 한 내용만 생성되고 나머지는409다.
동시 Run/Task 취소, 독립 분기 유지, 취소 후 같은 실행 재전송, DB 불변 trigger/FK/활성 UNIQUE를 확인했다.
브라우저에서 큰 정수 9007199254740993 보존·새 실행 키·취소 확인과 가로 overflow 없음을 확인했다.
Workflow desktop/mobile 스크린샷을 직접 열어 레이아웃·상태 표시를 확인했다.
추가로 최종 Playwright 소스 typecheck와 전체 shell/Python 구문 검사를 통과했다.

## 발견·수정 기록

- 최초 단위 시험 20261002T052510Z-ea362335: 새 MVC 테스트에 보안용 test property가 없어 context 생성 실패.
  기존 MVC와 같은 test 전용 비밀번호 property를 추가한 뒤 통과했다.
- 최초 UI 20261002T052850Z-3ccd5d75: 존재하지 않는 ProfilePage 생성 타입 참조로 TypeScript 실패.
  실제 listProfiles operation response 타입으로 수정했다. 수집기는 exit2를 BLOCKED로 분류했지만 외부 차단이 아닌 컴파일 오류였다.
- 최초 브라우저 20261002T053545Z-9f2f8b10: 초기 JSON이 있는 textarea의 label 텍스트가 값까지 포함되어 locator 대기 실패.
  최소 HTML 재현에서 getByLabel=0/getByRole=1을 확인하고 textbox 접근성 이름으로 찾도록 수정했다.
  같은 실제 PC/모바일 흐름 재실행이 통과했다.

## 후속 검증 및 제한

M3 검증은 아래 CI·배포 증거까지 완료했다.
M4 PodSpec/Runner/Result, M5 retry/offload, M6 VD, M7 STREAM 및 M8–M10은 미완료다.
SERVICE spec의 실행 가능 이미지·자원·포트 호환성은 M4 소비 계약에서 검증한다.
Task SUCCEEDED 보호 시험은 명시적 DB fixture이며 실제 작업 실행 증거가 아니다.
기존 demo-workflow.sh / test-kind.sh는 M4 실제 실행 기준을 유지하며 아직 BLOCKED다.
단일 API/DB·개발 Basic 인증과 공유 클러스터 Ingress 상태 제한이 남아 있다.
LOCAL_VERIFIED / FULL_ACCEPTANCE를 주장하지 않는다.

## CI 및 실제 Kubernetes 배포

코드 `8d1ae08ab479aa680a244b418473219dd7906a6a`의
[Actions 36970385137](https://github.com/dsa04156/edgeai/actions/runs/36970385137):
scaffold/storage/images/gitops 모두 success. 내려받은 세 verification artifact의 result.json 9개가 모두 PASS/0이다.
CI PostgreSQL17에서 migration·통합 시험·PC/모바일8개·DB 장애/복구, MinIO 및 실제 이미지 HTTP 시험을 통과했다.

Actions의 `7a1614e`가 아래 이미지 digest를 Git에 기록했다. ArgoCD Application의 일반 refresh 후
자동 동기화·rollout을 확인했고 실제 Pod imageID가 일치한다.

- API: `sha256:ad2a43756b2689a42991866c6ab0ed9e29d410e5ae9c260dca39df12489c246a`
- Dashboard: `sha256:71adb522ad200e7372fdce564d0213e53f9aa18c5376c6db2efa37453bc599e1`

| testRunId | 배포 검증 | 결과 |
|---|---|---|
| 20261002T054857Z-6edbe4aa | 이전 M2 배포에서 --through device 데모 범위 호환성 | PASS/0 |
| 20261002T055519Z-2f49e2b9 | imageID 일치·API/UI/DB Ready·PVC5Gi Bound·Argo Synced | PASS/0 |
| 20261002T055519Z-c35bcc0a | 실제 Ingress Profile/Device/Workflow HTTP·CSRF·취소·정밀도 | PASS/0 |
| 20261002T055519Z-7f924d90 | 새 API의 HTTPS/CA/SA Node 관측10개 UID/metadata 대조·합성 연결/해제 | PASS/0 |
| 20261002T055555Z-bfc97a18 | 실제 사설 HTTP Ingress의 Workflow PC/모바일 2개, 요청 키 생성·발행·취소 | PASS/0 |

최초 상태 확인 20261002T055357Z-a0c5bcb0은 rollout 중 구·신 API Pod가 함께 있어 FAIL이었다.
rollout 종료 후 같은 상태 검증을 다시 수행해 통과했다. 검증 조건을 완화하지 않았다.
Argo aggregate health는 기존 공유 Traefik/Ingress status 문제로 Progressing이다.
실제 HTTP 성공·Ready·Synced와 구분하며 공유 인프라 설정은 변경하지 않았다.
