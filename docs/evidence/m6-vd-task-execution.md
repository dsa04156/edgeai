# M6 VD 작업 배정·실행·결과 연결

2026-10-03 KST. ADR0020의 실행 연결이다. M5 잔여·M7–M10과 전체 플랫폼 완료 판정은 별도다.

## 연결한 동작

- 공개 VD Run은 동일 namespace의 Ready VD, 동일 SERVICE 버전과 엄격한 vdId 입력을 검사한다.
  요청 키의 재전송, retry와 하위 BATCH 대상, Run/Attempt 조회에 고정 vdId를 유지한다.
- poll 배정·완료 확인·receipt는 VD → Run 잠금과 하나의 DB 트랜잭션으로 처리한다.
  정확한 재전송에는 기존 배정만 반환하며 다른 작업·epoch·세대·session·Pod 보고를 거절한다.
- child Runner는 Task HMAC과 실제 VD Pod-bound 신원으로 인증한다. Job 신원을 만들지 않는다.
  claim/upload/commit/telemetry는 상태·lease·drain deadline을 확인하며 S3 검증 뒤 다시 검사한다.
- Result는 고정 버전 파일의 실제 크기/내용 hash를 검증하고 allocation의 vdRuntimeId/Pod에 결합한다.
  Result 확정·취소 요청은 slot을 반환하지 않는다. 종료 보고 또는 실제 Pod 종료를 기다린다.
- 응답 유실 뒤 미시작 취소는 다음 순번의 active/completed 부재와 미claim을 확인해야 닫힌다.
  supervisor는 빈 DRAIN에서도 STOP까지 poll을 계속한다. lease/drain deadline은 계속 적용한다.
- 배정 대기·실행·VD lease 만료를 worker가 검사하며 이전 종료 확인 후에만 retry한다.
  한 자식 취소가 공유 VD Pod를 삭제하지 않는다. VD 자원 측정은 작업별 자동 offload에 사용하지 않는다.
- Swagger/생성 타입과 Workflow 화면에 VD 정책, 공유 측정 설명, 대상·결과 실행 세대를 표시한다.

## 로컬 검증

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 초기 단위82개 | 20261002T192512Z-998496be | PASS/0 |
| Task 교환 포함 실제 PostgreSQL135개 | 20261002T193339Z-38d97068 | PASS/0, skip0 |
| 공개 VD Run 포함 실제 PostgreSQL136개 | 20261002T194641Z-b03db31a | PASS/0, skip0 |
| Python Runner28개, 실제 자식 프로세스·종료 대기 회귀 | 20261002T193208Z-5ed6569a | PASS/0 |
| 공개/내부 계약4개·타입·JAR YAML·MVC25개 | 20261002T194047Z-64c4bfc6 | PASS/0 |
| UI lint·typecheck·build·PC/모바일32개 | 20261002T194500Z-cb024468 | PASS/0 |
| 실제 Python VD/Runner·HTTP·S3·PostgreSQL2개 + 기존14개 | 20261002T194722Z-7921adcd | PASS/0, skip0 |
| 최종 단위82개 | 20261002T200158Z-ee581287 | PASS/0 |
| 실제 API/DB PC·모바일10개·Swagger39·DB 장애503/복구 | 20261002T195553Z-5fe1b1f1 | PASS/0 |
| VD 선택·재전송·결과 화면 PC/모바일2개 | 20261002T200200Z-939513e3 | PASS/0, API fixture |
| 실제 Kubernetes VD 수명/Task4개·S3 파일5개 | 20261002T195417Z-ea2a6d9b | PASS/0 |

VDTaskIntegrationTest의12개는 실제 PostgreSQL과 HTTP security를 사용한다. Pod 신원·S3 receipt는
명시적 fixture다. 동시 같은 poll4개/2slots, 완료·취소·드레인, 유실 응답의 취소/미시작 확인,
결과 검증 도중 취소, VD lease·Pod 장애와 종료 뒤 retry, queued 취소/만료, 위조 보고 롤백과
공개 API의 인증/CSRF·대상 SERVICE/준비 조건을 검증한다.

VDTaskArtifactIntegrationTest의2개는 실제 Python supervisor와 자식 Runner, Spring HTTP,
PostgreSQL 및 MinIO 고정 버전 파일을 사용한다. 프록시가 첫 배정·첫 성공 commit 응답을503으로
유실시켜 재전송을 유도하며 DAG3개 결과·각 Attempt1개·실제 내용 score8·VD 생산자·종료/정리를
확인한다. 별도 시험은 실제 느린 자식을 취소하고 다른 자식의 결과와 공유 supervisor 생존을 확인한다.
Pod/Node 신원 및 readiness는 이 로컬 시험에서 fixture이고 실장비 모델 수용시험이 아니다.

최종 화면 시험의 PC·모바일 스크린샷을 직접 확인했고 해당 evidence의 screenshots에 보존했다.
VD 대상·Attempt·VD Runtime/Pod·공유 자원 설명을 확인하며 가로 overflow가 없다.
실제 API/DB 브라우저에서는 VD가 비활성인 환경의 요청 본문·503 오류도 확인한다.

## 실제 Kubernetes 작업 수용

`test-vd-kubernetes.py`는 기존 배포와 분리한 임시 API/빈 PostgreSQL/MinIO Pod를 사용한다.
빈 DB에 V1–V18을 적용했으며 현재 JAR SHA-256은
`3ad4c64a2a843ab06ecd26e88c7ed506b71f04e8670b10d493648c31bbcd6dcc`다.
기존 API 이미지의 JRE에 이 JAR를 넣어 시험했다. Runner는 기존 고정 이미지이며 새 이미지
전체의 수용은 후속 CI가 담당한다. 상세 `vd-kubernetes.json`은 위 실행 evidence에 보존했다.

- AUTO/API Pod 재생성13.245초, NODE, 실제 Unschedulable→STARTUP_TIMEOUT을 통과했다.
- 실제 자식 두 개 실행 중 API Pod 재생성11.795초 뒤 같은 VD 세대/Pod에서 DAG3개를 완료했다.
  하위 작업은 선행 작업의 고정 버전 결과를 사용했다.
- 작업 실행 중 VD 교체는 기존 세대의 결과·물리 종료 뒤 다음 세대를 시작했다.
- 느린 자식 하나를 취소하고 다른 자식의 성공·공유 Pod 생존을 확인했다.
- 잘못된 입력의 실패→새 Attempt 재시도→예산 소진과 결과 부재를 확인했다.
- 고정 버전 S3 결과5개를 별도로 다운로드해 실제 내용·SHA-256·크기를 검증했다.
  마지막 drain 뒤 네 시나리오의 자원이0개이고, 임시 API/DB/MinIO/Service/Secret도 정리했다.
  두 namespace의 시험 소유 label을 별도 조회해 잔여0개를 재확인했다.

합성 계산 workload의 플랫폼 경로 검증이며 외부 실제 모델·장비·성능 수용을 대신하지 않는다.

## 재현한 실패와 보정

빈 DRAIN에서 supervisor가 바로 종료하여 다음 보고가 없던 회귀는
`20261002T193129Z-cb75a69f`에서 순번[0]으로 재현했다. STOP까지 대기한 최종 Runner28개가 통과했다.

V17 적용 후 `20261002T193139Z-6a621250`은135개 중3개가 실패했다. PostgreSQL이 cross-column
sequence CHECK를 `vd_task_allocation_check`, 기존 closure를 `_check1`로 생성했는데 V17이
잘못된 이름을 대상으로 했다. 적용된 V17은 보존하고 V18에서 실제 closure를 제거하고 명시적으로
이름 붙인 sequence 범위를 복구했다. 미시작의 같은 순번/exitCode 거절 및 최종136개가 통과했고,
실제 pg_constraint 정의와 Flyway V16/17/18의 success를 조회했다.

공개 인증 시험에서 CSRF 없는 POST의403을401로 예상한 실패
`20261002T194500Z-bf948760`은 CSRF를 제공한 비인증 요청으로 고쳤다. 실제 화면의 label
선택자 실패 `20261002T195322Z-2eebc2f6`은 접근 가능한 combobox 이름으로 수정했다.
이후 해당 PostgreSQL136개와 실제 브라우저10개가 각각 통과했다.

적용한 파일은 수정하지 않는다. SHA-256:
- V16: `5686bc81fe11522477fa5696c83bf7c5f46b36667aa455a6685e6702717ee191`
- V17: `f37be3addaf6d4fc188665e91beb42351ea433eee21c1c1ec15e9f2db49a7cbd`
- V18: `2e63e02e6b48b2ee8103446de171bc69abb2625883e1d500f207f3c623d49465`

## 수용 게이트

실제 Kubernetes Task 수용과 실제 API/DB 화면 검증에 이어 아래 새 이미지 CI·실제 배포
검증도 통과했다. M6 요구사항별 판정은 [완료 감사](m6-completion-audit.md)를 따른다.

## 소스 push와 CI 완료

구현 소스 `2ec9462052c3e9fbdd043cf3c29a05f3a63668ed`를 main에 push했다.
[CI37059110890](https://github.com/dsa04156/edgeai/actions/runs/37059110890)의5개 job은 모두
success다. 내려받은 result.json15개는 모두PASS/0이며 실제 PG136개/skip0, 컨테이너 Runner28개,
UI34개, 실제 API/DB 브라우저10개와 Swagger도 로그에서 확인했다.
새 이미지의 실제 kind는 기존 Run22개/S3결과20개와 VD4개 수명/작업 시나리오를 통과했다.
`kind-vd.json`의 taskExecution=true, VD 작업 Run4개와 고정 S3결과5개, 각 시험 자원0개를
확인했다. API Pod 재생성은 idle11.102초/작업 중9.945초이며 같은 VD 세대·작업을 유지했다.
GitOps pin6ee527d가 위 소스의 검증된 digest를 기록했다. 개발 overlay의 명시적 VD 활성화
patch는 서버 dry-run `20261002T201750Z-6b2923b7` 및 최종 pin의
`20261002T204447Z-177cbc5c` PASS 후 c2862a7로 push했다. 로컬 기본값은 계속 비활성이다.

## 실제 GitOps 배포와 결과 화면

| 검사 | 실행 ID | 결과 |
|---|---|---|
| c2862a7 Argo revision·실제 imageID·Ready·VD 활성화 | 20261002T204538Z-b137ac0f | PASS/0 |
| 배포 API/Runner·VD4조건·S3 결과5개·물리 정리 | 20261002T204808Z-37ef820e | PASS/0 |
| 배포된 실제 VD Run/Result PC·모바일 화면 | 20261002T205319Z-ea6cf390 | PASS/0 |

API/dashboard/MinIO의 실제 imageID와 source2ec9462의 pin이 일치하고 Pod Ready/PVC Bound,
Argo Synced 및 실제 Ready API Pod의 VD 활성화를 확인했다. 기존 공유 Ingress status로
Argo aggregate health는 Progressing이다. Healthy 또는 전체 운영 수용으로 판정하지 않는다.

기존 클러스터의 VD 데모는 공개 화면 프록시를 통해 Run을 실행했다. 내부 producer 차단 검사는
별도 API 포트포워딩을 사용했다. 새 Runner digest는 CI와 동일하며 AUTO/NODE·교체·drain·
시작 실패와 실제 DAG/Result·개별 취소·실패/재시도·고정 S3 파일5개를 확인했다.
네 시나리오의 소유 Pod/Secret/Job 잔여0개를 별도 조회했고 시험용 포트포워딩도 종료했다.
이 배포 데모에는 API 재시작 callback을 제공하지 않아 보고서 restart는 null이다.
기존 로그 한 줄의 무조건적인 API restart 표시는 후속 수정했다. 재시작 증거는 위 격리 시험과 CI kind다.

실제 성공 Run의 VD·Result·runtime·Pod·파일 크기/SHA/version 및 공유 측정 설명이 PC/모바일에
일치했다. 가로 overflow와 브라우저 저장소의 인증정보 저장이 없음을 확인하고 두 스크린샷을
직접 검토했다. vd-deployed.json과 actual-result.json, PC/모바일 PNG는 각 실행 evidence에 보존한다.
