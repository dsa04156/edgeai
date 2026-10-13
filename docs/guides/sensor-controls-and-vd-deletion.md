# 센서 최신값과 Virtual Device 정리

인프라 관리 → 센서에서 측정 항목마다 별도 행으로 이름·상태·대상 노드·최신값·수집 시각을 확인합니다. 각 행의 **같은 연결에 추가**는 측정 항목 추가 모달을 열고 **항목 수정·항목 삭제**는 해당 센서를 관리합니다. **항목 수정 → 항목 저장**은 선택한 항목 하나만 변경하며 다른 센서와 연결 설정을 유지합니다. 상태/최신값은 KubeEdge DeviceStatus의 DMI 보고를 사용하며, 매 수집 데이터는 별도 MQTT data plane으로 전달합니다.

**연결 테스트**는 대상 Edge Node에서 실제 property 하나를 읽습니다. 현재 드라이버는 읽기 전용이며 장치 명령 쓰기와 영속 측정 이력 조회는 제공하지 않습니다. 과거 센서 history/command API는 410 `SENSOR_API_REPLACED`를 반환합니다. 이전 검증 문서는 당시 구현의 역사적 증거입니다.

장치 장애 시 마지막 값과 시각은 보존하지만 OFFLINE으로 표시합니다. Mapper 준비 실패 및 오래된 DMI 관측도 OFFLINE으로 처리합니다. DeviceTwin의 관측과 Workflow 입력 형식은 [Mapper 실행 안내](../../deploy/kubeedge/README.md)를 참조하세요.

새 Virtual Device는 HTTP JSON property endpoint를 제공하는 런타임을 `protocol: virtual`, `kind: virtual`로 등록합니다. Physical Device와 같은 CRD/property 인터페이스를 사용합니다. 센서 목록의 삭제는 CRD 수집 설정만 제거하며 외부 런타임 컨테이너를 자동 삭제하지 않습니다.

연속 가상 센서는 **가상 센서 → 가상 장치 등록** 모달에서 별도로 구성합니다. 계산 결과의 그래프·보관 이력은 [연속 센서 가이드](vd-processing.md)를 따르며, 원본 센서의 장기 MQTT 이력과 구분합니다. 구형 UUID VD supervisor는 종료됐습니다. [종료 범위](virtual-devices.md)와 참조 보호를 확인하세요.
