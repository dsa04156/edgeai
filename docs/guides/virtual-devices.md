# 기존 가상 장치 기능 제거

2026-10-12에 기존 가상 디바이스 기능을 제거했습니다. 가상 장치 메뉴·등록·VD 템플릿·
실행과 내부 supervisor API를 제공하지 않으며 워크플로의 실행 정책은 AUTO/NODE/REMOTE입니다.

DEVICE/SERVICE 프로필, 앱 장치와 EdgeX 센서, 일반 워크플로·실행 결과 관리는 유지합니다.
eKuiper 연동 가상 디바이스는 이후 별도로 설계할 예정이며 이 변경에는 포함되지 않습니다.

기존 DB와 결과·감사 이력은 보존합니다. 이전 버전의 활성 가상 장치가 있는 환경은
이전 버전에서 실행을 종료한 후 업그레이드해야 합니다.
[제거 결정](../adr/0115-retire-legacy-virtual-devices.md)과
[검증 기록](../evidence/virtual-device-removal-2026-10-12.md)을 참고하세요.
