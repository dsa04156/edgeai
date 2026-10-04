# ADR0069 — 복구 전 원본 DB 연결 차단과 기존 연결 종료

상태: 채택, 2026-10-04. ADR0062의 복원 DB 격리와 ADR0064의 Kubernetes 실행 중지에
원본 PostgreSQL 연결 차단을 추가한다. 복원본만 격리하면 원래 API가 계속 DB를 변경할 수 있다.

## 결정

원본 DB 이름·OID·복구 UUID와 새 출력 경로를 명시한다. DB 소유 역할로 별도 `postgres` DB에
접속하여 대상의 신원을 확인한다. `edgeai` 또는 `edgeai_*` 원본만 허용하며 template·복원 DB·
다른 소유자·기존 DB 주석·다른 복구 UUID는 거절한다. 최초 실행은 성공한 EdgeAI migration
이력이 있는 DB만 대상으로 한다. 이름만 같은 DB로 교체되면 해당 DB를 차단해서는 안 된다.

한 transaction에서 `ALTER DATABASE ... ALLOW_CONNECTIONS false`를 적용하고 획득한 DB
잠금 안에서 OID·소유자·주석을 다시 확인한 뒤 `edgeai-recovery-fence:<UUID>` 주석을 남긴다.
재검사 실패는 ALTER까지 rollback한다. 연결 허용 여부와 복구 marker는 함께 commit된다.
PostgreSQL의 [ALLOW_CONNECTIONS](https://www.postgresql.org/docs/16/sql-alterdatabase.html)는
새 연결을 막으므로 기존 backend 종료는 별도로 확인한다.

대상 DB OID에 속한 backend의 PID·시작 시각을 조회하고 동일한 신원일 때만
`pg_terminate_backend(pid, timeout)`을 호출한다. 다른 DB의 연결은 종료하지 않는다.
[종료 확인 timeout](https://www.postgresql.org/docs/16/functions-admin.html)을 사용하고 실제
backend 목록이 비었는지 확인한다. 같은 서버·자격 증명으로 새 연결이 해당 admission 오류로
거절되는지도 검증한다. prepared transaction이 있으면 자동 commit/rollback하지 않고 BLOCKED다.

성공은 `SOURCE_DATABASE_CONNECTIONS_FENCED`다. DB 연결 차단의 확인 시점 증거이며
`globalQuiescenceProven=false`, `activated=false`를 유지한다. 결과·진단 파일은 소유자만 읽는다.
SQL/API 진단 본문과 자격 증명을 공개 evidence나 CI artifact에 포함하지 않는다.

## 재개와 제약

시간 초과·종료 권한 부족·확인 불가 시에도 이미 적용한 연결 차단을 해제하지 않는다.
같은 복구 UUID와 새 출력 경로로 재개할 수 있다. 다른 UUID로의 인계와 외부에서 연결 허용을
다시 켠 상태는 거절한다. 성공 직후에도 fence 신원을 다시 조회하며 확인할 수 없으면 성공을
반환하지 않는다. 차단 해제나 복원 DB 활성화 기능은 제공하지 않는다.

관리자의 병행 ALTER·클러스터 재구성까지 직렬화하지 않는다. 현재 연결 거절 판정은 PostgreSQL의
영문 admission 오류를 확인한다. 다른 locale의 오류를 읽으면 연결 실패만으로 성공을 추정하지
않고 BLOCKED로 남긴다. 기존 API 프로세스가 받은 외부 권한과 이미 시작한 외부 요청은 별도로
회수해야 한다. Kubernetes·Remote·MQTT·장치·S3 writer 중지와 키/journal 복원을 합친 종합
복구·활성화, RPO/RTO 수용은 여전히 남는다.

[실행법](../recovery-database-fence.md), [실제 PG/API 검증](../evidence/m9-recovery-database-fence.md).
