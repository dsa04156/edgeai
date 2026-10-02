# ADR0013: VD 식별자와 원본 연결

상태: M6 첫 구현. 영속 등록/수정/해제와 원본 연결을 먼저 검증하며 실제 VD runtime·Operation·Run VD
실행·화면·demo-vd까지 M6 완료 범위로 유지한다. [원문 요구사항](../m6-requirements.md)을 따른다.

VD는 불변 VD ProfileVersion과 SERVICE ProfileVersion을 참조하고 공개 key/UUID를 유지한다.
VDProfile `edgeai.vd/v1`은 sensorMirror/processing/emulation, 이름 있는 원본 조건0~16개,
serviceProfileVersionId, state, runtime의 동시 Task/시작/종료 제한을 정의한다. 현재 state는 STATELESS를
받는다. 이 관리 계약은 CHECKPOINT 복원이나 실제 물리 데이터 전송의 지원을 뜻하지 않는다.
sensorMirror/processing은 필수 원본을 하나 이상 선언하며 emulation은 원본 없이도 등록할 수 있다.

원본 조건별 Device Profile 버전과 허용 sourceMode를 고정한다. 하나의 slot에는 활성 Device 하나,
한 Device는 여러 VD/slot의 원본이 될 수 있다. 연결 변경 시 기존 행을 닫고 새 행을 추가하며 삭제하지 않는다.
Device 행 잠금으로 연결과 장치 해제를 직렬화한다. 활성 VD source로 사용 중인 Device 해제는409이며,
먼저 VD 연결을 바꾸거나 VD를 해제해야 한다. DB도 잘못된 Profile 종류·source 호환성·중복 활성 연결을 차단한다.

생성은 key와 정규화 요청 digest로 멱등 처리하고 다른 내용의 같은 key는409다. 해제 뒤 생성 재전송도
같은 VD를 반환하며 되살리지 않는다. 수정은 revision을 요구하고 이름·원본 집합·AUTO/NODE 배치 의도를
함께 교체한다. 바뀌지 않은 slot의 binding ID는 유지한다. Profile 참조와 생성 신원은 불변이다.

V13 단계의 state는 REGISTERED/RELEASED뿐이며 Ready나 실제 runtime을 만들었다고 표시하지 않는다.
활성 runtime이 생기는 후속 단계에는 수정/해제를 replacement/drain Operation으로 연결해야 한다.
지속 runtime과 개별 Task 실행의 소유·수명 및 producer fencing은 별도 계약·새 migration으로 연결한다.
기존 V1–V12와 Task 실행 모델을 가짜 VD Task/Job으로 재사용하지 않는다.
