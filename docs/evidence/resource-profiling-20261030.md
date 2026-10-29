# 서비스 자원 프로파일 시험·연동 기록 (2026-10-30)

[서비스 자원 프로파일](../guides/resource-profiling.md) 구현의 검증 근거입니다. 개발 시험은 기준 커밋 `4b497b6`의 격리 작업 트리에서 시작했고, 최신 main 병합·두 차례 검토 수정 후 운영에 반영했습니다. 개발 시험과 아래 [운영 배포](#운영-배포-2026-10-30) 근거를 구분합니다.

## 환경

| 항목 | 값 |
|---|---|
| DB | 작업 트리 전용 PostgreSQL 16.15(`127.0.0.1:55470`, `scripts/dev/dev-postgres-local.sh`), 시험마다 `isolated-db.sh`의 일회용 DB |
| Prometheus | 운영 `http://10.100.231.126:9090`(3.10.0) 읽기 전용 조회만 |
| KRR | 공식 릴리스 `krr-ubuntu-latest-v1.31.1.zip`(sha256 `ccb98636b69e9bab80fc65eb37376fea957b618745e1fdebef5d0c32b08725a2`, 소스 태그 커밋 `f21d482`). 이미지 기준은 `robustadev/krr:v1.31.1@sha256:835bf3b6fec4d8f27d96d236a36542bd87bfcb4f12f3537f8dce7f886916ed9c` |
| Kubernetes 접근 | KRR의 워크로드·Pod 목록 조회(get/list)만. 변경 요청 없음 |
| Docker | 로컬 소켓 권한 없음 → bridge 이미지 빌드는 `.github/workflows/krr-bridge.yml`에서 수행 |

KRR `v1.30.0`과 `v1.31.1` 사이 `robusta_krr/` 소스 변경은 없음을 확인했고(CVE 수정만), 보고서 계약은 `robusta_krr/core/models/result.py`·`allocations.py`·`objects.py`(v1.31.1) 기준으로 해석했습니다.

## 실제 KRR·Prometheus 연동

1. **KRR 단독 실행** — `krr simple -p <prometheus> -n edgeai --resource Deployment --resource StatefulSet -f json --fileoutput edgeai.json --history_duration 24` → exit 0, scan 14개. `edgeai-api/api`: CPU 요청 `0.393` core·limit `null`, 메모리 요청·limit `530579456`. `edgeai-postgres`·`edgeai-scenario-npu`: `"?"`·`No data`·`NoPrometheus*Metrics`. 일부를 잘라 `backend/app/src/test/resources/resource-profiling/krr-v1.31.1-edgeai-report.json` fixture로 사용했습니다.
2. **API 경유(로컬 API + 로컬 bridge가 실제 KRR 바이너리 실행)** — 원본: [resource-profiling-20261030-live.json](resource-profiling-20261030-live.json)
   - `edgeai/Deployment/edgeai-api/api`, 1시간 구간: CPU `AVAILABLE` p95 245.5m·최대 405.3m(표본 251), 메모리 `AVAILABLE` 최대 429,096,960 B, `aggregation: PER_POD_CONTAINER`, 구간의 Pod 3개를 `perPod`로 분리(합산 없음).
   - KRR: `AVAILABLE` 393m / 530,579,456 B, 창 24시간(KRR 실행 시각 기준), 인자·전략 설정·보고서 SHA-256 보존.
   - 장치: RTX 5080(`etri-ser0002-cgnmsb`, DCGM) `AVAILABLE`·`NODE_DEVICE_UNKNOWN_OWNER`(DCGM에 Pod 표시 없음), Hailo-8(`etri-dev0002-raspi5`) `UNSUPPORTED`.
   - `edgeai/StatefulSet/edgeai-postgres`: 실측 `NOT_FOUND`(구간에 Pod 없음), KRR `NO_DATA`(`No data`).
   - CPU·메모리를 KRR 추천으로 확정 저장 → `MEASURED` revision, `services/edgeai-api-profile/versions/1.0.0/revisions/1/handoff` 조회 일치.
   - 이 실행 당시 migration 이름은 `V67_1`이었습니다. `origin/main`(`3b74f34`, V68·V69 배포)과 병합한 뒤 `V70`으로 바꾸고 새 격리 DB에서 다시 확인했습니다(아래 「병합 후 재검증」).
3. **화면 경유(목 없음)** — release 콘솔 → 로컬 API → bridge(KRR) → Prometheus로 Playwright가 서비스 선택·`Deployment · edgeai-api`·RTX 5080 선택 → 분석(약 2.6초) → 표 확인(CPU 27.9m/140m/245.5m/391.5m, KRR 393m·제한 없음, 메모리 KRR 506 MiB, GPU "장치 전체 · 소유 미확인") → 추천 사용·범위 입력 → 저장 → 전달 계약 JSON(`MEASURED`, `basis: RECOMMENDED`, `request: 393`) 표시까지 통과했습니다.

## 자동 시험

| 명령 | 결과 |
|---|---|
| `python3 -m unittest discover -s profiling-bridge/tests` | 5/5 (고정 인자·URL 비공개·허용 범위 밖 400·실패/보고서 없음/시간 초과) |
| `bash scripts/test/test-unit.sh` | 통과. 신규: `KrrReportsTest` 5, `PrometheusWorkloadMetricsTest` 8(정확한 PromQL·중복 scrape 접기·복제본 비합산·UID 재사용 `AMBIGUOUS`·NO_DATA/INSUFFICIENT/STALE·DCGM 귀속·미지원 exporter), `ResourceRequirementTest` 4, `ManagementAuditRoutesTest` |
| `bash scripts/test/test-contract.sh` | 통과(OpenAPI → `dashboard/lib/api-schema.d.ts` 일치, 패키지 계약 일치) |
| `backend/gradlew -p backend :app:integrationTest --tests '*ResourceProfileIntegrationTest'`(격리 DB) | 6/6: 수동 미측정 저장·revision 증가·불변 trigger·SERVICE 삭제 409·정확한 handoff, Idempotency 재생/충돌, 잘못된 수량·범위·NaN·소수·근거 위조 400, 실측+KRR 근거 인용·다른 서비스 분석 거절, NO_DATA/INSUFFICIENT/STALE·추천 없음 근거 거절, 네임스페이스·구간·이름 주입·URL 필드 거절 |
| 콘솔 단위(`node --test`) | 133/133, 신규 `resource-profile.unit.ts` 6, 경로 경계 시험(관리 세션·고정 경로·64 KiB·405/404·Origin) |
| `corepack pnpm --filter @edgeai/dashboard lint` / `typecheck` / release 빌드 | 통과 |
| Playwright(desktop·mobile, API는 브라우저 안 fixture) | `resource-profile.integration.spec.ts` 6/6, 기존 `scheduling-profile.integration.spec.ts` 8/8 회귀 |
| `kubectl kustomize`(로컬 렌더만) | dev overlay + 컴포넌트 렌더 성공, `enable-resource-profiling.py`로 digest 고정·자리표시 digest 거절 확인 |

전체 `integrationTest`의 결과와 기존 시험 실패 여부는 아래 「전체 통합 시험」에 기록합니다.

## 전체 통합 시험

`bash scripts/test/test-integration.sh`(일회용 격리 DB, 운영 DB·Influx 미사용): 244개 중 243 통과, 1 실패.

- 실패: `ContinuousRegistrationIntegrationTest.everySelectedResultIsRetriedAfterHistoryFailureWithoutStoppingLiveOutputs`(`historyStored` 기대 false). 수정하지 않은 기준 커밋 `4b497b6`의 별도 작업 트리에서도 같은 시험이 실패했습니다. CI는 이 메서드를 실제 InfluxDB·MQTT가 필요한 `dataStoreIntegrationTest`로도 실행하며, 이 작업 환경에는 해당 저장소가 없습니다. 이번 변경과 무관한 기존 환경 의존 실패입니다.
- 앞선 1회 실행에서는 `WorkflowFullObservationVdIntegrationTest`가 로컬 PostgreSQL 연결 점검 실패(`Database recovery safety check failed`)로 한 번 실패했고 재실행에서 통과했습니다.
- 단위 시험은 243/243(`UNIT_TEST_COUNTS`), 계약 시험 통과.

## 병합 후 재검증

`origin/main` `3b74f34`(offload-lab, V68·V69)와 병합(`f54a8fb`)하고 migration을 `V70`으로 바꾼 뒤 다시 확인했습니다.

| 확인 | 결과 |
|---|---|
| 새 격리 DB의 Flyway 순서 | `67` → `68` → `69` → `70`(resource profile) 적용 |
| 실제 KRR·Prometheus API 경유(위 2번 절차 재실행) | 동일 결과: CPU p95 245.5m, KRR 393m / 530,579,456 B, RTX 5080 `NODE_DEVICE_UNKNOWN_OWNER`, Hailo `UNSUPPORTED`, `edgeai-postgres` `NOT_FOUND`/`NO_DATA`, revision 1 `MEASURED`·handoff 일치. 원본 JSON은 이 실행으로 갱신 |
| `test-unit.sh` | 251/251. 단, `origin/main`이 offload-lab 5개 operation을 감사 경로 없이 추가해 `ManagementAuditRoutesTest`가 main에서 이미 실패하던 것을 별도 커밋 `5c4f941`(경로 5개만 추가)로 맞췄습니다 |
| `test-contract.sh` | 통과 |
| `test-integration.sh` | 270개 중 `ResourceProfileIntegrationTest` 6/6 포함 268 통과. 실패 2: 위 기존 `ContinuousRegistrationIntegrationTest` 1건, `WorkflowFullObservationVdIntegrationTest` 1건(로컬 연결 점검 일시 실패, 단독 재실행 통과) |
| `test-ui.sh` | 140/140, lint·typecheck·release 빌드 통과 |
| Playwright(병합 release) | resource-profile 6/6 + scheduling-profile 8/8 |
| bridge 단위 시험 | 5/5 |

### main CI 회귀 정리(병합 범위)

`origin/main` `3b74f34`의 CI run `40894668350`은 이미 실패 상태였습니다.

- scaffold: `ManagementAuditRoutesTest` 1 실패 → offload-lab 5개 operation의 감사 경로 누락. `5c4f941`에서 그 5개만 추가했고 `test-unit.sh` 251/251.
- runner-native(amd64·arm64): 두 아키텍처 모두 `runner-container` 103개를 skip 없이 실행했지만 provenance 검사가 96개를 요구. `25209d9`가 `runner/tests/test_offload_lab.py`에 시험 메서드 7개를 추가했고 삭제는 0개임을 diff로 확인해 기대값만 103으로 맞췄습니다(`d32604f`, `stream-mqtt` 118 유지). `scripts/internal/test-runner-images.py` 12/12, 96개는 이제 거절됨을 시험에 추가.

## 검토 1차 수정과 이미지 준비(2026-10-30)

| 항목 | 근거 |
|---|---|
| 근거 위조 | `ResourceProfileIntegrationTest`: KRR 177m 인용에 999m `RECOMMENDED`, p95 450000000에 999000000 `MEASURED` 모두 400, 같은 값일 때만 저장·handoff 유지 |
| 전체 기한 | `ResourceAnalysisDeadlineIntegrationTest`(예산 1초/3초, 0.7초 워크로드·5초 장치 2개·10초 KRR 가짜): 약 2.7초에 201, 장치·KRR `UNAVAILABLE`(시간 한도), 중단된 호출이 interrupt됨, 행은 기한 안에 저장 |
| stderr 메모리 | bridge 시험: 16 MiB stderr에서 tracemalloc 최대 1 MiB 미만. 같은 시험을 이전 코드에 돌리면 33,621,249 B로 실패 |
| 분석 잠김·구간 조회 | Playwright: Pod 조회 대기 중 분석 성공·폼 잠김 없음, 168시간 구간에서만 보이는 Job 선택·분석. resource-profile 10/10 + scheduling 8/8(desktop·mobile) |
| 검토 2차(구간 변경 경합) | 구간을 바꾸는 즉시 Pod 목록·선택을 비우고 대기 중인 Pod 응답을 무효화. Playwright: 168시간에서 Pod 선택 → 구간 조회 지연 중 1시간으로 변경 → 분석 요청이 `pod`/`podUid` null·1시간 구간. 같은 시험을 수정 전 release에 돌리면 `pod: "detector-7d9-abc"`로 실패. resource-profile 12/12(desktop·mobile) |
| 콘솔 | `test-ui.sh` 140/140, lint·typecheck·release 빌드 |
| Argo CD | `applications.argoproj.io/edgeai-dev`: Synced·Healthy, revision `3b74f34`, 자동 동기화·selfHeal. API·콘솔은 `10.254.192.47:5000`의 digest(`newName`) |
| 이미지 빌드 | `edgeai-kube-arm64-rootless`에서 `linux/amd64` OCI 빌드(푸시 없음) 성공: 사용자 `10001:10001`, 기반 13개 layer가 `robustadev/krr:v1.31.1`(amd64 `sha256:10c21651…`)과 동일, 추가 파일은 `/opt/edgeai/krr_bridge.py`(소스와 같은 sha256)뿐 |
| 실행 확인 | 위 이미지의 Python·bridge를 비root 사용자, 쓰기 금지 rootfs로 실행해 운영 Prometheus에 실제 KRR: `COMPLETED`, 393m / 530,579,456 B, 쓰기는 HOME/TMPDIR(`.cache`·`.config`·보고서)뿐 |
| 발견·수정한 차단 요인 | 공식 이미지의 `krr version`은 `dev` → CI 검사를 사용자·읽기 전용 rootfs·기반 digest(`krrImage`) 확인으로 교체 |
| 통합 영향 | 컴포넌트를 켜면 운영 API Deployment에 `EDGEAI_RESOURCE_PROFILING_*` 5개 env만 추가되고 삭제·변경 0, 이미지·볼륨 동일, offload-lab 설정 유지(렌더 결과와 운영 spec 비교) |

## 운영 배포 (2026-10-30)

- 소스 `5da347b61d221838700b1689732efb76bb1fe944`, CI 이미지 고정 `3e2497b`, bridge 활성화 `0380dd3`, bootstrap 분리 `100e313`.
- [플랫폼 CI 40905905483](https://github.com/dsa04156/edgeai/actions/runs/40905905483) 전체 성공: 단위·계약·콘솔, 데이터/객체 저장소, amd64·arm64 Runner, 실제 DB를 연결한 빌드 이미지 시험, 이미지 발행·GitOps 고정.
- [KRR bridge CI 40905905154](https://github.com/dsa04156/edgeai/actions/runs/40905905154) 성공: bridge 시험·이미지 빌드·비root/읽기 전용 rootfs 실행·API 제한 검증·발행. 익명 GHCR manifest 조회도 HTTP 200.

| 실행 중인 Pod 이미지 | imageID의 digest |
|---|---|
| `ghcr.io/dsa04156/edgeai-api` | `sha256:696434c67d20bd39eeee606bb1059afc4297795f83ed6fc2ad66d5a59e73ad94` |
| `ghcr.io/dsa04156/edgeai-dashboard` | `sha256:2fd81ffc319ac921324b3b7f5c68025693bc9efe5f41bf84b0316605fcf8ce8b` |
| `ghcr.io/dsa04156/edgeai-krr-bridge` | `sha256:484af0d5232144e1d270f361c08495b8599997aa1565dd04467372d72348052f` |

세 Pod 모두 Ready, 재시작 0을 확인했습니다. Argo CD `edgeai-dev`는 `100e313`에서 Synced/Healthy·sync Succeeded입니다.

초기 bridge 동기화는 AppProject가 ClusterRole/Binding을 허용하지 않아 실패했습니다. 저장소의 기존 node-reader/runtime bootstrap 규칙에 맞춰 동일한 읽기 권한을 `deploy/kubernetes/bootstrap/krr-bridge-rbac.yaml`로 분리했습니다. 같은 이름의 기존 리소스가 없음을 확인하고 server dry-run 후 적용했습니다. GitOps 렌더에는 클러스터 RBAC가 없고 AppProject의 `clusterResourceWhitelist: []`도 유지합니다. KRR 계정은 `list deployments` yes, `create pods` no입니다.

배포 전 PostgreSQL custom-format dump와 Deployment 설정을 Git 밖에 보관했습니다. DB dump 3,912,361 B, SHA-256 `512c006fde6d72954a5fc04608b9c60aa748c3732d389762cb5df75f690ca335`, `pg_restore --list` 616행 확인. 실제 복원 시험은 수행하지 않았습니다. API 기동 로그와 DB 조회에서 `70 | resource profile | success`를 확인했습니다.

| 운영 확인 | 결과 |
|---|---|
| `/api/health`, `/ui/main/resource-profiles` | HTTP 200 |
| 웹 capabilities | HTTP 200, `prometheus:true`, `krr:true`, `v1.31.1` |
| 웹 자원 프로파일 목록 | HTTP 200, 0건(운영 예시 데이터 생성 없음) |
| 웹 `workloads?namespace=edgeai&hours=1` | HTTP 200, 13개 |
| API Pod → bridge Service `/healthz` | HTTP 200, uid 10001, 고정 KRR 기반 digest 일치 |
| bridge Pod의 실제 KRR 실행 | `COMPLETED`, scan 10개, `edgeai-api/api` 요청 CPU 0.393 core·RAM 530579456 B. 해당 워크로드의 24시간 자료에 대한 결과이며 다른 모델의 요구량이 아님 |
| 실제 Chromium으로 운영 화면 로드 | 서비스 선택 가능, JavaScript 오류 0·실패 HTTP 요청 0, 스크린샷 확인 |
| 기존 `/api/control-plane/offload-lab`, Hawkeye safety | HTTP 200 |

운영의 자원 요청값·오프로딩 실행·프로파일 저장은 변경하지 않았습니다. VPA/HAMi 설치는 수행하지 않았습니다.

## 남은 범위

- 웹·API·bridge 운영 배포는 완료했습니다. 서비스 프로파일 저장·전달 계약의 쓰기 시험은 격리 DB에서 수행했으며, 운영에는 예시 프로파일을 추가하지 않았습니다.
- DCGM Pod 표시가 없어 GPU는 장치 전체 값만 제공합니다. Jetson·Spark·Intel·Hailo exporter의 기간 조회는 연결하지 않았습니다.
- VPA·HAMi 설치, 워커 변경, 2-1의 YAML 생성은 범위 밖입니다.
