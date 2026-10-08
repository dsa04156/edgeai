# VD 센서 BATCH 검증

2026-10-08, `feat/vd-sensor-batch`에서 검증했습니다. 기준 구현은 `4853a39`, 기능 구현은
`3e3ed56`, 리뷰 수정은 `817a89d`입니다. 브랜치의 구현·검증 결과이며 운영 반영 완료를 뜻하지 않습니다.

## 검증 범위

VD에서 원본 센서의 측정 항목·허용 시간을 선택하고, Run 생성 시 Task별 입력을 고정합니다.
Runner는 고정된 `edgeai.vd-input/v1`을 받아 SERVICE를 실행합니다. Task 상세는 실제 사용한 값·시각·
센서 UID·모델·binding·VD revision을 표시하고, Result는 저장된 결과의 해시·크기를 검증합니다.

Sense HAT 실측 시험은 실제 Kubernetes Device CRD를 읽고 호스트의 supervisor·Runner를 실행합니다.
Spring HTTP, 분리 PostgreSQL DB, 별도 MinIO와 결과 파일은 실제 실행체를 사용했습니다.
Pod 신원·인증과 배치 경계는 fixture입니다. 실제 Kubernetes VD Pod에서 이 새 입력 경로를
실행하는 수용, MQTT 연속 소비·재전송, 학습 모델 추론은 이번 완료 범위에 포함하지 않습니다.

## 시험 결과

| 검증 | 결과 | 근거 |
| --- | --- | --- |
| 백엔드 단위 | 188개 통과 | `scripts/test/test-unit.sh`의 `UNIT_TEST_COUNTS`; 이후 계약 시험이 app 단위 XML을 필터된 결과로 교체함 |
| 백엔드 통합 | 252개 통과, 실패·오류·skip 0 | `backend/app/build/test-results/integrationTest/` |
| 실제 supervisor·Runner·S3 결과/취소 | 2개 통과, 실패·오류·skip 0 | `VDTaskArtifactIntegrationTest`, `runtimeArtifactIntegrationTest` |
| 실제 Sense HAT 입력 수용 | 1개 통과, 실패·오류·skip 0 | `scripts/test/test-vd-sensor.sh`, `vdSensorAcceptanceTest` |
| PC·모바일 UI | 20개 통과 | `managed-devices.spec.ts`, `managed-vd.spec.ts`, `vd-run.spec.ts` |
| 외부 계약 | 통과 | `scripts/test/test-contract.sh`: OpenAPI 생성물·Controller·패키징된 Swagger/STREAM 스키마 |
| Dashboard | 모두 통과 | lint·typecheck·production build |
| 셸 문법 | 100개 통과 | 전체 `scripts/**/*.sh`에 `bash -n` |

시험 그룹은 서로 다른 검증 범위를 가지며 수를 합산해 고유 시험 수로 표현하지 않습니다.
통합 시험은 일회성 DB를 생성해 V1–V48을 적용하고 종료 시 삭제합니다. DB 생성 실패는
`BLOCKED`, exit 2로 확인했으며 기존 개발 DB를 시험 대상으로 대체하지 않습니다.

## 실측 결과

마지막 수용 시험은 CSRF·세션 쿠키를 사용한 공개 `POST /api/v1/workflow-runs`에서 201을 확인했습니다.
공개 Task 상세의 `vdInputs`가 저장된 입력과 같은지 대조했고, 공개 Result 조회의 SHA-256과
다운로드한 파일의 크기·내용을 검증했습니다. Run은 `SUCCEEDED`, 출력의 `sourceMode`는 `SENSOR`입니다.

| 항목 | 값 |
| --- | --- |
| 센서·측정 항목 | `sensehat-001` · `temp_humidity` |
| 센서 UID | `64715adb-f43c-4984-a489-fcc794983eb6` |
| 관측 시각 | `2026-10-08T11:56:21.181Z` |
| 입력 고정 시각 | `2026-10-08T11:56:34.457Z` |
| 입력 온도 | 48.31861877441406 °C |
| SERVICE 출력 | 118.97351379394532 °F (`C × 1.8 + 32`) |
| 결과 크기 | 313 bytes |
| SHA-256 | `e6b117a5038458492db344b536c12a810ee043cf337a128ca7356481dca5b00f` |
| Pod 경계 | fixture |

실측 JSON과 PC·모바일 스크린샷은 Git에서 제외한 `output/vd-sensor/`에 보관합니다.
JUnit 보고서는 `backend/app/build/test-results/`에 남깁니다. 자격 증명·서명 URL·시험용 키는
검증 기록에 포함하지 않습니다.

## 수용 기준별 확인

[수용 기준](../requirements/vd-sensor-batch.md)의 1–10번을 다음 근거로 확인했습니다.

| 기준 | 확인한 동작 | 주 근거 |
| --- | --- | --- |
| 1 | 항목·허용 시간 선택, 저장·조회, 오류 뒤 입력 유지 | `VDSensorBatchIntegrationTest`, `managed-vd.spec.ts` |
| 2 | UID·모델·항목 검사, binding 교체 이력 | `ManagedVDIntegrationTest`, `VDSensorBatchIntegrationTest` |
| 3 | Task별 값·시각·센서 신원·binding·revision 고정 | `VDTaskIntegrationTest` |
| 4 | offline·미수집·오래된 값·미래 시각·잘못된 값·신원 변경에서 Run 생성 거절 | `VDTaskIntegrationTest`, `ManagedVDIntegrationTest` |
| 5 | 순차·동시 같은 요청, 재시도·원본 변경 후 최초 입력 유지 | `VDTaskIntegrationTest`의 replay·claim/retry 시험 |
| 6 | 같은 센서를 쓰는 여러 VD의 입력 분리, 예약 `vdInputs` 위조 거절 | `VDTaskIntegrationTest` |
| 7 | 실제 프로그램 처리·검증 Result, 공개 Task 입력·Result 조회 | `VDTaskArtifactIntegrationTest`의 실측 수용 |
| 8 | PC·모바일 선택·오류·출처·결과 이동, 가로 넘침 없음 | 관련 Playwright 20개, 스크린샷 |
| 9 | 분리 DB, 기존 관리 인증 기대값 5건 정리 | 통합 252개, DB helper의 BLOCKED 시험 |
| 10 | 관리 웹·실센서 조회 정상, 소유한 시험 자원 정리 | 아래 정리 결과 |

Mapper가 scale·offset을 적용한 값은 공학 값입니다. `dataType`은 원시 센서 전송 형식이며,
변환된 값에 원시 정수·float32 범위 검사를 다시 적용하지 않습니다. 변환된 float32 값
`3.4e39`의 입력 수용과 유한 수·JSON 크기 제한을 시험했습니다.

## 리뷰

### Standards

최초 P2 지적 2건, 수정 후 남은 지적 0건입니다.

- 수신 시각 0을 1970년으로 표시하던 문제: 양의 유효 시각만 표시하고 나머지는 `미수집` 처리.
- 시험 DB 생성 실패가 exit 1로 끝나던 문제: `BLOCKED`, exit 2로 정리하고 기존 DB 보존 확인.

`817a89d` 재검토에서 추가 회귀·문서 규칙 위반을 발견하지 않았습니다.

### Spec

최초 P2 지적 2건, 수정 후 남은 지적 0건입니다.

- 동시 같은 요청이 센서를 다시 읽어 실패할 수 있던 문제: 최초 조회 전에 요청 키의 DB 트랜잭션
  잠금을 획득해 첫 커밋 뒤 기존 Run을 반환하도록 수정.
- scale 적용 float32 값에 원시 범위를 적용하던 문제: 기존 Mapper의 공학 값 계약에 맞게 수정.

각 지적의 실패 재현 후 수정 시험을 통과했고, `817a89d` 재검토에서 추가 회귀를 발견하지 않았습니다.
두 리뷰 축의 지적 수는 별도로 유지합니다.

## 시험 자원 정리와 다음 게이트

자동 생성된 시험 DB와 수동 생성했던 `edgeai_test_vd_sensor_batch`를 모두 삭제했습니다.
시험 전용 MinIO(127.0.0.1:19000/19001)를 종료하고 해당 데이터·로그를 제거했습니다.
시험 bucket·임시 키·supervisor/child는 시험 종료 정리에서 제거했습니다.
개발 DB의 Flyway 최신 버전은 45이고 `edgeai_test_%` DB는 0개입니다.
기존 backend health는 `UP`, 관리 웹 health는 200입니다. Arduino·Sense HAT은 `ONLINE`이며
측정 항목은 각각 6개·11개로 유지됐습니다.

다음 게이트는 같은 온도 SERVICE를 실제 VD Pod에서 실행해 노드 배치·Pod 신원·고정 입력·Result 출처를
대조하는 것입니다. Pod 재시작·재시도에도 입력이 유지되고 중복 결과가 생기지 않는지 함께 확인합니다.
MQTT 연속 실행과 실제 학습 모델 수용은 별도 후속 범위입니다.
