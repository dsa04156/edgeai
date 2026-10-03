# M7 BATCH 작업별 VD·Remote 혼합 배치

2026-10-04. ADR0054/V30. 아래 로컬·실제 Kubernetes 검증과 abf6bfd의 이미지·CI·배포를 통과했다.
후속 Remote 혼합3개 CI·배포도 아래에서 확인했다. 실제 외부 시스템 수용과 STREAM VD/REMOTE·M7 전체
완료를 주장하지 않는다.

## 후속 CI·배포 확인

소스 `abf6bfd573b626d8f195ec6a5eb5233e1ce11b07`의 CI37151914101은5jobs 모두 success다.
다운로드한 원시17개 PASS와 PG214·Runner111·TLS MQTT90·기존 kind22Run/S320·VD5/S38·
STREAM11/Pod39/S324·영속 broker 교체·TLS 저장소·배포 데모3개/Pod8/S36을 대조했다.
감사 summary는 `20261003T211258Z-1187337c/ci-audit-summary.json`에 보존했다.

GitOps `720203b5448c28d3e2770e90bdf08f512efb2fb1`의 실제 API/dashboard/MinIO imageID,
Ready·PVC Bound·Argo Synced를 `20261003T211258Z-1187337c`에서 확인했다. 원래10개 고정
S3파일과 PostgreSQL/MinIO 두PVC UID 보존은 `20261003T211350Z-0ef63124`에서 확인했다.
공유Ingress로 인한 aggregate health Progressing은 유지한다.

이후 실제 Remote 혼합3개 kind 게이트를 GitOps 위로 재배치했다.
소스 `0d31eb8b737de6a995b91c3cb1d715b76330ea75`의 CI37154396986은5jobs/원시17개 PASS다.
혼합Remote3개/S35·PG214·기존kind22Run/S320·VD5/S38·STREAM11/Pod39/S324 및 영속TLS/
배포데모까지 대조했다(220028Z-1e19bb71). GitOps8b17e24의 실제3imageID·Ready/PVCBound/
ArgoSynced(215910Z-ab971f5e), 기존10파일/두PVC 보존(215911Z-fa4598d5)도 PASS다.
기존 공유Ingress aggregate Progressing은 유지한다. 후속 ADR0055 VD STREAM의 새 CI/배포는 별도다.
아래 최초 로컬 구현 기록의 새 CI/배포 대기는 위 두 소스 범위에서 해소됐다.
ADR0055의 VD STREAM 변경에는 해당 완료 판정을 적용하지 않는다.

## 연결한 계약

Run의 기본 정책과 작업별 AUTO/NODE/VD/REMOTE 최초 대상을 분리한다. 대기 Task에도
`initialVdId`/`initialRemoteTarget`을 고정하고 INITIAL Attempt가 이를 따르도록 DB에서 확인한다.
VD SERVICE 검증은 해당 VD에 배치한 작업만 비교한다. Remote의 endpoint/자격은 노출하지 않고
provider key/configuration digest/sourceMode를 고정한다. 재전송·재시도·전환의 기존 의미를 유지한다.

Run 상태/취소/하위 준비는 Run만 잠근다. 생산자·slot 작업은 자기 VD → Run 순서를 유지한다.
VD 행의 `FOR NO KEY UPDATE`는 변경을 직렬화하면서 불변 ID 외래 키 참조를 허용한다.
관련 저장소와 DB trigger를 함께 변경했다. 실제 poll/claim/결과/취소 경합과, VD B를 다른
트랜잭션에서 잠근 동안 VD A의 결과가 B의 하위 작업을 준비하는 경우를 시험했다.

## 실행 근거

원시 자료는 무시된 `docs/evidence/runs/<runId>/`에 보관한다.

| 검증 | runId | 확인한 범위 |
|---|---|---|
| 혼합 VD·기존 영속 제약·공개 Run | 20261003T200649Z-cb7e43fc | PASS, 실제 PG32개. 두 VD의 다른 SERVICE·대기 대상·동시 poll·개별 취소/결과·slot 반환·재전송. Pod/S3 경계는 fixture |
| PostgreSQL 전체 회귀 | 20261003T201455Z-f5ab8379 | PASS,214개, 실패/오류/skip0 |
| 실제 저장소 전체 | 20261003T201018Z-d854429d | PASS,40개. 새 NODE fixture→실제 Remote 및 실제 Remote→NODE fixture, 실제 Spring/PG/MinIO/참조 Python 제공자·고정 S3 입력/결과. Kubernetes 경계는 fixture |
| 서버 단위·실행 JAR | 20261003T201709Z-532f2376 | PASS,단위105개 및 bootJar |
| 화면 lint/type/build·PC/모바일 | 20261003T201019Z-bd569ec3 | PASS,42개. 혼합 요청·기본 정책 변경 시 작업별 선택 보존·대기 VD/Remote 표시·새 버전 초기화. HTTP fixture |
| OpenAPI·생성 타입·서버 계약 | 20261003T201759Z-36c1ca92 | PASS,계약5개/MVC26개. 제공 YAML과 생성 타입 일치 |
| 실제 API/DB·PC/모바일·Swagger | 20261003T202502Z-ea86ef80 | PASS,10개. 혼합 배치의 한국어 설명·최초 대상 필드·STREAM 제한 표시, 실제 CSRF 발행 |
| 기존 DB V29→V30 업그레이드 | 20261003T202632Z-18d31194 | PASS,기존 Task9,359개의 신원·최초 실행 대상 보존, 성공한 migration30개 |
| 실제 Kubernetes VD·혼합 DAG | 20261003T201757Z-789c7d16 | PASS,5개/고정 S3결과8개. 서로 다른 VD→NODE→VD의 실제 생산자·지정 Node UID·API 교체·결과3개·소유 자원 정리 |
| 실제 Kubernetes·Remote 최초 혼합 배치 | 20261003T204108Z-330b498b | PASS,3개/고정 S3결과5개. NODE→REMOTE→NODE·REMOTE→AUTO·혼합 Remote 취소, 실제 TLS 제공자·Pod·S3 입력/결과·API 교체·단일 계산·소유 자원 정리 |

PC/모바일의 새 혼합 입력 및 대기 대상 화면을 원시 디렉터리에 보관했고 실제 이미지를 확인했다.
실제 Kubernetes 시험은 현재 JAR와 이미 검증된 Runner digest를 사용했다. 혼합 DAG 도중 API Pod를
8.416초에 교체했고 DB Pod·두 VD의 실행 세대/Pod·고정 대상과 결과를 유지했다. 공유 배포는
변경하지 않았으며 격리 API/DB/서비스/Secret을 UID 소유 확인 후 정리했다. 이 새 혼합 시나리오를
kind CI에 연결했다. 아직 새 소스의 이미지·CI·공유 배포 완료 판정은 아니다.

검증한 JAR SHA-256은 `32f53705cf33661cd4aac82a52990660b19081f54a73f09404c122480a8d3c04`다.
업그레이드 전후 기존 Task9,359개의 전체 신원·최초 대상 스냅샷을 비교했다. 로컬 V30 Flyway
checksum은 `1571392355`다. 비공개 snapshot은 로컬에만 보관한다.

## 후속 실제 Remote 혼합 실행

`scripts/test-vd-kubernetes.py --context <context> --mixed-remote`는 현재 JAR를 쓰는 격리 API/DB/S3와
독립 TLS 참조 제공자 Pod로 새 `mixed_remote_acceptance.py`를 실행한다. 기존 공유 배포나 노드의
스케줄링 상태를 바꾸지 않는다. 제공자 상태 볼륨은 API 재시작과 독립적이며 실제 제공자 재시작·
외부 장비 계약 수용은 이 시험에 포함하지 않는다.

- AUTO 기본 Run의 NODE→REMOTE→NODE: 기다리는 작업의 최초 대상을 고정하고 실제 다른
  생산자의 결과를 BATCH 입력으로 전달한다. Remote 실행 중 API를 교체해 같은 Attempt/allocation과
  계산1회를 보존한다. Remote가 받은 파일의 크기/SHA와 Node가 받은 고정 S3 version을 각각 대조한다.
- REMOTE 기본 Run의 REMOTE→AUTO: 하위 AUTO 작업이 기본 Remote binding을 물려받지 않고
  실제 scheduler/Pod에서 실행된다. 선행 Remote 결과의 고정 S3 입력과 실제 Pod 생산자를 확인한다.
- NODE 기본 Run의 Remote root 취소: 취소 뒤 API를 교체해 실제 제공자의 CANCELLED·계산1회,
  하위 Attempt/Result 부재와 Node Job 미생성·자원 정리를 확인한다.

실제 Node Pod3개와 고정 S3파일5개가 계산값8/features[2,1]에 일치했다. 이 새3개는 kind의 기존
실행/VD/STREAM 게이트에 추가했고 성공·실패 모두 `.tools/kind-mixed-remote.json`을 artifact로
보존한다. 위 로컬 JAR 결과와 새 이미지/CI/공유 배포 판정은 구분한다.

V30을 최초 격리 DB에 적용한 후 SHA-256은
`5cd128c280daecc410e86305d0a61036e11fa09de9e0132a5dd82575b9c09835`이다.
이후 변경하지 않으며 V1–V29도 유지한다.

## 시험 중 수정한 경계

- `200251Z-7a5511ab`: 새 시험이 JSON/Result helper의 실제 메서드 이름과 달라 컴파일 실패했다.
  기존 `decode`/`outputs` API로 맞췄다. DB 적용 전 실패다.
- `200325Z-d8aa41ce`: 기존30개 통과, 새2개는 기존 내부 `/commit` 대신 없는 `/results`를 호출해401이었다.
  기존 Runner 경로로 수정했다. V30 최초 적용은 이 격리 DB에서 성공했다.
- `200454Z-13df3f9b`: 새 BATCH 입력의 S3 download fixture가 빠졌고, 실제 프로세스 종료 전
  취소 상태를 CANCELLED로 잘못 기대했다. download grant를 명시적 fixture로 제공하고
  CANCELLING→인증된 종료 poll→CANCELLED와 slot 반환을 검증했다. 최종32개가 통과했다.

선행 작업별 AUTO/NODE 코드 `fe32ed8`의 CI37149032705와 이 혼합 배치 변경은 구분한다.
해당 CI5jobs/원시17개와 GitOpsaabb815의 실제 imageID·Ready/PVCBound/ArgoSynced·V29 적용,
기존10파일/두PVC 보존은 통과했다. 이 혼합 V30 변경의 새 이미지·배포 증거가 아니다.
저장소40개 시험의 Kubernetes 부분은 fixture이며 후속 실제 Kubernetes↔Remote3개와 구분한다.
실제 외부 제공자·실장비, STREAM VD/REMOTE 및
M5 잔여/M8–M10은 남아 있다. VD STREAM 구성 요소 후속은 [별도 근거](m7-vd-stream-execution.md)를 따른다.
