# 성능 프로파일 웹 등록 화면 운영 배포 — 2026-10-29

범위: 콘솔 `성능 프로파일` 화면(`/ui/main/scheduling-profiles`, [측정 기반 DAG 배치](../guides/operational-scheduling.md#웹-화면에서-등록))을 클러스터 `edgeai` 네임스페이스의 웹 콘솔(13080)에 배포했습니다. API·계약·환경 변수는 바꾸지 않았습니다. 운영에서 측정 프로파일 등록, 미리보기, Run 실행은 하지 않았습니다.

## 소스와 이미지

| 항목 | 값 |
|---|---|
| 화면 커밋 | `9b0b877` (기능), `1c80943`·`87082ac`·`090cf5f` (검토 결과 수정) |
| 배포 소스 | `090cf5f53235d94dd540424fd6269692a819a675` |
| 배포 고정 커밋 | `07a8bf3` (`overlays/dev` 콘솔 digest, `release.json`의 `sourceRevision`·`dashboardDigest`). `3466d39`, `c3ad611`, `0744a01`은 중간 이미지 고정 커밋이며 해당 이미지는 운영에 올리지 않았습니다. |
| 콘솔 이미지 | `10.254.192.47:5000/edgeai-dashboard@sha256:71262eaeb46a86462b17057f8e45f91598a4fb0522d5270330d604fd32c189b6` (tag `sha-090cf5f5…`) |
| API 이미지 | 변경 없음 `10.254.192.47:5000/edgeai-api@sha256:15eb6af65b1644f11e7c7e9e78ff635101787397c18c7b4f96649680d90916e7` |

콘솔 이미지는 이전 운영 콘솔(`sha256:9aa35f92…`)을 기반으로 `scripts/ops/build-kestra-console.sh` release 전체를 `/app`에 복사한 layer 하나를 더했습니다(FROM+COPY, RUN 없음, `linux/amd64`, `--provenance=false`, rootless builder `edgeai-kube-arm64-rootless`). 레지스트리에서 다시 읽어 확인했습니다: amd64, 사용자 `node`, 하위 8개 layer가 기반과 같음, 맨 위 layer 931개 파일이 시험한 release와 같은 sha256이고 그 외 파일은 `REVISION`(`090cf5f…`) 하나입니다. 기반 이미지의 이전 hash 이름 asset은 하위 layer에 남지만 새 `index.html`이 참조하지 않습니다.

## 배포 전 시험

- 브라우저 시험 `dashboard/tests/scheduling-profile.integration.spec.ts`(desktop·mobile, API는 브라우저 안 fixture): `1c80943` release에서 4개 시나리오 8/8, 마지막 수정 뒤 `090cf5f` release에서 영향을 받은 가져오기 시나리오 2/2.
- 대시보드 단위 시험 126/126, typecheck·lint 통과.
- 백엔드·계약은 바뀌지 않아 이전 격리 통합 시험 27건 결과를 재사용했습니다.
- 독립 검토 2회에서 나온 결함(저장된 parameters 정밀도, 가져오기 메타데이터 검증과 증빙 ID 제한, 미리보기와 표시 프로파일 일치, 마이크로초 시각 순서, 버전 조회 실패와 빈 목록 구분)을 수정했습니다.

## 배포 절차

1. 사전 백업(비공개, Git 밖): 콘솔·API Deployment JSON.
2. `07a8bf3`을 `main`에 fast-forward로 push했습니다.
3. `kubectl set image deploy/edgeai-dashboard dashboard=…@sha256:71262eae…`를 실행했습니다.
4. Argo CD Application `edgeai-dev`(`main`, `deploy/kubernetes/overlays/dev`, selfHeal)가 이 Deployment를 관리합니다. Argo CD가 07:29:49Z에 이전 고정값 `ab3d8d2`로 한 번 되돌린 뒤, 07:30:03Z에 `07a8bf3`을 동기화했습니다. 최종 상태는 Synced·Healthy `07a8bf3`입니다. 두 커밋 사이 overlay 차이는 콘솔 digest뿐입니다.

## 수용 확인 (07:30:52Z 이후, 읽기 전용)

| 확인 | 결과 |
|---|---|
| 콘솔 Pod | `edgeai-dashboard-64885fdd8f-q47ft` Ready, 재시작 0, imageID `…@sha256:71262eae…` |
| 콘솔 Deployment | 이미지 외 spec이 배포 전과 같음 |
| API Deployment·Pod | spec(이미지 포함)이 배포 전과 같음. Pod `edgeai-api-7fcc6b68d5-4rqlj` 재시작 0, `EDGEAI_RUNTIME_WORKFLOW_KEYS=hawkeye-video-safety` 유지 |
| 웹 `/ui/main/scheduling-profiles`의 `index.html`, `SchedulingProfilesPage` chunk | 시험한 release 파일과 sha256 같음 |
| 웹 `/api/health`, `/ui/main/{scheduling-profiles,workflows,dashboards/default}`, `/api/control-plane/{workflows,nodes,workflow-runs,workflow-csrf}` | 200 |
| 웹 `GET /api/control-plane/scheduling-profiles/{임의 UUID}` | 404 `SCHEDULING_PROFILE_NOT_FOUND` |
| 브라우저 desktop | 사이드바 `성능 프로파일`이 `실행 · 작업 · 결과` 아래에 표시됨. 클릭하면 경로로 이동. 워크플로·DAG 버전 선택, 프로파일 ID 입력과 열기 버튼 표시 |
| 브라우저 mobile | 같은 선택 요소 표시. 워크플로 목록에 운영 워크플로 449개 |
| 쓰기 요청, 페이지 오류, 5xx | 0 (GET 외 요청은 차단하도록 하고 확인) |

## 한계

- 운영 프로파일 등록·미리보기·측정 기반 Run은 실행하지 않았으므로 화면의 운영 쓰기 경로와 성능 효과는 확인하지 않았습니다. 콘솔 proxy의 쓰기 전달은 이전 배포에서 확인했습니다(잘못된 본문 400, CSRF 없음 403).
- 프로파일 목록 API는 없어 화면은 ID로 다시 엽니다. 실행 요청 양식에는 아직 측정 프로파일 선택이 없습니다.

## 되돌리기

`overlays/dev`의 콘솔 digest를 `sha256:9aa35f92241c6e4523cf01b7053ce4605c5422972cf8822acb5e882fb059eeb4`로 되돌려 `main`에 push합니다. Argo CD selfHeal이 Git 고정값을 다시 적용하므로 `kubectl set image`만으로는 유지되지 않습니다.
