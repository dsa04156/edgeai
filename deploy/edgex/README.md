# EdgeAI 센서 수집 배포

현재 레포가 관리하는 EdgeX 4.0.2 기반 테스트베드 구성이다. 서버의 Core Keeper / Metadata /
Data / Command / MQTT / PostgreSQL 6개와 엣지 Device Service 2개, 초기화 Job 1개를 배포한다.
기존 `edge-ai-workspace`의 Adapter Controller, Discovery DaemonSet, Ingest Gateway는 포함하지 않는다.

## 책임과 연결

```text
Arduino --Serial--> device-serial-jetson --MQTT--> Core Data --> PostgreSQL
Sense HAT --I²C--> device-sensehat-raspi --MQTT-->
Spring --HTTP GET/POST--> Core Metadata (장치 / 프로필 조회·지원 센서 등록)
Spring --> EdgeAI Device / VD / Workflow / Kubernetes Runtime
```

EdgeX 센서 등록과 EdgeAI의 Device/VD는 서로 다른 객체다. 이 구성은 두 등록부를 자동 복제하거나
삭제하지 않는다. Spring은 inventory·측정값·명령 조회와 지원 센서 수동 등록을 제공한다.
등록 화면과 지원 범위는 [센서 등록 가이드](../../docs/guides/sensor-registration.md)를 따른다.
측정값의 AI 입력 연결은 별도다. `UP`은 Metadata의 상태이며 최근 실측값 수신 여부는
Core Data의 Event origin과 별도로 확인한다.

| 구성 | 배치 | 연결 |
|---|---|---|
| 코어·PostgreSQL·MQTT | etri-ser0002-cgnmsb | ClusterIP만 사용 |
| Arduino 수집 | etri-dev0001-jetorn | host `/run/edgeai/devices` → `/dev/edgeai` |
| Sense HAT 수집 | etri-dev0003-raspi5 | `/dev/i2c-1` |

Arduino는 온도·조도·자기·가속도 X/Y/Z를 각각 별도 등록으로 제공하여 센서 표의 6개 행으로 표시한다.
모두 `physicalDeviceId: arduino-001`과 같은 Serial 연결을 공유한다. 중복 aggregate 등록은 두지 않는다.
Sense HAT는 기존 드라이버의 resource group 계약에 따라 6개 등록을 사용한다.
총 12개 등록은 물리 보드 12개를 뜻하지 않는다.
테스트용 `virtual-*` 등록, 자동 탐색·승인, 구동기 write command는 포함하지 않는다.

## 렌더링·검증·적용

```bash
bash scripts/ops/edgex.sh render
bash scripts/test/test-edgex.sh
bash scripts/ops/edgex.sh check kubernetes-admin@kubernetes
bash scripts/ops/edgex.sh apply kubernetes-admin@kubernetes
bash scripts/ops/edgex.sh status kubernetes-admin@kubernetes
```

검증 스크립트에는 Python 3 + PyYAML, kubectl이 필요하다. 적용 명령은 명시한 context와 이 폴더의
리소스만 사용한다. DB/PVC 초기화나 이전 배포 삭제를 자동 실행하지 않는다.

새 클러스터에는 먼저 `namespaces.yaml`을 적용하고 `edgex-system`에
`edgex-postgres-credentials` Secret을 별도로 준비한다. 키는 `username`, `password`다.
자격 증명은 Git이나 명령 인자로 저장하지 않는다. 기존 DB 비밀번호를 임의로 교체하지 않는다.
PostgreSQL PVC는 20Gi `local-path`이며 StatefulSet 삭제 후에도 보존된다.

Keeper 기동 후 `edgex-core-common-config-bootstrapper` Job이 공통 설정을 등록한다.
Job 사양은 Kubernetes에서 불변이므로 공통 설정을 바꿀 때 해당 Job만 재생성한다.
DB를 새로 만들 때도 기존 완료 Job을 그대로 남겨 두지 말고 새 DB용 bootstrap을 실행한다.

## Spring 설정

클러스터의 Spring API에 다음 환경 변수를 전달한다.

```dotenv
EDGEAI_EDGEX_ENABLED=true
EDGEAI_EDGEX_METADATA_URL=http://edgex-core-metadata.edgex-system.svc:59881
```

로컬 IDE 실행에는 접근 가능한 Service IP 또는 별도 포워딩 주소를 사용한다.
NetworkPolicy는 `edgeai` namespace의 `app=edgeai-api` Pod에서 Metadata 59881 접근을 허용한다.
브라우저는 EdgeX에 직접 접속하지 않고 기존 `/api/control-plane/infrastructure`를 사용한다.
이 기능은 같은 주 작업 폴더 `/home/jinuk/codex-work/edgeai`의 기존 infrastructure 구현을 사용한다.
배포 파일과 Spring/UI 구현은 아직 미커밋 변경이므로 함께 검토해야 한다.

## 이미지·제약

모든 이미지는 digest로 고정한다. Core Keeper/Metadata/Command와 bootstrapper는 공식 4.0.2,
Core Data는 기존 DB 조회 교착 수정 이미지, 장치 서비스는 기존 실장치 드라이버 이미지다.
드라이버를 재작성하거나 펌웨어를 변경하지 않는다. 사설 이미지가 있는 `192.168.0.56:5000`
레지스트리가 필요하다. 이 폴더는 배포·센서 설정을 소유하며 커스텀 이미지 빌드 소스의 이관까지
완료한 상태는 아니다.

MQTT는 현재 테스트베드의 익명 접속/비영속 설정을 유지한다. namespace NetworkPolicy로
접근 범위를 제한한다. 물리 Device Service는 고정 hostPath와 privileged 실행을 사용한다.
공개망 운영, 사용자별 권한, 고가용성, 단절 중 영속 재전송은 이 배포의 검증 범위가 아니다.

기존 ArgoCD `edgex-telemetry`는 이전 저장소를 다시 적용하므로 재구성 시 관리 연결을 해제한다.
현재는 이 레포의 매니페스트를 직접 적용한다. 작업 기준 경로는 `/home/jinuk/codex-work/edgeai`다.
Git push와 새 ArgoCD 자동 동기화는 별도 작업이며,
원격 저장소에 없는 경로를 가리키는 Application을 먼저 활성화하지 않는다.
