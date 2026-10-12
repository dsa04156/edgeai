# 사용자 정의 VD 공통 처리 검증

검증일: 2026-10-12. 작업 브랜치 `feat/vd-processing`, 기준 커밋 `4c832f0`.
별도 worktree에서 구현했으며 원래 작업 디렉터리의 미커밋 변경은 수정하지 않았다.

## 구현 범위

| 요구사항 | 구현과 검증 경로 |
| --- | --- |
| 물리 센서와 측정 항목 선택 | 기존 DeviceModel/Device CRD와 VD v2 원본 바인딩 재사용. 숫자 처리 입력을 발행·등록 시 검증 |
| 처리 조합과 파라미터 | 필터(저역·고역), 이동평균, RMS, FFT, 임계값. Dashboard에서 추가·삭제·순서 변경·설정 |
| 재사용 가능한 엔진 | `runner/edgeai_runner/processing.py`, SERVICE 공통 명령 `python3 -m edgeai_runner.processing` |
| 설정 저장과 Runtime 실행 | 발행 VD Profile의 processing → Run의 고정 입력 → 기존 VD supervisor/Runner → 검증된 Result |
| 다른 VD·AI 입력 | BATCH 의존성의 고정 artifact. 파생 VD는 artifact-only 원본을 지원하고 참조 AI는 처리 결과의 숫자 값을 사용 |
| 생성·수정·실행·상태·결과 UI | 기존 템플릿 발행·VD 등록·버전 전환·Runtime 실행·Task 상태 경로에 처리 편집기와 결과 내용 조회 연결 |

Mapper는 센서가 연결된 대상 Edge 노드에서 프로토콜별로 공유한다. VD나 센서마다 수집 프로그램·Pod를 생성하지 않는다.
기존 Mapper가 적용한 scale/offset 결과를 사용하며 엔진에서 다시 환산하지 않는다.

## 설정과 표본의 일관성

공유 서버 수집기는 Mapper twin의 서로 다른 관측 시각만 저장한다. 반복 조회를 새 표본으로 세지 않는다.
이력은 Device UID·측정 항목·설정 체크섬으로 분리한다. V51은 이전 이력을 삭제하지 않고 `legacy`로 보존하며 새 창에서 제외한다.
관리 API의 설정 변경 시각 이전 값은 제외한다. 시각만으로 비동기 적용을 증명할 수 없으므로 다음 식별자도 확인한다.

1. Compiler가 정규화된 수집 Profile의 체크섬을 visitor `configurationSha256`에 넣는다.
2. Mapper는 실제 수집에 사용하는 cached visitor의 식별자를 calibrated 값과 함께 DMI twin metadata `edgeaiConfigurationSha256`로 보고한다.
3. 서버는 보고 식별자와 현재 Profile이 일치하는 표본만 수집·고정한다. 새 시각으로 보고한 이전 설정 값도 제외한다.

새 등록·설정 저장에는 새 Mapper 이미지가 필요하다. 설정을 유지한 기존 Mapper와 기존 SERVICE/VD는 호환된다.
새 설정이 적용되지 않았으면 `VD_SOURCE_CONFIGURATION_PENDING`, 창이 부족하면 `VD_PROCESSING_WINDOW_PENDING`으로 Run 생성을 막는다.
설정과 표본은 Run 생성 때 고정하며 재시도에서 재사용한다.

처리 입력은 총 1 MiB, 사용자 매개변수 병합은 별도 여유를 포함해 1,179,648 bytes, Runner claim 응답은 2 MiB까지 허용한다.
V52는 PostgreSQL JSONB 텍스트 표현의 여유를 포함한다. 기존 일반 요청과 비처리 입력의 한도는 유지한다.
표본 1–1024개, 파이프라인·단계 각각 최대 16개이며 전체 입력 예산을 넘으면 선택 항목·표본을 줄이라는 오류를 반환한다.

## 테스트

| 검사 | 결과 |
| --- | --- |
| Backend 전체 단위 테스트 (`scripts/test/test-unit.sh`) | 193 passed, failures/errors/skipped 0 |
| 실제 PostgreSQL 전체 통합 테스트 (`scripts/test/test-integration.sh`) | 253 passed, failures/errors/skipped 0 |
| Runner 전체 테스트 (`scripts/test/test-runner.sh`) | 121 passed |
| Mapper (`scripts/test/test-mapper.sh`) | 모든 패키지 `go test -race` 통과 |
| Mapper Linux 정적 빌드 | amd64·arm64 통과 |
| OpenAPI 생성 비교·Controller·Swagger 계약 (`scripts/test/test-contract.sh`) | 통과 |
| Dashboard 타입·lint·production build | 통과, standalone에 프로젝트 문서 203개 포함 |
| 관련 Dashboard desktop/mobile Playwright | 14 passed: managed-vd, results, vd-execution, vd-processing, vd-run |
| 실제 Sense HAT 수용 테스트 (`scripts/test/test-vd-sensor.sh`) | 4 passed, failures/errors/skipped 0 |

처리 테스트는 알려진 정현파 FFT의 Hz·단측 진폭, DC/Nyquist 처리, 불규칙 관측 시각 거부, 창 크기·순서·자료형 검증을 포함한다.
실제 Runner claim으로 4개 항목 × 1024개 관측값을 전달해 이전 256 KiB 한도를 넘는 처리 실행을 검증했다.
실제 자식 SERVICE에서 기존 `PYTHONPATH` 유지도 검증했다.
실제 PostgreSQL 회귀는 같은 UID/Model의 배율 변경 뒤 이전 설정 식별자로 보고한 새 시각의 표본 세 개를 모두 제외하고 새 식별자의 창만 사용했다.
Mapper 회귀는 실제 수집 루프·HTTP 센서 fixture·gRPC DMI 경로에서 scale 1/값 20/식별자 A와 scale 2/값 40/식별자 B를 함께 확인했다.
동일 설정을 다시 저장하면 관측 cutoff가 바뀌지 않는다.

처음 전체 통합 테스트와 계약 테스트를 같은 Gradle 출력 디렉터리에서 병렬 실행했을 때 클래스 탐색이 실패했다.
빌드를 순차 실행해 재확인했으며 최종 결과는 위 표에 기록한다.

## 실제 센서 경로

모든 경로가 `SUCCEEDED`이고 고정 S3 객체의 버전·길이·SHA-256을 확인했다. 공통 처리 Result는 서버 내용 조회 API에서도 다시 검증했다.

| 경로 | 실제 관측·결과 | 결과 SHA-256 |
| --- | --- | --- |
| 기존 온도 SERVICE | 48.372684478759766 °C → 119.07083206176758 °F, 314 bytes | `bf2ddc3826cacd7400f69754c0ad87f7dcd3e76bb38f894e97c1234e4f78c0b8` |
| 공통 엔진, window 1 | 48.372684478759766 | `75758d5caac8912e263cf83c2da178f9918f04f59801ef9187669b3c2f5512af` |
| 공통 엔진, 서로 다른 3개 표본 이동평균 | 48.414737701416016 | `d3dfff5e463e695d901ec5e50d2c493bae6912b2aa4b9df9aa10435711da44d9` |
| 센서→processing VD→RMS VD→참조 AI→Result | 입력 48.390708923339844, score 97.78141784667969 (=abs(input)×2+1) | `150e5f7feb3cbe4bc28e792eb4eabba75c3d57204041a9a593af45c6a37530e2` |

세 표본의 실제 UTC 시각과 값:

| observedAt | value |
| --- | --- |
| 2026-10-12T03:24:51.827Z | 48.372684478759766 |
| 2026-10-12T03:25:06.126Z | 48.48081970214844 |
| 2026-10-12T03:25:21.526Z | 48.390708923339844 |

실제 Device는 `sensehat-001`, UID `64715adb-f43c-4984-a489-fcc794983eb6`이다.
첫 세 표본 테스트는 대기 25초·수신 허용 30초에서 창을 채우지 못했다. 실제 보고 간격은 약 14–15초였다.
최종 테스트는 Profile 원본 수신 허용 시간을 120초로 설정하고 최대 90초 기다려 서로 다른 세 관측값을 확인했다.
제품의 표본 중복 방지·수신 시간 검사는 유지했다.

센서와 Mapper/DeviceStatus는 실제 장치·클러스터를 읽는다. Spring과 PostgreSQL은 격리 테스트 DB, MinIO는 이 작업 전용 로컬 서버를 사용한다.
VD supervisor와 Runner 및 SERVICE 자식 프로세스는 실제 실행한다. Kubernetes Pod 인증·Ready 경계만 fixture다.

## Standards

독립 Standards 리뷰의 최종 보고를 정리했다.

- 원래 P1 설정 이력 혼합은 해결했다. Compiler의 Profile 식별자를 같은 설정으로 수집한 Mapper 값에 포함하고 수집기·Run 캡처에서 확인한다. 시각이 새로워도 이전 collector 값은 새 창에 들어가지 않는다.
- 원래 P2 표본 수와 byte 예산 불일치는 해결했다. 입력·매개변수·DB·claim 한도를 맞추고 전체 입력 초과 오류와 큰 실제 Runner 실행을 확인했다.
- Hard requirements: 계층 경계를 유지했고 기존 migration을 수정하지 않았다. 새 필드의 OpenAPI와 생성 타입을 갱신했다. 남은 차단 이슈 0개.
- Judgment: 여러 언어의 처리 설정 검증 중복, 수집기의 다른 서비스 reading 검증 호출은 유지보수 관찰 2개이며 기능 완료를 막지 않는다.

초기 차단 지적 2개 해결, 추가 설정 전파 경합 해결, 최종 미해결 차단 0개.

## Spec

독립 Spec 리뷰의 최종 보고를 정리했다.

- 원래 P1 보정 설정/표본 창 혼합은 해결했다. 각 표본은 실제 적용 식별자가 현재 설정과 일치해야 한다. 비동기 전파 경합도 양쪽 경계의 회귀 테스트로 확인했다.
- 원래 P2 기존 SERVICE의 `PYTHONPATH` 덮어쓰기는 해결했다. Runner 경로를 앞에 추가하고 기존 경로를 보존한다.
- Hard requirements: 기존 Mapper 보정 재사용, 기존 구성 호환, Backend→Runtime→Dashboard와 다른 VD/AI 연결을 충족한다. 추가 범위 확장이나 빠진 사용자 요구사항 지적은 없다.
- 새 Mapper/Runner 이미지의 운영 적용은 수행하지 않았다. 실제 Kubernetes Pod 경계 검증을 미검증으로 남기는 것은 사용자 요구사항에서 허용한다.

초기 차단 지적 2개 해결, 관련 추가 경합 해결, 최종 미해결 차단 0개.

## 미검증과 남은 운영 항목

- 실제 Kubernetes에서 새 이미지로 VD Pod를 시작하는 검증은 수행하지 않았다. Docker socket 권한이 없고 kind가 설치되지 않았으며 kubeconfig에는 운영 context만 있다. 권한·context를 바꾸거나 운영에 테스트 리소스를 배포하지 않았다.
- Mapper 정적 binary는 두 아키텍처에서 빌드했으나 새 Mapper/Runner 컨테이너 이미지 build/push·운영 배포는 하지 않았다. 운영 적용 시 새 이미지 digest가 필요하다.
- `sensor_sample` 이력 삭제는 구현하지 않았으므로 운영 보관 기간·저장 공간 정책을 결정해야 한다.
- 이 기능은 BATCH 처리와 최신 twin 관측을 사용한다. 고속 연속 스트림 DSP와 실제 AI 모델의 정확도 검증은 해당 범위에 포함되지 않는다.

운영 센서 설정·배포·데이터를 변경하거나 삭제하지 않았다. 테스트 스크립트는 자신이 생성한 격리 DB만 정리한다.

## 수정 파일

기준 커밋 대비 60개 파일:

```text
.interface-design/system.md
backend/adapters/src/main/java/io/edgeai/adapters/repository/JdbcSensorSampleRepository.java
backend/app/src/main/java/io/edgeai/app/config/VirtualDeviceConfiguration.java
backend/app/src/main/java/io/edgeai/app/controller/ResultController.java
backend/app/src/main/java/io/edgeai/app/exception/ResultExceptionHandler.java
backend/app/src/main/java/io/edgeai/app/service/ManagedDeviceService.java
backend/app/src/main/java/io/edgeai/app/service/ResultService.java
backend/app/src/main/java/io/edgeai/app/service/SensorHistoryService.java
backend/app/src/main/java/io/edgeai/app/service/VDSensorInputsService.java
backend/app/src/main/java/io/edgeai/app/service/VirtualDeviceService.java
backend/app/src/main/java/io/edgeai/app/support/DeviceProfileCompiler.java
backend/app/src/main/java/io/edgeai/app/support/ManagementAuditRoutes.java
backend/app/src/main/java/io/edgeai/app/support/ProcessingInput.java
backend/app/src/main/java/io/edgeai/app/support/ProfileJson.java
backend/app/src/main/java/io/edgeai/app/support/RuntimeParameters.java
backend/app/src/main/java/io/edgeai/app/support/VirtualDeviceInput.java
backend/app/src/main/resources/db/migration/V49__vd_sensor_sample_history.sql
backend/app/src/main/resources/db/migration/V50__vd_processing_artifact_inputs.sql
backend/app/src/main/resources/db/migration/V51__sensor_sample_configuration_identity.sql
backend/app/src/main/resources/db/migration/V52__vd_processing_input_budget.sql
backend/app/src/test/java/io/edgeai/app/integration/VDSensorBatchIntegrationTest.java
backend/app/src/test/java/io/edgeai/app/integration/VDTaskArtifactIntegrationTest.java
backend/app/src/test/java/io/edgeai/app/service/ManagedDeviceServiceTest.java
backend/app/src/test/java/io/edgeai/app/service/ResultContentTest.java
backend/app/src/test/java/io/edgeai/app/support/DeviceProfileCompilerTest.java
backend/app/src/test/java/io/edgeai/app/support/RuntimeParametersTest.java
backend/app/src/test/java/io/edgeai/app/support/VirtualDeviceInputTest.java
backend/domain/src/main/java/io/edgeai/domain/repository/SensorSampleRepository.java
backend/domain/src/main/java/io/edgeai/domain/vd/VDProfileSpec.java
contracts/openapi/platform-api.yaml
contracts/openapi/runner-api.yaml
contracts/profiles/processing.schema.json
contracts/profiles/vd-profile.schema.json
dashboard/app/api/control-plane/[...path]/route.ts
dashboard/app/profiles/processing-fields.tsx
dashboard/app/profiles/profile-fields.tsx
dashboard/app/profiles/vd-profile-fields.tsx
dashboard/app/workflows/result-content.tsx
dashboard/app/workflows/vd-sensor-inputs.tsx
dashboard/app/workflows/workflow-console.tsx
dashboard/docs-navigation.json
dashboard/lib/api-schema.d.ts
dashboard/lib/json-record.ts
dashboard/lib/processing.ts
dashboard/lib/vd-profile.ts
dashboard/tests/results.spec.ts
dashboard/tests/vd-execution.spec.ts
dashboard/tests/vd-processing.spec.ts
dashboard/tests/vd-run.spec.ts
docs/evidence/vd-processing.md
docs/guides/vd-processing.md
docs/requirements/vd-processing.md
mappers/internal/driver/modbus.go
mappers/internal/runtime/configuration_test.go
mappers/internal/runtime/panel.go
runner/edgeai_runner/main.py
runner/edgeai_runner/processing.py
runner/examples/linear.py
runner/tests/test_processing.py
runner/tests/test_runner.py
```
