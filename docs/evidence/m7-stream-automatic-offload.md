# M7 STREAM 그룹 자동 전환 검증

2026-10-04. ADR0052/V28. 공개 Run의 선택적 측정 정책과 그룹 체크포인트 전환을 연결한다.
이 문서는 구성 요소와 실제 Kubernetes 자동 전환의 검증 기록이다. 전체 M7 수용 완료를 뜻하지 않는다.

## 구현과 확인한 범위

- 공개 정책 생성/재전송/충돌, 혼합 DAG의 복구 불가 SERVICE 거절.
- 현재 체크포인트 누락·오래된 측정·자원 제한 누락·완료 허가 시 전환 보류.
- 모든 구성원의 시작/전환 대기와 자동 예산, 동시 판단의 단일 Operation, 방문 노드 제외.
- 취소 경합에서 새 Attempt 생성 차단, BATCH 하위 대기와 독립 분기 보존.
- V28의 정책/선택 구성원/다른 구성원 배치 위조 거절과 전체 삽입 rollback.
- 실제 Runner의 스트리밍 중 HTTPS 측정, 최종 처리까지 sequence 유지, 권한 거절과503 구분.
- 설정·그룹 구성원 배치·체크포인트·자동 판단 근거의 PC/모바일 표시.

## 실행 근거

원시 로그/JSON은 무시된 `docs/evidence/runs/<runId>/`에 보관한다.

| 검증 | runId | 결과와 범위 |
|---|---|---|
| 취소 경합 재현 | 20261003T173637Z-9cf73f09 | PASS, 원래 실패한 DB 시험1개 |
| 자동 정책8 + 기존 STREAM30/Offload16 | 20261003T174336Z-06167497 | PASS, 실제 PostgreSQL/MVC54개; Pod/broker/S3 receipt fixture |
| 실제 Runner/모델/HTTPS/TLS broker | 20261003T174501Z-dcd4236f | PASS,18개; 신규 측정3개 포함 |
| Runner 회귀 | 20261003T174642Z-3abac63e | PASS,111개; 컨테이너 자원 제한 검증은 CI 별도 |
| 화면 lint/type/build/브라우저 | 20261003T174642Z-f256c674 | PASS,40개; 자동 그룹 표시 PC/모바일 확인 |
| 자동 전환 실제 서버 단독 | 20261003T174900Z-4d471ffb | PASS, 실제 Spring/PG/MinIO/TLS MQTT/독립 Runner와 Device SDK; Pod 배정·신원·종료 관측 및 지연 파일 입력 fixture |
| 전체 실제 서버 연결 | 20261003T175029Z-b76dbcbe | PASS,8개; 자동·수동 그룹 전환·retry·최종 처리·취소 포함 |
| 전체 TLS MQTT/HTTPS 회귀 | 20261003T175030Z-e82f5124 | PASS,90개 |
| 최종 PostgreSQL 전체 | 20261003T175617Z-1e47f9cb | PASS,206개, 실패/오류/skip0; 명시적 opt-in 없는 Run의 DB 위조 거절 추가 포함 |
| 실제 저장소 전체 회귀 | 20261003T175805Z-0a91a34f | PASS,38개; Remote/VD/Result/STREAM 포함 |
| 서버 단위 | 20261003T180027Z-36103220 | PASS,105개, 실패/오류/skip0 |
| 최종 OpenAPI/생성 타입/Swagger YAML/MVC | 20261003T180331Z-783d42a1 | PASS,5개 계약·MVC26개·포장 YAML 일치 |

실제 서버 시험은 동일 Device owner의 센서 커서를 보존하고 외부 상태9를 새 Attempt에
인계한다. 이후 root14/sink23/BATCH37을 각각 고정 S3 버전·bytes·SHA와 비교한다. 지연
1200μs 입력은 제어된 시험 파일이며 실제 서비스의 성능 측정 결과로 해석하지 않는다.
해당 파일의 읽기/sequence/HTTPS 인증/서버 판단과 상태 인계는 실제 경로다.

## 실패와 판정 한계

- `20261003T172921Z-ba49187d`:52개 중 취소 fixture의 잘못된 fence 사유1개 실패.
  허용된 CANCELLED로 수정한 뒤 원래 시험 및54개 회귀를 통과했다.
- `20261003T173927Z-5a249991`:54개 중 새 DB guard 시험의 잘못된 digest 표현1개 실패.
  실제 sha256 접두사 계약으로 수정하고 정상 삽입/rollback 및5개 위조 거절을 확인했다.
- `20261003T174156Z-423b9339`:18개 중 새 시험2개의 결과 기대 형식이 잘못됐다.
  실제 JSON 결과 계약으로 수정한 뒤18개를 통과했다.
- `20261003T174613Z-83a4a278`:실제 서버8개 중 자동 전환 시험의 최초 checkpoint 조회에서
  일시적 CheckpointUnavailable1개. 단독 재실행은 통과했으나 외부 지연 원인은 미확정이다.
  검증 도구는 기존 제한 시간 안에서 재조회하도록 보완했다. 거절/fence 오류는 계속 실패한다.

위 로컬 판정 시점의 새 V28은 격리 DB에서 검증했다. 이후 실제 Kubernetes 검증은 아래를 따른다.
기존 MQTT 간헐 재연결/lease 문제의 원인이 이번 변경으로 해결됐다고 주장하지 않는다.
M5 잔여/M7–M10 전체 목표는 유지한다.

## 실제 메모리 부하와 Kubernetes 전환

`20261003T181929Z-71a54feb`는 새 자동 전환/취소2개·Runner Pod7개·고정 S3파일3개를 PASS/0으로
검증했다. API는638d77d의 현재 JAR이고 Runner는 같은 소스의 CI37142931811에서 컨테이너111개와
TLS MQTT90개 검증 후 게시한 `sha256:eabb42b73b41b4768550ac65df2cfc783fde013cb6dd8c46157488e672502376`이다.
전체 API 이미지 CI 완료와 이 증거를 구분한다.

참조 모델 프로세스가 첫 체크포인트 뒤320MiB를 실제 할당한다. Runner는 자신의 cgroup v2를 읽고
인증된 HTTPS로 측정값을 전송한다. 임계치는512MiB 제한의50%, 연속2개 표본이다. 시험에서 부하 전
관측값은 약44.9MB였고 전환 결정 표본은 약371.6/371.9MB였다. 측정 API/DB에 값을 주입하거나
cgroup 파일을 바꾸지 않는다. 실제 Pod의 제한과 판단에 포함된 연속 표본·시각·Attempt를 대조한다.

전체 그룹의 종료/권한 회수 장벽, 이전 producer4개의401, 새로운 AUTO 작업의 다른 Node 배치,
동료 작업의 기존 NODE 배치 유지, 고정 체크포인트2개 인계를 확인했다. Device SDK의 동일 owner와
센서 커서를 유지해 상태9에서 root14/sink23/BATCH37까지 재개하고 실제 S3 version·bytes·SHA를
대조했다. 대기 중 API Pod 교체14.881초 후 동일 Operation/구성원/결정이 유지됐다. 취소 사례는
대기 중 공개 취소로 새 Attempt 없이 종료됐다. 시험 소유 자원과 Job 대기 finalizer도 제거했다.

검증 도구의 기본 목록은 기존7개에 `offload-automatic`, `offload-automatic-cancel`을 더한9개다.
실행 중인 기존 배포·노드 설정은 건드리지 않으며, 고유 소유 자원·실제 Pod·TLS/S3/DB를 사용하는
검증 fixture다. 합성 정수 입력과 부하가 실제 장비·모델 정확도 또는 성능 수용을 대신하지 않는다.

추가 한도 시험의 최초 전체 실행 `20261003T182319Z-d1b73dde`는 기존7개를 통과하고 자동 전환 후
한도 검사의 부하 조건에서 실패했다. 복원 직후 입력이 없는 모델은 `Processor._begin()`에서
프로세스를 아직 시작하지 않아, 모델 내부 할당 신호만으로 부하를 만들 수 없었다. 실제 두 번째
데이터/체크포인트 이후 부하를 주도록 순서를 수정했다. 또한 기존 노드에 남은 동료 작업을 가압해
두 노드 클러스터에서도 아직 방문하지 않은 목적지가 존재하도록 한다.

수정 후 `20261003T183050Z-6d1e38b6`의 동일 자동 전환/취소2개는 PASS/0이다. 실제 DB에 수신된
표본2개와 Operation의 판단 근거가 일치한다. 동료 sink의 새 Attempt에서 warmup/cooldown 이후
연속3개 표본 약370.5/370.7/370.5MB를 확인했고, 방문하지 않은 Ready 목적지가 존재해도
Operation1개·Attempt2개를 유지했다. API 교체10.052초·다른 노드 전환·체크포인트2개·늦은
producer 차단·취소와 Pod7개/S3결과3개를 다시 검증했다.

최종 전체 실행 `20261003T183338Z-5496a478`은9개 시나리오·Runner Pod31개·고정 S3파일18개
PASS/0이다. AUTO/NODE·API 교체·계산 중 그룹 복구·최종 처리 복구·취소·수동/자동 그룹 전환과
각 전환의 취소를 포함한다. 새 자동 판단 표본과 실제 DB 수신 표본2개 일치, 다른 노드 배치와
동료 배치 보존, 고정 체크포인트2개 인계, API 재시작 후 동일 Operation/결정, 이동 가능 노드가 있는
동료의 재부하3개 표본/전환 한도, 이전 producer8개의401 및 소유 자원 정리를 확인했다.
처음 실패한 실행의8개 Run과 fixture 자원·Job/Pod/Secret/대기 finalizer가 남지 않은 것도 재조회했다.
원시 보고서는 각 실행 폴더의 `stream-kubernetes.json`에 함께 보관한다.

## CI·게시 이미지·실제 배포

638d77d CI37142931811은5개 job 모두 success다. `20261003T184641Z-efbfb547`에서 다운로드한
원시 결과17개 PASS/0, PostgreSQL206개 실패/오류/skip0, 컨테이너 Runner111개·TLS MQTT90개를
확인했다. 실제 kind `20261003T182006Z-1abf66a4`는 기존 BATCH/Remote22Run/S3결과20개,
VD4개/실제 Task/S35개, STREAM7개/Pod24개/S315개, 영속 TLS broker의 새 Pod/동일PVC·권한 유지,
렌더링한 TLS MinIO256KiB/익명403, 실제 배포 데모3개/Pod8개/S36개를 통과했다.
생성한 `edgeai-ci-da88eafd7ae7`만 삭제했다. 이 CI의 STREAM 목록은 기존7개이며 이번 기본9개
변경의 CI 수용과 구분한다. 요약과6개 kind 보고서는 감사 실행 폴더에 함께 보관한다.

GitOps `f869da5386a80abc90bfee570e97d324854e7584`의 실제 배포는
`20261003T184641Z-7ebc102f`에서 정확한 API/dashboard/MinIO imageID·Ready·PVCBound·ArgoSynced를
통과했다. DB V28 success/checksum `-423966927`, 실패 migration0개와 기존 소스 bytes 불변을 확인했다.
`20261003T184839Z-12c00400`은 원래10개 고정 파일의 bytes/SHA/metadata와 PostgreSQL/MinIO의
두PVC UID 보존을 확인했다. 공유 Ingress로 인한 Argo aggregate health Progressing은 기존 상태다.

게시된 API `sha256:efef59865dcd2a7e77d09f7f467ee6758f22dc3d9a28b8bb284e221e6dc647b8`와 위
Runner digest를 사용한 `20261003T184641Z-a6fc3e70`도 자동 전환/취소2개·Pod7개/S33개를 PASS/0으로
검증했다. 이미지에 포장된 JAR를 그대로 실행했으며 별도 JAR를 주입하지 않았다. JAR SHA256은
`cf8bae8c1405adc483bb4c0e595487c2939056765eb4338d43637eded10493d6`으로 전체9개에 사용한 JAR와
일치한다. 대기 중 API 교체는13.289초였다. 실제 메모리/판단·
수신 표본 일치·그룹 종료/회수·다른 노드·동료 배치/전환 한도·API 교체·checkpoint/결과·이전
producer401·취소/정리를 다시 확인했다. 새9개 기본 게이트의 CI와 단계별 배치·VD/REMOTE 스트림,
외부 장치/모델·M5 잔여 및 M7–M10 전체 수용은 남는다.
