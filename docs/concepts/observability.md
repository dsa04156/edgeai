# 관측값과 상태 읽기

상태를 판단할 때는 값의 원본, 수집 시각, 의미를 함께 확인합니다.
목록에 존재하는 자원, 정상 연결, 실제 데이터 수신, 작업 완료는 서로 다른 사실입니다.

## 값은 어디에서 오는가

| 표시 | 원본 | 의미 |
|---|---|---|
| 서버·엣지 분류 | Kubernetes 노드 라벨 | 장비 역할 |
| 노드 Ready | Kubernetes Ready 조건 또는 이를 수집한 메트릭 | Kubernetes 관점의 준비 상태 |
| CPU·메모리·GPU·NPU | Prometheus exporter | 수집 시각의 자원 측정 |
| 센서 UP/DOWN | EdgeX operatingState | EdgeX가 보고한 장치 운영 상태 |
| 센서 측정값 | EdgeX Core Data | 저장된 Reading과 원본 시각 |
| 앱 장치 연결 | 현재 Device Session의 Observation | 해당 에이전트가 보고한 연결 상태 |
| VD Ready | Pod 관측과 인증된 supervisor 상태 | VD 작업을 받을 실행의 준비 상태 |
| 작업 성공 | Task·Attempt 및 검증된 Result | 플랫폼 실행 계약의 완료 상태 |

## 실시간의 의미

화면 새로고침 주기와 exporter 수집 주기는 다릅니다. 화면을 5초마다 갱신해도
30초마다 수집되는 CPU 지표에서 더 빠른 실제 변화를 얻을 수는 없습니다.
CPU는 최근 두 표본의 `irate`를 쓰며 PromQL의 `[2m]`은 표본 탐색 범위입니다.
GPU는 최신 사용률 gauge를, Intel NPU는 1분 rate를 사용합니다.

호스트 메모리 사용량은 전체에서 가용 메모리를 뺀 값입니다.
GPU 메모리와 호스트 메모리는 서로 다른 지표입니다. 장착 정보가 있어도 사용률 exporter가 없으면 사용량은 알 수 없습니다.

## 빈 값과 실패

- `0`: 정상적으로 수집한 0 값입니다.
- `—` 또는 미확인: 값이 없거나 시각·수집 상태 검사를 통과하지 못했습니다.
- `DISABLED`: 해당 조회 기능이 설정에서 꺼져 있습니다.
- `UNAVAILABLE`: 원본 서비스 조회가 실패했습니다.
- 정상 빈 목록: 조회에는 성공했지만 표시할 항목이 없습니다.

메트릭은 원본 시각과 exporter 상태를 검사합니다. 센서 상세는 조회 실패 시 마지막 데이터를
오류 안내와 함께 남길 수 있으므로 이를 새 측정값으로 해석하지 않습니다.

## 비동기 작업

HTTP 202, Argo 요청 수락, Operation 성공, Result 확정은 같은 상태가 아닙니다.
배포는 Argo와 Pod, Spring 작업은 Run·Task·Result, 센서 쓰기는 실제 장치 결과를 확인합니다.

다음 단계: [메트릭 연결](../guides/node-metrics.md), [문제 해결](../operations/troubleshooting.md).
