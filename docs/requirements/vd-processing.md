# 사용자 정의 VD 처리 요구사항

사용자가 물리 센서와 측정 항목을 선택해 VD를 만들고, 필터·이동평균·RMS·FFT·임계값을 순서대로 조합하며 파라미터를 설정한다.
공통 Processing Engine을 재사용하고 VD Profile 설정을 실제 Runtime에서 실행한다.
처리 결과를 다른 VD 또는 AI Workflow 입력으로 연결한다.
Dashboard에서 생성·수정·실행·상태·결과를 조회한다.

기존 KubeEdge Mapper, DeviceModel/Device CRD, VD Profile v2, Spring Boot, Runner와 Dashboard를 재사용한다.
센서마다 별도 프로그램이나 Pod를 만들지 않고 Mapper의 수집·scale/offset을 그대로 사용한다.
기존 구현과 호환성을 유지하며 기능별 테스트·수정과 회귀 검증을 수행한다.
마지막에는 실제 센서 → Mapper → VD 처리 → Workflow → Result를 검증한다.
실제 Kubernetes 검증이 불가능하면 미검증으로 기록한다. 운영 배포·실데이터 삭제는 별도 승인 전 수행하지 않는다.
