# M9 — 복원 DB 격리와 조회 점검 검증

2026-10-04 KST. [ADR0062](../adr/0062-restored-database-quarantine.md)의 기동 차단·조회 모드다.
운영 producer 회수와 서비스 활성화까지 완료한 판정은 아니다.

| 검증 | 근거 |
|---|---|
| 전체 단위113개 | `20261004T015814Z-a341a724` PASS, 새 조회 인증·유효한 인증/CSRF 쓰기 거절2개 포함 |
| 실제 PostgreSQL226개 | `20261004T020031Z-c21892d0` PASS, 새 실제 DataSource 격리3개 포함, failures/errors/skipped0 |
| 패키징 API·백업13개 | `20261004T015909Z-df643a3e` PASS,41테이블·Flyway34행·복원 조회·거절·원본 보존·소유 자원 정리 |
| OpenAPI·계약/MVC | `20261004T020353Z-baad79de` PASS, 정상 모드의 기존 계약 유지 |
| 실제 저장소·TLS broker47개 | `20261004T020809Z-89d0fd0c` PASS, 별도 DB/MinIO·실제 SDK, 소유 자원 정리 |
| 새 JAR의 DB/S3 전체 참조9개 | `20261004T021137Z-d86797ad` PASS, 원본 DB/MinIO 없이4고정버전/1,287bytes·거절·정리 |

새 DB만 사용하는 PostgreSQL 시험은 marker가 있는 DB의 일반 연결 초기화가 거절되는지
확인한다. Flyway가 꺼져 있어도 검사하며 거절한 pool은 닫힌다. 점검 모드의 서로 다른 두
실제 연결에서 autocommit과 명시적 transaction의 INSERT가 모두 SQLSTATE25006으로
거절된다. 모든 실행/inventory feature의 활성화 거절과 marker 없는 DB 거절도 확인했다.

패키징 API는 복원 DB의 일반 기동과 `SPRING_FLYWAY_ENABLED=false` 기동이 실제 종료되고,
DB 전체41테이블 내용이 변하지 않는지 확인했다. 원본 DB를 점검 모드로 착각한 기동도 거절한다.
올바른 복원 DB 점검에서는 기존 Profile/Workflow/Run 응답을 읽고, 유효한 Basic/CSRF를 넣은
Profile 등록이403으로 거절되는지 확인했다. 기존 불변 제약·archive 손상/권한/덮어쓰기 거절과
실제 pg_restore 실패 정리도 유지한다.

위 API 시험의 JAR SHA-256은
`1b7b33f6a797c11a49e07b4127bcee33293ed5ec3d2eb2fe38e0dd2792e50b96`이다.
백업 archive527,881bytes와 요약 JSON을 해당 evidence 폴더에 보존했다. 원문 archive·SQL/API
로그·인증 정보는 비공개 디렉터리에만 남겼다. V1–V33 migration 파일은 수정하지 않았다.

첫 단위 실행 `015751Z-04d3f58d`는 새 테스트의 변경 가능한 필드를 try-with-resources에 넣어
컴파일에 실패했다. final 지역 변수로 자원 수명을 표현한 뒤 위 전체113개가 통과했다.
첫 저장소 실행 `020441Z-a2f79042`는 오래된 로컬 helper에 broker 도구 경로가 빠져,
실제 fixture 로그의 `mosquitto_ctrl` FileNotFoundError로 실패했다. 해당 실행은 로컬 개발 DB를
사용했으며 불변 시험 이력을 임의 삭제하지 않았다. 후속 실행은 별도 소유 DB/MinIO와 명시적
Mosquitto/SDK 경로를 사용했고 위47개가 통과했다. 같은 JAR의 참조 대조9개도 통과했다.
신규 코드의 원격 CI·배포는 후속이며, 이 결과를 기존6617d93 CI의 판정으로 대신하지 않는다.

공유 원본 DB나 운영 namespace의 권한을 회수한 시험은 아니다. Secret/CA·broker/device
journal·원래 외부 producer의 종료/권한 회수·운영 활성화는 [M9 수용 범위](../m9-requirements.md)에 남는다.
