# 센서 최신값과 Virtual Device 정리

인프라 관리 → 센서에서 장치별 이름·상태·측정 항목 수·대상 노드를 먼저 확인합니다. 장치를 누르면 펼쳐지는 측정 property별 행에서 연결 상태, 최신값, 마지막 수집 시각을 확인합니다. 펼친 영역의 **항목 추가·장치 삭제**와 각 행의 **항목 수정·항목 삭제**를 사용합니다. **항목 수정 → 항목 저장**은 선택한 측정 항목 하나만 변경하며 다른 항목과 장치 연결 설정을 유지합니다. 상태/최신값은 KubeEdge DeviceStatus의 DMI 보고를 사용하며, 고주기 데이터는 별도 MQTT data plane으로 전달합니다.

**연결 테스트**는 대상 Edge Node에서 실제 property 하나를 읽습니다. 현재 드라이버는 읽기 전용이며 장치 명령 쓰기와 영속 측정 이력 조회는 제공하지 않습니다. 과거 센서 history/command API는 410 `SENSOR_API_REPLACED`를 반환합니다. 이전 검증 문서는 당시 구현의 역사적 증거입니다.

장치 장애 시 마지막 값과 시각은 보존하지만 OFFLINE으로 표시합니다. Mapper 준비 실패 및 오래된 DMI 관측도 OFFLINE으로 처리합니다. DeviceTwin의 관측과 Workflow 입력 형식은 [Mapper 실행 안내](../../deploy/kubeedge/README.md)를 참조하세요.

새 Virtual Device는 HTTP JSON property endpoint를 제공하는 런타임을 `protocol: virtual`, `kind: virtual`로 등록합니다. Physical Device와 같은 CRD/property 인터페이스를 사용합니다. 센서 목록의 삭제는 CRD 수집 설정만 제거하며 외부 런타임 컨테이너를 자동 삭제하지 않습니다.

기존 앱 UUID VD의 미사용 등록 영구 삭제 규칙은 [가상 장치 관리](virtual-devices.md#4-미사용-등록-영구-삭제)에 따릅니다. 이 기능과 Mapper Virtual Device CRD는 서로 다른 등록이며 암묵적으로 합치지 않습니다.
