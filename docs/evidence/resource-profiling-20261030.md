# 서비스 자원 프로파일 시험·연동 기록 (2026-10-30)

[서비스 자원 프로파일](../guides/resource-profiling.md) 구현의 검증 근거입니다. 기준 커밋 `4b497b6`의 격리 작업 트리에서 수행했고 **운영 배포·운영 DB 적용·클러스터 변경은 하지 않았습니다.**

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

## 남은 범위

- bridge 이미지는 CI에서 아직 발행되지 않았습니다. 발행 후 `enable-resource-profiling.py --digest`로 고정하고 배포는 검토 후 별도로 수행합니다.
- DCGM Pod 표시가 없어 GPU는 장치 전체 값만 제공합니다. Jetson·Spark·Intel·Hailo exporter의 기간 조회는 연결하지 않았습니다.
- VPA·HAMi 설치, 워커 변경, 2-1의 YAML 생성은 범위 밖입니다.
