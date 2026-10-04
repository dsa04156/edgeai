# 복구 중 원본 PostgreSQL 연결 차단

복원본을 활성화하기 전에 기존 API가 원본 DB에 다시 쓰는 것을 막는 명령이다. 선택한 원본
DB의 **새 연결과 기존 연결을 모두 차단**하므로 해당 DB를 사용하는 API는 사용할 수 없게 된다.
백업을 만드는 명령이 아니며 복원할 archive는 [백업 절차](postgres-backup.md)로 준비한다.

대상 서버의 `postgres` DB에서 원본 DB의 `oid`, `datdba`, `datallowconn` 및
`shobj_description(oid, 'pg_database')`를 조회해 이름·소유자·OID를 확인한다. 프로젝트의
DB 환경을 사용하며, 소유자와 기존 backend를 종료할 권한이 필요하다. 복구 전체에서 유지할
UUID를 정하고 아래의 인자를 명시한다.

```bash
bash scripts/fence-recovery-database.sh \
  --transport native \
  --database <확인한-원본-DB> \
  --database-oid <확인한-OID> \
  --recovery-id <이번-복구-UUID> \
  --timeout 60 \
  --output .tools/recovery-db-fence-<새-실행명>
```

로컬 클라이언트 경로가 다르면 `--pg-bin <PostgreSQL-bin-경로>`를 지정한다.
프로젝트 Compose DB는 `--transport compose`를 사용한다. 환경 변수의 암호는
PostgreSQL 자식 프로세스 환경으로 전달되며 명령 인자나 공개 보고서에 넣지 않는다.

`edgeai`/`edgeai_*` 원본 이름, OID, 소유자와 성공한 migration 이력을 확인한다.
`edgeai_restore_*`, 기존 주석이 있는 DB, 다른 복구 작업의 marker는 거절한다.
차단 뒤 기존 API pool과 쓰기 연결을 종료한다. 아직 commit되지 않은 transaction은 해당
연결 종료로 rollback된다. prepared transaction은 자동으로 정리하지 않는다.

새 출력 디렉터리의 `fence-report.json`을 확인한다.

| 결과 | 의미 |
|---|---|
| exit0 / `SOURCE_DATABASE_CONNECTIONS_FENCED` | 해당 OID의 backend가 없고 새 연결 거절과 fence 보존을 확인 |
| exit2 / `BLOCKED` | timeout·prepared transaction·연결 거절 원인 또는 최종 상태 확인 불가 |
| 기타 nonzero / `FAIL` | 입력·소유권·DB 신원 불일치 또는 명령 실패 |

실패했다고 연결이 다시 열렸다고 가정하지 않는다. `fenceRetained`와
`fenceStateUnconfirmed`를 확인하고, 후자가 true이면 실제 DB 상태를 다시 조회한다.
같은 복구 UUID와 **새 출력 경로**로 재개한다. 성공·실패 모두 자동 해제하지 않는다.
현재 영문 PostgreSQL admission 오류만 확정적으로 분류하며 다른 언어의 오류는 BLOCKED다.

이 단계의 `globalQuiescenceProven=false`, `activated=false`는 의도된 값이다.
[Kubernetes producer 중지](recovery-producer-stop.md), Remote/MQTT/장치·저장소 권한 회수,
복원 DB/S3/키/journal 대조 및 실행 상태 조정을 마쳐야 서비스 재개를 검토할 수 있다.
원래 API 프로세스 자체를 종료하거나 복원 DB를 활성화하는 기능은 이 명령에 포함하지 않는다.

```bash
bash scripts/test-recovery-database-fence.sh
# 프로젝트 Compose PostgreSQL에서 같은 실제 검증
bash scripts/test-recovery-database-fence.sh --transport compose
```

시험은 별도로 만든 DB·API·쓰기 연결을 사용하고 다른 DB의 연결/데이터 보존도 확인한다.
검증 후 시험 소유 프로세스와 DB만 정리한다.
[설계](adr/0069-recovery-source-database-fence.md), [검증 근거](evidence/m9-recovery-database-fence.md).
