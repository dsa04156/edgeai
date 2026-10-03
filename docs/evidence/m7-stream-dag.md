# M7 실제 다중 Runner STREAM → BATCH 검증

2026-10-03. [ADR0041](../adr/0041-public-stream-runs.md)의 공개 실행 연결을 실제 독립 Runner로
검증했다. 전체 M7·실제 Kubernetes·실장비 완료 기록은 아니다. 공개 STREAM 기본 비활성은 유지한다.

## 실제 연결한 경로

```text
DeviceSource a ─┐
               ├─ TLS MQTT → root Runner ─ STREAM → sink Runner
DeviceSource b ─┘                  │                      │
                              S3 result14            S3 result23
                                  └──── BATCH report Runner ────→ S3 report37
```

`StreamSourceCompletionIntegrationTest`의 두 DAG 시험은 공개 MockMvc Run 생성으로 시작한다.
두 장치의 현재 session을 고정하며 수동 SQL, route generation 생성, claim 호출, 완료 허가나
체크포인트 응답 대역을 사용하지 않는다. 시험 소유자는 Pod 생성·신원 확인과 종료 관측만 대신한다.
각각 독립된 `runner.py`가 실제 TLS HTTP claim부터 배정 조회, 지속 모델 subprocess, TLS MQTT,
heartbeat, 외부 S3 checkpoint, 공동 완료와 고정 S3 Result commit까지 수행한다.
실제 Spring scheduled worker가 모든 claim 이후 route를 준비하고 broker 권한을 발급·회수한다.

첫 입력은 a=4, b=5이며 root와 sink 양쪽 서버 checkpoint의 상태 SHA가9임을 확인한 후 다음
입력을 보낸다. 두 번째 입력 a=2, b=3으로 root의 누적 합은14가 된다. root가 출력한 값은9와14이므로
sink는23이다. 두 Result가 봉인된 후에만 세 번째 BATCH Runner를 시작한다. 이 Runner는 고정 S3
입력 파일 두 개를 실제로 내려받아 `{"sourceMode":"SYNTHETIC","sum":37,"inputs":{"root":14,"sink":23}}`
을 만든다. 세 Result의 producer Pod UID, 실제 MinIO version 파일 bytes·길이·SHA-256과 로컬
계산 파일을 대조한다. 두 Task의 공동 완료 시각도 같고 모든 route는 한 세대만 생성·회수된다.

취소 시험은 두 실제 스트림 Runner가 상태9를 외부 checkpoint로 확정한 시점에 공개 Task 취소를
호출한다. 두 프로세스가 실제 종료한 뒤에만 종료 관측을 전달한다. sink=CANCELLED,
root/report=SKIPPED이며 최종 결과와 BATCH Attempt가 없다. 실제 broker 권한 회수도 확인한다.

`runner/examples/stream_report.py`는 이 합성 시나리오의 파일 집계 예제다. 두 입력의 `sum`이 정수인
정확한 파일 형식만 받아 `report`를 쓰며 실장비·모델 정확도 또는 성능 수용을 뜻하지 않는다.

## 검증 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 source/Runner 완료 및 다중 Runner DAG | `20261003T100717Z-b96f3a27` | 4개 PASS, 성공·중간 취소 및 기존 새 볼륨 최종 복구·완료 전 취소 |
| 전체 실제 S3/DB 통합 | `20261003T100859Z-b5443baf` | 34개 PASS, 기존 Result/Remote/VD/checkpoint 회귀 포함 |

각 검사는 자체 PostgreSQL DB·loopback MinIO·TLS broker를 생성하고 종료 시 제거했다.
프로젝트 DB와 V1–V25 migration은 변경하지 않았다. Python 자식 프로세스는 직접 저장소 경로를
설정하며 외부 PYTHONPATH에 의존하지 않는다. 출력은 고정 검사 코드이며 토큰·서명 URL·원시 데이터는
로그에 쓰지 않는다. 이 시험들은 기존 `runtimeArtifactIntegrationTest`에 포함되어 CI 저장소 작업에서 실행된다.

## 남은 수용 범위

실제 Kubernetes Pod scheduling·TokenReview를 포함한 다중 스트림 종단과 `demo-multidevice`,
운영 TLS 배포, 인접 Task/Device를 함께 복구하는 그룹 인계는 남아 있다. M5 상태 복원/외부 시스템,
M8 부하, M9 종합 장애, M10 실장비 수용도 계속 별도 게이트다.
