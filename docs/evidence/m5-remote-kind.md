# M5 실제 Kubernetes↔Remote 전환 — 추가 게이트

source45ce85f의 CI37016556197에서 실제 kind22Run과 결과 파일20개 검증을 통과했다.
구성 요소 시험과 실제 종단 결과를 아래에 구분한다. 외부 수용·상태형 복원이 남아 M5는 진행 중이다.

## 추가한 실제 경로

기존 kind 격리·소유 label·cleanup 규칙과18개 Run을 유지하고 독립 TLS Remote Pod/PVC를 추가한다.
API는 서비스 DNS와 CA·bearer로 참조 제공자에 연결하며 별도 API Pod 교체가 제공자를 교체하지 않는다.
표준 kind 게이트에서 네 Run을 추가하도록 연결했다. 구현은 scripts/test-kind.py와 smoke-runtime.py다.

- REMOTE BATCH/API 교체: 같은 Attempt·allocation·단일 실제 계산과 하위 BATCH 결과를 확인한다.
- NODE→REMOTE BATCH child 전환: 이전 Pod 삭제·늦은 commit 차단, 같은 Task의 새 epoch와
  실제 선행 artifact 입력을 대조한다. 결과 producer는 RemoteAllocation이어야 한다.
- REMOTE→NODE BATCH child 전환: 실제 provider CANCELLED 뒤에만 target STARTING을 허용하고,
  target 노드를 잠시 cordon한 상태에서 API 교체 후 같은 target Attempt로 회복해야 한다.
  uncordon 후 실제 scheduler/Runner와 선행 S3 object version, Pod UID 결과를 대조한다.
- Remote 취소/API 교체: 실제 provider CANCELLED, Result와 하위 Attempt 부재를 확인한다.

SQLite 계산 횟수와 metadata는 비공개 subprocess pipe에서 해당 합성 Run만 추출한다. 서명 URL·토큰·
TLS key·전체 요청은 보고서에 없다. 모든 결과 파일은 기존 실제 S3 version/크기/SHA/계산값 검증에 추가한다.
두 실행 방식의 synthetic linear 예제에 같은0~60000ms 대기 입력을 지원하여 전환 시점을 확보한다.
이는 임의 지연을 실제 성능으로 제시하는 시험이 아니다.

## 확인한 구성 요소 증거

| testRunId (2026-10-02) | 실제 확인 범위 |
|---|---|
| 20261002T134429Z-be6f5414 | Python TLS launcher2개 + 기존 실제 Java/참조 제공자13개. PASS/0 |
| 20261002T134429Z-abb0d818 | 호스트 Runner14개. 지연 중 실제 timeout·잘못된 지연 거절 포함. PASS/0 |
| 20261002T134622Z-a17fffe2 | 실제 Kubernetes server dry-run: Remote 리소스6개와 Kustomize API patch. PASS/0, 적용 없음 |

TLS 시험은 신뢰 CA·hostname 검증, 무인증401·유효 bearer, 파일 토큰 교체와 프로세스 재시작 후
영속 취소 tombstone을 확인한다. manifest 검증은 Secret2·ConfigMap·PVC·Service·Deployment와
기존 base에 합성한 API env/volume patch를 실제 Kubernetes1.31.14 서버에서 수행했다.
로컬 Docker 권한 제한 때문에 실제 kind 수용은 GitHub runner에서 확인한다.
`20261002T134938Z-0d00cb3b`의 로컬 kind 실행은 같은 Docker 접근 제한으로 BLOCKED/exit2였다.

## 실제 CI·배포 확인

[CI37016556197](https://github.com/dsa04156/edgeai/actions/runs/37016556197),
source `45ce85f9c7ea5249057556251ed490629a9d5b52`: 5 jobs 모두 success.
platform/storage/runner/image artifact 네 그룹을 내려받아 result.json15개 모두 PASS/0을 확인했다.
Docker build 기록은 ZIP 형식이 아니어서 전체 artifact 다운로드가 실패했으며, 검증 artifact만
선택해 다시 내려받았다. 검증 자료 누락을 성공으로 간주하지 않았다.

`20261002T140450Z-743ceafa`의 실제 kind 보고서에는 기존18개와 새 Remote4개, 총22Run이 있다.
Remote BATCH의 실제 allocation당 executions1, API 교체 후 동일 Attempt와 제공자 Pod 보존,
NODE→REMOTE의 이전 Pod commit401·새 epoch2, REMOTE→NODE의 제공자 CANCELLED·새 Pod Result와
전환 중 API 교체 복구, Remote 취소 뒤 하위/결과 부재를 확인했다. 결과 파일20개의 고정 S3 version·
크기·SHA·실제 합성 계산값 검증과 생성한 kind cluster 삭제도 로그에 남아 있다.

GitOps `861663f`가 같은 source의 검증 digest를 기록했다. 실제 기존 클러스터 검증
`20261002T143130Z-1956d7e0`에서 API/Dashboard/MinIO imageID 일치·Ready·PVCBound·ArgoSynced를
확인했다. 기존 공유 Ingress status 제한으로 Argo aggregate health는 Progressing이다.
kind 종료 직전 진단에는 Dashboard ready=false가 한 번 기록되었다. 실제 HTTP proxy를 통한22Run은
통과했지만 이 진단의 원인은 확인되지 않았다. 이후 기존 배포의 Dashboard Ready 확인과는 구분한다.

## 남은 확인

실제 외부2세부 API·장비/모델·상태형 복원은 별도 미완료 범위이며 참조 SYNTHETIC 제공자로 대체하지 않는다.
원래 M0–M10 범위와 LOCAL_VERIFIED/FULL_ACCEPTANCE의 전체 게이트는 유지한다.
