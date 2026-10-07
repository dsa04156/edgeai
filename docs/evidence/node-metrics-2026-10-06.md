# 노드 메트릭 연결 검증 — 2026-10-06

사용자 요청: 기존 Prometheus를 백엔드와 프론트에 연결한다. 코드는 수정하되 앱 실행은
사용자가 담당한다. Prometheus 주소·지표·라벨은 직접 확인하라는 요청에 따라 읽기 전용으로 조사했다.

## 확인한 데이터

- kube-system의 `prometheus-kube-prometheus-prometheus` 서비스와9090 포트를 확인했다.
- Java `PrometheusQueries.query()`가 생성한 동일 쿼리를 기존 Prometheus에 POST했다.
  응답은 success·vector210개·warnings 없음이었다.
- 그 응답을 실제 `PrometheusNodeMetrics.decode()`와 조회 당시 시각으로 정규화했다.
  노드10개·측정값42개·미연결 시계열0개이며 모든 측정값의 freshness 검사를 통과했다.
- CPU/메모리10개 노드, DCGM GPU2개, Jetson GPU2개, Spark GPU1개,
  Mobilint NPU1개, Intel NPU1개의 사용률을 확인했다. 가속기별 추가 항목은 수집되는 범위만 연결했다.
- DCGM Hostname의 Pod 이름, node-exporter nodename의 대소문자 차이를 실제 응답에서 확인해
  Kubernetes 메타데이터로 정규화했다. collector 성공 지표가 up과 OR 결합에서 사라지는 문제를
  독립 태그로 구분하고 같은 쿼리를 다시 조회해 전용 상태5개가 모두 포함됨을 확인했다.

## 코드 검증

모든 명령은 `rtk proxy`를 통해 실행했다.

```bash
backend/gradlew -p backend :app:test \
  --tests io.edgeai.app.service.NodeMetricsTest \
  --tests io.edgeai.app.controller.NodeMetricsControllerTest \
  --tests io.edgeai.app.support.ManagementAuditRoutesTest \
  --tests io.edgeai.app.controller.DeviceControllerTest \
  --tests io.edgeai.app.config.SwaggerUiTest --console=plain
corepack pnpm contract:generate
corepack pnpm --filter @edgeai/dashboard typecheck
corepack pnpm --filter @edgeai/dashboard lint
corepack pnpm --filter @edgeai/dashboard build
```

- Gradle XML:21개, 실패0·오류0·건너뜀0. 새 메트릭 시험13개와 관련 기존 시험8개다.
- 측정값0·미수집·매핑 충돌·다중 가속기·중복 지표·노후 값·exporter 장애·collector 실패·
  비정상 값·부분 응답·캐시·비활성/실패 상태와 MockMvc JSON 계약을 확인했다.
- OpenAPI에서 TypeScript 타입 생성, 프론트 타입·lint·production build가 모두 exit0이었다.
- `git diff --check`와 `bash -n scripts/test/test-contract.sh`도 exit0이었다.

## 검증 경계

새 API 앱 서버·Dashboard 서버를 실행하거나 재시작하지 않았다. Kubernetes 리소스도 변경하지 않았다.
위 검증은 기존 Prometheus 읽기, Java 쿼리/정규화, 단위·MockMvc 시험, 프론트 정적 검사와 빌드다.
실행 중인 새 API를 통한 화면 연결과 브라우저 렌더링은 사용자가 실행한 뒤 확인해야 한다.
13080에서 새 기능이 동작한다고 확인한 것은 아니다. 전체 기존 E2E는 이번에 재실행하지 않았다.
