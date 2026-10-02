# M6 실제 Kubernetes VD 수명 검증

2026-10-03 KST. 실행 `20261002T182533Z-cfdb17d2`는 PASS/exit 0이다.
기존 클러스터의 전용 EdgeAI namespace에서 임시 API/DB/Service/Secret을 만들고,
현재 로컬 API JAR을 고정 API 이미지의 JRE로 실행했다. 전송 전후 JAR SHA-256을 대조했다.
기존 배포·DB·스토리지는 변경하지 않았다. 새 API 컨테이너 이미지 시험은 별도 CI 대상이다.

재현: `bash scripts/test-vd-kubernetes.sh <explicit-context>`.
`scripts/vd_acceptance.py`를 실제 VD가 활성화된 API에서 실행하는 `demo-vd.sh`와
격리 kind 검사에도 연결했다. 새 kind 연결의 CI 성공은 아직 확인하지 않았다.

## 실제 관측

- AUTO: 공개 provision 요청과 같은 키 재전송, 실제 supervisor Pod의 인증된 poll·Ready,
  API Pod 삭제/재생성 후 동일 runtime·Pod UID 유지와 lease 갱신을 확인했다.
- API 재시작은 13.065초였으며 임시 DB와 서명 Secret은 유지했다. 새 API Pod UID가 다르고
  실행 JAR SHA-256은 `91499e11da17cda118f44e25e74824da3bdc552ab984f68a1ba278b5e7a6b201`이다.
- AUTO/NODE 각각 이름 변경은 runtime을 교체하지 않고, 명시적 교체는 같은 vdId의
  generation 1→2와 새 Pod UID를 만들었다. 이전 물리 자원 종료 후 새 세대가 시작됐다.
- 실제 Node UID/이름·지정 배치·고정 Runner imageID·Secret 소유 관계를 대조했다.
  이전 Pod의 자격으로 poll을 재시도하면 차단됐으며, 이전 provision 키 재전송은 새 세대를 만들지 않았다.
- drain은 실제 Pod/Secret 제거 후 성공했고, 각 두 세대와 닫힌 binding 이력을 보존했다.
- CPU 요구량이 수용량을 초과하는 Pod의 실제 `Unschedulable` 관측 후 `STARTUP_TIMEOUT`과
  Operation 실패·자원 제거를 확인했다.
- 시험 VD 3개를 해제했다. 임시 API/DB/Service/Secret은 UID·소유 label을 확인해 삭제했고,
  종료 뒤 두 namespace에서 시험 자원이 0개임을 별도 조회했다.

구조화 관측은 `.tools/vd-kubernetes.json`, 실행 결과는 위 evidence 실행 디렉터리에 보존한다.
보고서의 `taskExecution`은 false다. 이 시험에는 VD Task 배정/결과·실장비 수용이 포함되지 않는다.

kind 호출의 전용 `KUBECONFIG`를 두 Kubernetes subprocess 경계에 명시적으로 전달하도록
보강하고 전달 검사를 통과했다. 최신 release pin으로 전체 실제 시험을 다시 실행한
`20261002T183254Z-d9bbf00c`도 3개 시나리오·자원 정리 PASS/0이다.

## 실패와 수정 근거

- `20261002T181032Z-ffa9f21d`: 실제 Pod에서 개발 호스트 API 연결이 시간 만료됐다.
  방화벽 원인은 확정하지 않았으며 시험 자원은 정리했다. 후속 시험의 API/DB를 클러스터 안에 격리했다.
- `20261002T181744Z-798cf202`: 임시 API 기동 실패로 readiness가 시간 만료됐다.
- `20261002T182440Z-a44aca2a`: 종료 즉시 감지와 비공개 로그 보존을 추가해
  `Cannot configure Kubernetes CA`를 확인했다. ServiceAccount의 자동 마운트는 false이고
  기존 API Deployment는 Pod에서 true를 명시한다. 시험 Pod에도 같은 설정을 적용한 뒤
  최종 실행에서 API 기동과 모든 VD 관측이 통과했다. 공유 ServiceAccount 설정은 변경하지 않았다.

## 남은 범위

VD 실행은 기존 배포에서 기본 비활성이다. 새 kind 게이트의 CI·이미지 검증,
VD Run의 Task 배정/claim/Result·취소/실패 연결은 남는다. M5의 상태형 복원·실제 외부 계약 수용,
M7–M10도 미완료다. 이 결과로 M6 전체 완료를 판정하지 않는다.
