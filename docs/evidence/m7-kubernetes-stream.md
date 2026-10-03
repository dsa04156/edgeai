# M7 Kubernetes TLS·스트림 수용 진행 기록

2026-10-03. [ADR0042](../adr/0042-runtime-tls-trust.md). 실제 Kubernetes 전체 DAG 통과는 아직 미확정이다.

## 구현

`scripts/test-stream-kubernetes.sh <context>`는 현재 API JAR를 빌드한 뒤 소유 label과 UID를
확인하는 격리 API/DB/MinIO/Mosquitto/DeviceSource Pod 및 Service를 만든다. API는 native HTTPS,
MinIO는 native HTTPS, MQTT는 TLS와 Dynamic Security를 사용한다. API/S3/MQTT의 인증서를
검증하며 skip-verify를 사용하지 않는다. API는 제한된 제어 서버 SA를 사용하고 Runner는 실제
scheduler·Pod-bound projected token·TokenReview·claim을 거친다.

시험 시나리오는 AUTO·NODE의 두 Device→두 STREAM Runner→BATCH report, 상태9 확정 시 API
Pod 교체, 처리 중 sink 취소다. 외부 시험 소유자는 실제 DB의 확정 checkpoint SHA와 실제 Pod를
관측해 다음 입력을 보낸다. 공개 Run 생성·장치 토큰 발급·취소·경로/결과 조회는 HTTPS API를 사용한다.
성공 판정에는 실제 고정 S3 파일6개와 값14/23/37, Result의 producer Pod/Attempt/Node 대조와 자원
회수가 필요하다. report 명령에는 관측을 위한 합성2초 지연이 있으며 성능 측정값으로 사용하지 않는다.

현재 JAR를 기존 검증 API 이미지의 JRE에 전달하고 SHA를 확인한다. Runner/MinIO는 release.json의
검증 digest를 사용한다. CI Runner 작업을 먼저 검증한 경우 `--runner-image`와 `--runner-source`에
해당 고정 digest·전체 소스 커밋을 함께 지정할 수 있다. 둘 중 하나만 지정하거나 tag/다른 저장소를
사용하면 클러스터 접근 전에 거절한다. 보고서에서 API 기반 이미지와 Runner 소스를 별도로 보존한다.
따라서 이 경로 자체는 새 API 이미지 전체 검증을 대신하지 않는다.
소유 자원 목록은 `.tools/edgeai-stream-check-*-owner.json`, 실패 진단은 private 파일에 남긴다.
기존 배포·프로젝트 DB·저장소는 변경하지 않고, 생성한 namespace 내부의 해당 UID 자원만 제거한다.

## 확인한 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| CA compiler·VD 이전 구성 호환·이름 제한 및 전체 단위 | `20261003T102249Z-eb5b910f` | 101개 PASS |
| 실제 PostgreSQL VD 신뢰 설정 고정 | `20261003T102507Z-c06695dd` | 14개 PASS |
| 전체 PostgreSQL 회귀 | `20261003T104208Z-13b0af44` | 178개 PASS |
| 실제 Kubernetes fsGroup 재현 | `20261003T104630Z-1950dfa5` | UID10001, parent2777, mkdir 결과2700, 명시적0700 변경 확인 및 소유 Pod 삭제 |
| 기존 Runner의 setgid 회귀 재현 | `20261003T104520Z-4fd28778` | 수정 전 FAIL: claim 후 INVALID_RESPONSE·결과 없음 |
| 같은 실제 HTTPS/MQTT/모델/Runner 회귀 | `20261003T104558Z-b73fbd99` | 수정 후 PASS: 결과14·단일 commit·세션/Journal/모델 폴더0700 |
| 전체 실제 Spring/PG/S3 저장소 회귀 | `20261003T104711Z-2861f3f2` | 34개 PASS, 독립 DAG의 setgid 작업 폴더 포함 |
| 재연결 단독 재검증 | `20261003T105053Z-b5273b48` | 1개 PASS, 8.103초 |
| 전체 HTTPS/TLS MQTT 직렬 재검증 | `20261003T105647Z-5a641fb0` | 71개 PASS, 124.607초 |

첫 전체 MQTT `104711Z-82cbad90`는71개 중 broker SIGKILL/재연결 시험1개가20초 진행 대기로
실패했다. 같은 코드의 단독 재검증과 Gradle을 병행하지 않은 전체 재검증은 위와 같이 통과했다.
시간 제한을 늘리거나 재연결 구현을 변경하지 않았다. 처음 시간 초과의 원인은 미확정이며,
setgid 수정으로 해당 간헐 실패까지 해결했다고 판정하지 않는다.

최초 단위 `102140Z-5c72f465`는 새 시험의 Java wildcard assertion 컴파일 오류였으며 수정 후 통과했다.
Kubernetes `103720Z-67ab2988`, `103929Z-3fb0cf5a`, `104127Z-ea134ec5`는 격리 TLS 서비스와 실제
Runner claim까지 진행했지만 스트림 초기화에서 실패했다. 첫 시험에는 연속 route 조회 사이 상태가 바뀌는
시험 코드 문제도 있었다. 조회 성공 값을 재사용하고 실제 DB 실패 상태를 보존한 후 sink의 RUNNER_FAILED,
root 취소·report 미배정을 확인했다. setgid 조건에서 같은 Runner 실패를 재현한 뒤 원인을 수정했다.
실제 Pod 재현의 최초 `104522Z-b254049a`는 Pod 성공 판정에 실패했으며 amd64를 명시한 위 실행으로
재검증했다. 실패한 각 시험의 소유 자원은 정리됐다. 수정된 Runner 이미지로 전체 DAG를 다시 실행해야 한다.

## 선행 공개 실행의 CI·배포

source `6cefa1a`의 CI37114497788은5개 작업과 내려받은17개 결과JSON이 모두 PASS/0이다.
kind `20261003T100430Z-e7b47b6e`는 실제 BATCH/재시도/노드 전환/TLS Remote/VD, API 재시작,
고정 S3 결과20+5와 소유 kind cluster `edgeai-ci-65647c6a17d7` 삭제를 통과했다.
배포 `20261003T102506Z-4b1e3e84`에서 정확한 API/dashboard/MinIO imageID·Ready·PVC Bound와
Argo Synced를 확인했다. GitOps 이미지 pin은 `7f5f68d`, 관측한 Argo revision은 후속 `08f57d1`이다.
공유 Ingress로 aggregate health는Progressing이다. 이 근거는 이후 TLS/디렉터리 수정의 배포 완료를 뜻하지 않는다.

후속 독립 DAG source `08f57d1`의 CI37116337147도5 jobs와 다운로드17개 결과JSON 모두 PASS/0이다.
Runner 컨테이너108개(102507Z-ef1671c5), TLS MQTT70개(102706Z-46089963), 실제 kind
103800Z-597b25b7의 BATCH/Retry/Offload/TLS Remote/VD·고정 S3 20+5개를 확인했다.
배포105914Z-31fb6998에서 GitOps `bcd4cd2da1c90d07447fe6fa07380caf98762b19`의 정확한
API/dashboard/MinIO imageID·Ready·PVC Bound·Argo Synced와 VD 활성화를 확인했다.
공유 Ingress로 aggregate health는Progressing이다.
이 역시 이후 CA/setgid 수정 이미지의 검증과 구분한다.

M7 전체 완료, 배포의 공개 STREAM 활성화, demo-multidevice, 그룹 checkpoint 복구와 M5 잔여/M8–M10은 남는다.
