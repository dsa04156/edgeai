# ADR0036 — 같은 Device 세션의 송신 journal과 경로 인계

상태: 구성 요소 구현·로컬 검증 완료. ADR0035의 Task 체크포인트 인계 다음 데이터 경계다.
[시험 근거와 한계](../evidence/m7-device-source-handover.md)를 따른다.

## 소유와 보존 범위

Device source는 인증된 현재 배정을 조회한 뒤 같은 Device Session의 로컬 송신 journal을
소유한다. 샘플·adapter 상태·경로별 순번을 한 SQLite 트랜잭션에 기록하고 MQTT로 보낸다.
MQTT PUBACK으로 삭제하지 않으며 소비자의 처리 확인을 받은 순번만 제거한다.
소비 Task가 EXTERNAL 모드일 때 그 처리는 서버가 검증한 checkpoint 범위여야 한다.

소비 Attempt 변경으로 경로 generation이 바뀌면 기존 source를 닫고 현재 generation을
인증 조회하여 `handover=True`로 같은 journal을 연다. 현재 ACTIVE 경로는 서버에서 옛 경로의
broker 권한 회수 뒤에만 생성할 수 있다. SDK는 동일 논리 경로·동일 Device Session·증가한
generation만 허용하며 frame의 generation만 원자적으로 변경한다. state/revision/순번,
미확인 DATA·END와 확인 위치는 그대로 보존한다. snapshot serial은 한 번 증가한다.

이 변경은 LOCAL·출력 전용 Device journal에만 적용한다. Task Attempt나 입력 journal,
EXTERNAL 확정 이력·후보, 장치 세션 교체·논리 경로/제한 변경·세대 역행을 허용하지 않는다.
Task journal은 ADR0035의 서버 검증 checkpoint를 통해 인계한다. Device 볼륨 손실이나
새 Device Session의 원본 재생/순번 연속성은 별도 계약이며 빈 journal로 자동 대체하지 않는다.

## 인증 수명

DeviceSource는 하나의 Device Session·Run·broker 연결과 전체 지정 경로를 관리한다.
배정 조회는 lease를 연장하지 않는다. heartbeat0으로 기존 순번을 확인한 뒤 증가 순번으로
갱신하며 응답 유실은 같은 순번으로 재시도한다. 양쪽 생존 조건과 세션 전체 timeout을 지킨다.
배정 만료·취소·인증 오류면 MQTT를 닫고 새 journal 쓰기도 차단한다. 저장소나 브라우저에
broker 비밀번호·Device 토큰을 기록하지 않는다.

같은 journal의 동시 소유는 기존 파일 잠금으로 차단한다. 인계는 그 잠금 아래에서 수행하고
인계 전/후 현재 권한을 검사해 만료·실패·프로세스 강제 종료 시 SQLite가 전체 변경을 되돌린다.
재시작 후 같은 배정으로 여는 것은 멱등이며 옛 세대로는 다시 열 수 없다.

## 수용과 남은 연결

실제 SQLite의 원자성/용량/강제 종료와 인증 HTTP·TLS MQTT의 재전송/중복 제거를 시험한다.
장치 세션·운영 broker 권한 회수는 실제 서버 시험과 명시적 fixture 시험을 구분한다.
DeviceSource는 센서 SDK의 전송 경계이며 실제 하드웨어 adapter를 대신하지 않는다.
Task/인접 Task의 인계 orchestration·SERVICE/Runner·공개 STREAM·Kubernetes 다중 장치
데모와 전체 M7 완료는 별도로 검증해야 한다.
