# ADR0087: 장치 송신 journal을 일관되게 백업하고 격리 복원한다

- 상태: 채택 — 실제 SQLite/age13개·Runner111개·TLS MQTT95개 검증, 새 CI/배포는 후속
- 날짜: 2026-10-04

## 결정

ADR0036의 동일 Device Session·LOCAL·출력 전용 journal에 백업을 추가한다. 실행 중인
SQLite 파일을 복사하거나 owner 잠금을 빼앗지 않는다. 읽기 전용 연결의 한 트랜잭션에서
manifest, serial/revision, adapter state, 경로별 생성/처리 확인 순번, 미확인 DATA/END를
읽는다. 물리 schema나 SQL을 백업 형식에 넣지 않고 기존 checkpoint의 논리 상태 검증을
재사용한다. Task/input/EXTERNAL·여러 Device Session·미확정 checkpoint 후보는 거절한다.

장치 완료 intent가 있으면 같은 manifest·종료 cursor와 대조한다. SQLite 읽기 전후에
실제 intent 파일이 바뀌면 재시도를 요구한다. 빈 journal로 대체하거나 ACK/순번을 줄이지
않으며, snapshot은 캡처 시점 이후의 데이터 보존을 주장하지 않는다.

기존 제한을 유지한 최대72MiB 논리 snapshot을8MiB 이하 조각으로 나눠 ADR0065의
고정 age 도구로 암호화한다. 공개 수신자만으로 백업하며 개인 키는 복원 때만 필요하다.
장치/세션/경로 식별자·센서 값·상태·완료 intent는 암호문 내부에만 넣는다. 이름·hash·조각
크기와 archive 검증은 기존 정적 파일 형식을 재사용한다. 작성자 서명을 추가한 것은 아니다.

복원은 인증 복호화와 전체 논리 검증 뒤 새 SQLite schema에 데이터만 삽입한다. 세션·세대·
serial·처리 확인·미확인 프레임·완료 intent를 그대로 보존한다. 격리 marker를 쓰고 fsync한
뒤 새 `source/` 디렉터리로 게시한다. Journal은 marker가 있으면 SQLite나 owner lock을
열기 전에 거절한다. 게시 직전/직후 SIGKILL에도 자동 실행 가능한 복원본을 남기지 않는다.

## 수용 경계

복원 상태는 `QUARANTINED_DEVICE_JOURNAL`, `activated=false`다. 원본 producer 종료,
DB의 현재 Device Session·route generation·처리 watermark 대조, broker 권한과 키 복원,
source 재개·새 세션/외부 센서 재생 계약은 별도다. marker 삭제를 재개 명령으로 제공하지 않는다.
이 구성 요소는 M9 전체 복구나 RPO/RTO 수용을 뜻하지 않는다.

동일 사용자/관리자가 입력 경로를 병행 치환하는 환경, 운영 하드웨어·외부 adapter 수용은
별도다. 강제 종료로 남은 개인 임시 파일의 매체 보안 삭제는 보장하지 않는다.
[실행법](../operations/backup/device-journal-backup.md), [검증](../evidence/m9-device-journal-backup.md).
