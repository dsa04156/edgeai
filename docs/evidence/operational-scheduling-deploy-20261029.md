# 측정 기반 DAG 배치 운영 배포 — 2026-10-29

범위: [측정 기반 DAG 배치](../guides/operational-scheduling.md)를 클러스터 `edgeai` 네임스페이스의 API와 웹 콘솔(13080)에 배포했습니다. 새 메뉴·화면은 없습니다. 측정 프로파일 등록과 측정 기반 Run 실행은 하지 않았으므로 성능 효과는 주장하지 않습니다.

## 소스와 이미지

| 항목 | 값 |
|---|---|
| 기능 커밋 | `bffcc408889f7e002a03e4e698c41f4294d95d15` (기준 `101cc56`) |
| 배포 고정 커밋 | `3492603` (`overlays/dev` API·콘솔 digest, `release.json`) |
| API 이미지 | `10.254.192.47:5000/edgeai-api@sha256:15eb6af65b1644f11e7c7e9e78ff635101787397c18c7b4f96649680d90916e7` |
| 콘솔 이미지 | `10.254.192.47:5000/edgeai-dashboard@sha256:9aa35f92241c6e4523cf01b7053ce4605c5422972cf8822acb5e882fb059eeb4` |

두 이미지는 이전 운영 이미지(`ghcr.io/dsa04156/edgeai-api@sha256:576ee63f…`, `edgeai-dashboard@sha256:41b9dd5a…`)를 그대로 기반으로 하고 맨 위 layer 하나만 추가했습니다(FROM+COPY, `linux/amd64`, rootless builder `edgeai-kube-arm64-rootless`). 레지스트리에서 다시 읽어 확인한 값:

- API: 사용자 `10001:10001`, 맨 위 layer `/app/app.jar` sha256 `9f9bded3e940d233a0d7783528371ea37ed783144142852a467a6ef00eb5f45e`
- 콘솔: 사용자 `node`, 맨 위 layer `/app/device-registration.ts` sha256 `8e424cd2743c293c5734d1f00676ec66a9c0decfd44970f60b24554807a840ec`
- 두 이미지의 하위 7개 layer는 이전 운영 이미지와 같습니다.
- OCI revision label은 `bffcc408…`입니다.

콘솔 Dockerfile은 `device-registration.ts`를 빌드 없이 `/app`에 복사해 실행합니다. 변경된 client 소스가 없으므로 `dist/`는 이전 이미지와 같습니다.

## 배포 절차

1. 사전 백업(비공개, Git 밖): Deployment 2개와 PostgreSQL custom-format dump. sha256 일치, `pg_restore --list` 594행.
2. `kubectl set image deploy/edgeai-api api=…@sha256:15eb6af6…`. 기동 시 Flyway가 V66에서 `67 - operational scheduling`을 적용했습니다(성공, 0.456 s).
3. `kubectl set image deploy/edgeai-dashboard dashboard=…@sha256:9aa35f92…`.
4. 두 Deployment의 이미지 외 spec(env·포트·볼륨·selector·`Recreate` 전략)은 배포 전후가 같습니다. `EDGEAI_RUNTIME_ENABLED=true`, `EDGEAI_RUNTIME_WORKFLOW_KEYS=hawkeye-video-safety`도 유지했습니다.

Argo CD Application은 없고 새로 만들지 않았습니다.

## 수용 확인 (배포 후)

| 요청 | 결과 |
|---|---|
| Pod imageID | 두 Pod 모두 위 digest, Ready, 재시작 0 |
| API `/actuator/health`, `/actuator/health/readiness`, `/api/v1/platform` | 200 / 200 / 200 |
| 웹 `/ui/main/dashboards/default`, `/api/health`, `/api/live`, `/api/edgeai/overview` | 200 |
| 웹 `/api/control-plane/workflows`, `nodes`, `workflow-runs`, `infrastructure`, `scenarios/hawkeye-safety` | 200 |
| 웹 `GET /api/control-plane/scheduling-profiles/{임의 UUID}` | 404 `SCHEDULING_PROFILE_NOT_FOUND` |
| 웹 `GET /api/control-plane/workflow-runs/{임의 UUID}/scheduling-decision` | 404 `RUN_NOT_FOUND` |
| 웹 `GET /api/control-plane/workflow-csrf` 후 잘못된 프로파일 `POST /api/control-plane/scheduling-profiles` | 400 `SCHEDULING_PROFILE_INVALID` (쓰기 전달 확인, 저장 없음) |
| CSRF 없는 같은 POST | 403 |
| `edgeai.scheduling_profile`, `edgeai.workflow_run_scheduling` 행 수 | 0 / 0 |
| `flyway_schema_history` | 67 `operational scheduling` success |

배포 전 시험(같은 소스): 격리 PostgreSQL 통합 시험 27건(측정 기반 배치 6, Workflow 10, Retry 10, Database 1), 계약 검사, 대시보드 단위 시험 126건, typecheck, 문서 검사 통과. 대시보드 lint는 실행하지 않았습니다.

## 되돌리기

- 이미지만 이전 digest로 되돌립니다: API `ghcr.io/dsa04156/edgeai-api@sha256:576ee63f…`, 콘솔 `ghcr.io/dsa04156/edgeai-dashboard@sha256:41b9dd5a…`.
- V67은 그대로 둡니다. 추가된 테이블과 nullable 열은 이전 코드가 읽지 않습니다. Flyway 기본값(`ignoreMigrationPatterns=*:future`)은 이미 적용된 상위 버전을 허용합니다.
- DB 복원은 승인 후에만 사전 dump로 합니다.
