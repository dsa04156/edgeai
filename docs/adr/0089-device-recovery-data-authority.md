# ADR 0089: 장치 복구 데이터와 원본 MQTT 권한의 결합 검증

상태: 채택. 2026-10-04.

ADR0088의 DB/journal 메타데이터 일치만으로 실제 consumer checkpoint 객체가 존재하거나
원본 MQTT 권한이 회수됐다고 판단할 수 없다. 독립 명령의 과거 성공 보고서를 조합하는
경우에도 이후 객체 삭제·관리자 자격 복구·계정 재활성화를 놓칠 수 있다.

새 읽기 전용 명령에서 ADR0088 대조, ADR0061의 실제 고정 객체 검증, ADR0070의 원본
브로커 private state와 TLS pin·marker·전체 disabled 계정·원래 관리자 자격 거절을 연결한다.
Run 설정과 generation의 broker digest를 대조해 다른 설치의 차단 증거를 거절한다.
소비자 최신 checkpoint의 canonical bytes도 파싱하고 DB summary/실행 digest와 비교한다.
파일 해시 일치와 metadata 의미 일치를 구분한다.

DB/journal·브로커 inventory·저장소 참조를 다시 관측하며 변경 시 실패한다. 변경 방지나
원자적 활성화 transaction을 제공하지 않으므로 성공을 실행 허가로 사용하지 않는다.
브로커 접속은 현재 권한을 읽는 명령만 사용하고 원본의 물리 프로세스 중지는 증명하지 않는다.
격리 marker와 세션/경로는 그대로 보존한다.

실제 공개 API/복원 PG·SQLite·age·TLS MinIO 두 설치와 TLS Mosquitto로 검증한다.
고정 객체 유실/새 latest 버전, 실제 상태 summary 불일치, 계정 재활성화/원래 관리자 복구,
관측 중 DB/권한 변경을 포함한다. 런타임 claim·broker 접수·DB checkpoint receipt는
제약조건을 유지한 SQL fixture이며 장비 수용이나 전체 서비스 재개 완료로 판정하지 않는다.
Java JAR·V1–V34·Runner 실행 코드는 바꾸지 않는다.
