# EdgeX 초기화 및 EdgeAI 레포 기준 재구성

최초 재구성 검증: 2026-10-06 17:50 KST. 아래 18:05 KST 행별 분리 보완이 현재 상태다. Context: `kubernetes-admin@kubernetes`.

## 확정 범위와 변경

사용자가 기존 EdgeX 데이터까지 삭제하고 현재 레포에 맞게 새로 구성하도록 명시 승인했다.
EdgeAI 애플리케이션 DB, 다른 프로젝트의 워크로드와 주 작업 디렉터리의 미커밋 Spring/UI 코드는
변경하지 않았다. 처음 별도 워크트리에서 만든 `deploy/edgex` 및 배포·검증 스크립트는
사용자의 작업 경로 정정에 따라 `/home/jinuk/codex-work/edgeai`로 반영했다.
앞으로 이 주 작업 폴더를 기준으로 작업한다.

- 이전 `edge-ai-workspace`의 ArgoCD Application `edgex-telemetry`를 자동 동기화 해제 후
  orphan 방식으로 삭제했다. 이전 Git 정의가 새 구성을 복구·덮어쓰지 않는다.
- 기존 EdgeX Deployment/DaemonSet/StatefulSet/초기화 Job을 종료했다. Discovery 4개,
  Adapter Controller, Ingest Gateway 및 전용 권한·설정·자격 증명을 제거했다.
- 이미 RETIRED이고 consumer 0인 AdapterRuntime 5개를 제거했다. 이 객체들의 삭제가 기존
  controller finalizer에서 대기하여, 해당 adapter Deployment/Pod/Service가 없음을 확인한 후
  obsolete finalizer를 제거했다. 클러스터 전체 객체에 일괄 finalizer 제거를 하지 않았다.
- EdgeX PostgreSQL PVC와 Adapter Controller PVC를 삭제하고 새 DB PVC와 무작위 자격 증명을
  만들었다. 다른 프로젝트 소유 `sensor-anomaly-demo-state` PVC는 변경하지 않았다.
- 기존 핵심 Service 객체는 재사용해 ClusterIP와 Spring 접속 주소를 보존했다.
- 코어6 + 실장치 수집2 = 상시 Pod8개. 공통 설정 bootstrap Job은 별도 완료 상태다.
- 센서는 Arduino aggregate 등록1개와 Sense HAT resource-group 등록6개로 구성했다.
  기존 테스트용 virtual-* 등록과 사용하지 않는 Modbus/MQTT 등록·프로필은 새 DB에 만들지 않았다.

## 발견한 원인과 보완

기존 Arduino 등록의 Serial Port는 `/dev/arduino-001`이었으나 컨테이너 마운트는
`/dev/edgeai/arduino-001`이었다. 새 선언에서 실제 마운트 경로와 기존 펌웨어의
`on-demand-read` 복구 설정을 사용했다. 이후 Metadata UP뿐 아니라 6개 raw resource의
실제 신규 Event 수신을 확인했다. 펌웨어 변경이나 actuator write command는 실행하지 않았다.

새 DB 첫 기동에서 Core Data가 Metadata HTTP 준비 전에 device cache를 초기화하다 EOF로
종료했다. Core Data init container에 Metadata `/api/v3/ping` 대기를 추가했다.
대기는 최대90회·2초 간격이며 무한 루프가 아니다. 재적용 후 현재 Core Data Pod는 restart0이다.

## 검증 결과

| 확인 | 결과 |
|---|---|
| `bash scripts/test/test-edgex.sh` | 구성 회귀 테스트5개 및 shell 문법 PASS |
| `bash scripts/ops/edgex.sh check kubernetes-admin@kubernetes` | 전체 server dry-run exit0 |
| `kubectl --context kubernetes-admin@kubernetes diff -k deploy/edgex` | exit0, 선언과 배포 차이 없음 |
| 상시 Pod | 8/8 Running·Ready, 최종 Pod 전부 restart0 |
| DB | 신규 20Gi local-path PVC Bound |
| 센서 등록 | 7개, 모두 UNLOCKED / UP |
| 센서 데이터 | 7개 등록 모두 두 번의 조회 사이 Event origin 증가 |
| 데이터 신선도 | 최종 조회에서 Arduino 약1.01초, Sense HAT 약0.58–0.62초 |
| Arduino resource | temperature_raw, light_raw, magnetic_raw, acceleration_x/y/z_raw 수신 |
| 실행 중 Spring 경유 API | `/api/control-plane/infrastructure`: sensorsStatus AVAILABLE, 센서7·노드10 |
| 이전 ArgoCD 앱 | edgex-telemetry 없음 |

검증 근거는 실제 클러스터/API 응답이다. 이전의 sensor7/13 관측이나 mock 응답을 재사용하지 않았다.
초기 기동의 경고 이벤트는 남아 있을 수 있으며, 최종 정상 상태와 첫 기동 무오류를 혼동하지 않는다.

## 백업·복구 근거

초기화 전 EdgeX DB custom-format dump 422,292,214바이트를 저장했다.
`pg_dump` exit0, 로컬 PostgreSQL16 `pg_restore --list`와 `pg_restore --file=/dev/null` exit0으로
목차와 전체 아카이브 읽기를 검증했다. 실제 복구 연습은 수행하지 않았다.

위치: `/mnt/data3tb/edgeai/backups/edgex-rebuild-20261006T0840Z/`.
보호된 디렉터리에 DB dump·배포 스냅샷·이전 Secret을 저장했으며 Git에는 포함하지 않는다.
이전 데이터를 복구하려면 현재 수집 중지, 이전 자격 증명/DB/구성의 일관된 복원을 수행해야 한다.
새 DB에 이전 전체 dump를 즉흥적으로 병합하지 않는다.

## 남은 경계

- 이 레포의 매니페스트를 직접 적용했다. 커밋/push 및 새 ArgoCD 자동 동기화는 실행하지 않았다.
- 공식/기존 검증된 커스텀 이미지를 digest로 재사용했다. 커스텀 드라이버 빌드 소스 이관과
  새 이미지 발행은 이 배포 변경에 포함하지 않는다. 사설 레지스트리 의존성이 남는다.
- 기존 Spring inventory 구현과 새 EdgeX 배포 파일은 모두 `/home/jinuk/codex-work/edgeai`의
  미커밋 작업이다. 기존 Spring/UI 변경을 덮어쓰지 않고 새 파일과 문서 추가분만 반영했다.
- Spring의 장치 등록/명령 기능, 실제 AI 추론 연결, 단절 후 영속 재전송, 고가용성,
  공개망 인증·권한·장기 성능은 검증하지 않았다.
- 이전 state-aggregator 등 다른 프로젝트의 앱은 수정하지 않았다. 제거한 구형 Adapter/Gateway
  API를 사용하는 구형 관리 기능은 이번 새 구성에서 제공하지 않는다.
- Semantica 로컬 그래프의 선행 결정을 조회했으나 MCP 기록 도구가 연결되지 않아 새 결정은
  그래프에 저장하지 못했다. 확정 요구·변경·검증·한계는 이 문서에 남긴다.


## 18:05 KST 보완: 센서를 행별로 분리

사용자가 기존 행별 표시 요구를 재확인했다. 재구성 시 Arduino를 aggregate 하나로 묶은 것이
등록당 한 행을 그리는 기존 센서 표와 충돌했다. 주 작업 폴더 `/home/jinuk/codex-work/edgeai`에서
Arduino 온도·조도·자기·가속도 X/Y/Z의 단일 resource 프로필과 등록6개로 변경했다.
등록명은 `arduino-temperature-001`, `arduino-light-001`, `arduino-magnetic-001`,
`arduino-acceleration-x-001`, `arduino-acceleration-y-001`, `arduino-acceleration-z-001`이다.
모두 같은 physicalDeviceId와 Serial 연결을 공유하며 별도 물리 보드 또는 EdgeAI VD를 뜻하지 않는다.
Sense HAT는 기존 resource-group 6행을 유지하여 총12행이다.

Serial Device Service만 재배포했다. 새6개 등록의 UP·신규 수신을 먼저 확인한 후 중복되는
`arduino-001` 장치 등록과 미사용 `arduino-multisensor-v1` 프로필을 Metadata API로 제거했다.
DB/PVC 재초기화, Core Data 이력 삭제 API, Spring/UI 코드 변경은 실행하지 않았다.
변경 전 장치·프로필과 Serial 배포 스냅샷은
`/mnt/data3tb/edgeai/backups/edgex-sensor-rows-20261006T090348Z/`에 보관했다.

검증 결과:
- 구성 테스트5개와 shell 문법 PASS. 채널별 등록6개, 각 프로필 resource 일치, 전체12개,
  aggregate 부재, 공유 serial 연결을 회귀 검증한다.
- Serial server dry-run 성공, rollout 성공, 엣지 수집 Pod2개 Running/Ready·restart0.
- 전체 `kubectl diff -k deploy/edgex` exit0.
- Metadata12개 모두 UP. 3초 간격 두 조회에서12개 모두 Event origin 증가,
  최종 신선도 Arduino 약0.21초·Sense HAT 약0.31–0.33초.
- 실제 Spring 경유 `/api/control-plane/infrastructure` AVAILABLE·센서12개,
  Arduino 각 항목은 단일 resource를 반환한다.
- Playwright로 실행 중 `/devices?view=sensors`의 실제 DOM12행과 Arduino6개 개별 행을 확인했다.
  응답 mock이나 앱 재시작 없이 검증했다.

Semantica 선행 결정은 로컬 그래프에서 확인했다. MCP 기록 도구가 없어 이번 보완은 그래프에
저장하지 못했으며, 확정 요구사항과 근거·검증 결과를 이 문서에 기록했다.
