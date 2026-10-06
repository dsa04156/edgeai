# M7 Kubernetes TLS·스트림 수용 진행 기록

2026-10-03. [ADR0042](../adr/0042-runtime-tls-trust.md). 실제 Kubernetes AUTO/NODE DAG·API 재시작·취소를 통과했다. 전체 M7 수용은 아직 미완료다.

## 구현

`scripts/test/test-stream-kubernetes.sh <context>`는 현재 API JAR를 빌드한 뒤 소유 label과 UID를
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
재검증했다. 실패한 각 시험의 소유 자원은 정리됐다. 수정된 Runner 이미지의 후속 전체 DAG 검증은 아래와 같다.

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

## 수정 이미지의 실제 Kubernetes 수용

`20261003T110626Z-140db9d9`가 PASS/0이다. Runner는 source
`e93d9e196af9122f1fba56afa3e11d5dd2bbb203`의 CI37118314544 runner 작업에서 검증한
`sha256:e908fc5f70c30967033e04c9c910df83388813e72c23a82a961c232c1ccc96de`다.
CI 컨테이너108개(110146Z-abfcec67)·HTTPS/TLS MQTT71개(110335Z-678d7a32) PASS/0를
다운로드 확인하고 GHCR 소스 tag의 manifest 내용 SHA와 응답 digest를 대조했다.

- 실제 AUTO 및 NODE에서 두 DeviceSource→두 STREAM Runner→BATCH Runner를 실행했다.
  각 경우 root14·sink23·report37이며 실제 고정 S3 파일6개의 bytes·SHA·version·계산값을 검증했다.
- AUTO의 확정 상태9/9에서 API Pod를 교체했다. 14.815초 뒤 새 API UID를 확인했고, 두 Runner
  Pod UID를 유지한 채 상태14/23·최종 결과로 진행했다. 이 시간은 성능 수용값이 아니다.
- NODE의 세 결과 생산자 모두 지정한 실제 Node UID와 일치했다. 세 Run 전체에서 실제 Runner
  Pod8개의 imageID·Attempt/Task/Node/Pod 신원과 고정 Runner 이벤트를 관측했다.
- 실행 중 sink 취소는 스트림 그룹·하위 Task로 전파됐다. 결과는 없고 BATCH Attempt도 생성되지
  않았으며 Job/Pod/Secret과 시험용 API/DB/MinIO/broker/source 자원을 모두 제거했다.

API는 현재 JAR SHA `0cec11a0f3ad51d463eb4e1c48ade18daf8a0d15d0b33a7244cfc0226629f657`를
기존08f57d1 JRE 이미지로 실행했다. 완성된 보고서는 해당 실행 디렉터리의
`stream-kubernetes.json`에 보존했다. 실제 물리 장치·모델 시험과 새 전체 API 이미지 검증은 별도다.
재연결 단독 추가5회 `20261003T110309Z-e785956b`도44.085초/PASS였으나 선행 간헐 timeout의
원인까지 확정하지 않았다.

## 패키징된 API 이미지와 최신 배포

source `e93d9e196af9122f1fba56afa3e11d5dd2bbb203`의 CI37118314544는5 jobs와
다운로드한17개 결과JSON 모두 PASS/0이다. 실제 kind111413Z-a430c00d에서 기존
BATCH/Retry/Offload/TLS Remote/VD·고정 S3 20+5개와 소유 클러스터 삭제를 확인했다.
이 CI에는 후속 STREAM kind 게이트가 아직 포함되지 않는다.
배포114014Z-b349b243에서 GitOps `ee44614a5168800212537c59c9bf39688a00bf4d`의
정확한 API/dashboard/MinIO imageID·Ready·PVC Bound·Argo Synced·VD 활성화를 확인했다.
공유 Ingress로 aggregate health는Progressing이다.

`20261003T114015Z-426fc3dd`는 **packaged-image 모드**로 위 소스의 전체 API 이미지
`sha256:fafdfb9713d7ff00b55deacfb21f7d64633c570e993c8e360b229c81d3bf2c79`를 검증했다.
로컬 JAR를 주입하지 않고 이미지 안의 `/app/app.jar`를 실행한다. 실제 API imageID와 교체 전후
같은 JAR SHA를 확인했고 AUTO/NODE의 두 Device→두 STREAM Runner→BATCH, 상태9 확정 뒤
API Pod 교체, root14/sink23/report37, 고정 S3파일6개와 sink 취소·자원 정리가 모두 PASS다.
실행 디렉터리의 `stream-kubernetes.json`에 원시 보고서를 보존했다.

후속 kind 게이트도 이 packaged-image 경로를 사용한다. 실제 기존 클러스터의 패키지 이미지
시험은 통과했지만, 새 kind 게이트의 GitHub Actions 실행 결과는 아직 별도로 확인해야 한다.

M7 전체 완료, 배포의 공개 STREAM 활성화, demo-multidevice, 그룹 checkpoint 복구와 M5 잔여/M8–M10은 남는다.

## 새 STREAM kind 게이트 확인

source `c152d4da616832da4d96196d5ebc36e57f4e29c7`의 CI37120638129는5 jobs와
다운로드17개 결과JSON 모두 PASS/0이다. kind `115518Z-41a6b1c9`는 기존 BATCH/Remote/VD에
추가한 실제 STREAM 게이트도 통과했다. packaged API commit-tag 이미지와 검증 Runner를 사용해
AUTO/NODE DAG·상태9에서 API Pod 교체8.088초·같은 Runner2UID 유지·결과14/23/37·고정 S36개·
sink 취소·소유 자원 회수를 확인했다. 총 RunnerPod8개이며 kind 클러스터
`edgeai-ci-685594f200e7`도 삭제했다. 실행 디렉터리에 `kind-stream.json`을 보존했다.

배포 `121607Z-1af97f19`에서 GitOps `ab68e2ab9313a570e6056a1e035bb2c30573f98b`의
정확한3개 imageID·Ready·PVCBound·ArgoSynced·VD 활성화를 확인했다. aggregate health는
공유 Ingress 때문에Progressing이다. 이 CI/배포는 후속 Device 조회·그룹 재시도 변경의 검증이 아니다.
