# M9 — PostgreSQL 백업·복원 검증

2026-10-04 KST. [ADR0059](../adr/0059-postgres-backup-restore.md)의 DB 구성 요소를 검증했다.
합성 업무 데이터와 실제 PostgreSQL16, 현재 패키징 API JAR을 사용했다. 전체 플랫폼 복구·
운영 DB 복원·M9 완료 판정은 아니다.

`20261004T010138Z-7ebea0c3`의 `scripts/test-postgres-backup.sh`가 exit0/PASS다.
공개 API로 Profile·Device·세션·관측·두 작업의 Workflow·취소한 Run을 생성했다.
기존 개발 DB와 별개인 소유 DB를 사용하고, 복원 API의 실행/VD/Remote/stream worker는 껐다.

| 시험 | 실제 확인 |
|---|---|
| 스냅샷 복원 | edgeai의41개 테이블별 전체 행 SHA와 Flyway 이력34행 일치. 빈 테이블은 존재·빈 상태를 확인 |
| 백업 시점 경계 | 백업 완료 후 추가한 네 번째 관측은 원본에만 남고 복원 DB에는 최초3개만 존재 |
| 패키징 API | 복원 DB의 Profile·Workflow·Run 조회 응답이 원본과 동일. 큰 정수·한글 포함 |
| 불변 제약 | 복원 후 Profile UPDATE/DELETE를 실제 PostgreSQL이 거절, 내용 불변 |
| 기존 대상 거절 | 기존 복원 DB·비격리 원본 DB·기존 백업 폴더를 덮어쓰지 않음 |
| 파일 검사 | 손상 archive·파일 symlink·소유자 외 읽기 권한은 DB 생성 전에 거절 |
| 복원 중 실제 오류 | 새 DB에서 실패하는 CHECK 제약을 넣은 별도 실제 archive의 COPY 실패 후 새 DB만 제거 |
| 백업 경고 | 실제 dump 성공 후 stderr 경고를 주입한 fixture는 manifest를 발행하지 않고 실패 |
| 자원 정리 | 성공/실패 검증 후 소유 API 종료·소유 source/restore DB 제거, 다른 DB에는 쓰지 않음 |

총10개 사례다. 백업527,876bytes, SHA-256과 owner-only 파일/폴더 권한을 확인했다.
최종 JAR SHA-256은 `33ba4881ea9982ac2ab210f15663dc708dfc81d8959065bad6a4ffe14650c023`다.
요약 report는 해당 evidence 폴더의 `postgres-backup-report.json`에 보존한다.
archive·manifest·API/SQL 원문 로그는 비공개 `.tools`에만 남긴다.

ADR0061의 새 DB comment/보고서 `restoreIdentity` 추가 후 동일10개 시험을 다시 실행한
`20261004T014814Z-0a42874e`도 PASS다.41테이블·Flyway34행, archive527,874bytes, 위와 같은
JAR이며 소유 DB/API 정리까지 확인했다. [DB/S3 대조](m9-recovery-references.md)는 식별자
불일치 거절과 실제 복원 참조의 별도 저장소 읽기를 검증한다.

첫 `20261004T005336Z-49fa11e4`는8개 PASS였으며 복원 client 종료를 주입했다.
위 최종 실행은 실제 pg_restore 제약 오류로 강화하고 파일 권한과 dump 경고 거절을 추가했다.

CI scaffold에 `--transport compose`로 PostgreSQL17 컨테이너 내부 클라이언트를 사용하는
동일 게이트를 추가했다. 로컬 Docker socket은 접근 권한이 없어 Compose 실행은 하지 않았다.
새 커밋의 CI 통과 여부는 후속 확인한다. Native16 결과로 Compose17 통과를 주장하지 않는다.
글로벌 역할/ACL·외부 Secret·S3 고정 version/bytes·broker/device journal·실행 중 외부 상태·
주기/암호화/원격 보관/RPO/RTO는 [전체 M9 요구](../m9-requirements.md)의 남은 항목이다.
