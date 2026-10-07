# PostgreSQL 백업·복원 실행

이 문서는 대상별 백업·격리 복원 절차입니다. 다른 보호 대상과 복구 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

이 명령은 DB를 백업하고 **새 DB에 복원**한다. MinIO의 파일/버전, Kubernetes Secret,
외부 제공자 인증, broker·장치 journal과 실행 중 작업까지 복구하는 절차는
[M9의 남은 범위](../../requirements/m9-requirements.md)다. 기존 API의 DB 연결이나 worker를 변경하지 않는다.

## 준비와 백업

저장소 루트에서 실행한다. `scripts/lib.sh`가 `.env`의 `EDGEAI_DB_NAME`, `EDGEAI_DB_USER`,
`EDGEAI_DB_PASSWORD`, `EDGEAI_DB_PORT`, 선택적인 `EDGEAI_DB_HOST`를 읽는다.
원본 DB의 전체 객체/행을 읽을 수 있는 계정을 사용한다. 복원 계정에는 새 DB 생성 권한도
필요하다. 비밀번호를 명령 인수로 넘기지 않는다.

프로젝트의 로컬 PostgreSQL16에는 다음 명령을 사용한다. 폴더 이름은 매번 새로 지정한다.

```bash
bash scripts/ops/backup-postgres.sh --output .tools/backups/2026-10-04-first
bash scripts/ops/restore-postgres.sh --input .tools/backups/2026-10-04-first \
  --target-database edgeai_restore_20261004_first
```

Compose PostgreSQL에는 컨테이너 내부의 동일 버전 클라이언트를 사용한다.
항상 `edgeai-dev` 프로젝트의 `postgres` 서비스로 연결하며 `.env`와 Docker 접근이 필요하다.

```bash
bash scripts/ops/backup-postgres.sh --transport compose --output .tools/backups/compose-first
bash scripts/ops/restore-postgres.sh --transport compose --input .tools/backups/compose-first \
  --target-database edgeai_restore_compose_first
```

native는 `.tools/postgres/usr/lib/postgresql/16/bin`이 있으면 사용하고, 없으면 PATH에서 찾는다.
다른 서버에는 `--pg-bin /absolute/path/to/matching/bin`으로 psql/pg_dump/pg_restore를 지정한다.
서버와 dump/restore 클라이언트의 major는 같아야 한다. 현재 libc locale·PostgreSQL16 이상만
지원한다. `--database <name>`으로 `.env` 기본값 대신 백업할 DB를 명시할 수 있다.

## 성공 결과와 검토

백업 폴더에는 `database.dump`와 `manifest.json`이 생긴다. 폴더0700·파일0600이며 DB 내용과
DB에 저장된 민감한 값도 포함한다. Git·CI artifact·공유 문서에 올리지 않는다. 현재 파일
암호화·원격 저장·보관 기간/자동 삭제는 구현하지 않았다. `.tools`의 로컬 복사본만으로
호스트 유실을 대비했다고 판정하지 않는다.

manifest에는 format/scope·원본 DB·서버/클라이언트 버전·locale·백업 시작/종료 시각·archive의
크기/SHA-256·제외 범위가 있다. 완료 시각이 DB 스냅샷의 정확한 시각을 뜻하지는 않는다.
dump에 stderr 진단이 있거나 archive 검사가 실패하면 manifest를 발행하지 않는다.
부분 폴더를 재사용하지 말고 출력된 비공개 진단 경로에서 원인을 확인한 후 새 폴더로 실행한다.

복원은 크기/SHA·archive 형식·파일 권한을 확인한 뒤 새 `edgeai_restore_*` DB를 만든다.
동일한 encoding/collation/ctype로 전체 archive를 단일 트랜잭션에 복원하고 Flyway 성공 이력을
확인한다. 기존 DB는 덮어쓰지 않는다. 파일 자체의 symlink와 소유자 외 권한을 거절하지만
SHA-256이 archive 출처를 인증하지는 않으므로 자신이 신뢰하는 백업만 복원한다.

성공하면 `RESTORED_DB_ONLY`, 비공개 `restore-report.json`에는 `activated: false`가 기록된다.
새 DB의 comment와 보고서에는 임의 `restoreIdentity`를 남긴다. 후속 [DB/S3 참조 대조](../recovery/recovery-references.md)는
이 식별자와 DB 이름/OID를 확인해 다른 복원 DB의 보고서를 잘못 사용하지 않게 한다.
일반 API는 이 복원 DB에 대한 기동을 거절한다. 조회는 [복원 DB 점검 모드](../recovery/recovery-inspection.md)로 수행한다.
복원 DB는 점검을 위해 남는다. 현재 API 연결을 바꾸거나 실행 worker를 켜기 전에 S3 고정
버전·인증 키·외부 실행 주체와의 일치 확인이 필요하다. 현재 명령은 이를 자동으로 수행하지 않는다.

실패하면 이번 실행이 생성한 DB의 OID를 확인하고 그 DB만 삭제한다. 활성 연결 등으로 정리가
실패하면 report의 `cleanupFailureType`을 확인한다. 강제 종료·호스트 장애 때 남은 DB는
이름/OID와 소유 관계를 확인해 별도로 정리해야 한다. 기존 API/DB/Pod를 강제로 종료하지 않는다.

종료 코드는0=명시된 DB 작업 성공,2=지원하지 않는 환경/범위,1=실패다.
글로벌 역할·원래 소유권·ACL, 외부 Secret 및 전체 플랫폼 복구 성공은 포함하지 않는다.

## 실제 복원 시험

```bash
bash scripts/collect-evidence.sh postgres-backup bash scripts/test/test-postgres-backup.sh
# Compose 사용 시
bash scripts/collect-evidence.sh postgres-backup bash scripts/test/test-postgres-backup.sh --transport compose
```

시험은 별도의 합성 DB 두 개와 API를 만들고 정상 종료 시 소유 자원만 제거한다.
덤프와 원본 API/SQL 로그는0700/0600의 `.tools/postgres-recovery-test-*`에 남는다.
기본 결과 `.tools/postgres-backup-test.json`에는 시험 이름·개수·환경·정리 여부만 기록한다.
CI는 `--report docs/evidence/runs/postgres-backup-report.json`으로 이 요약만 업로드한다.
[검증 근거](../../evidence/m9-postgres-backup.md), [설계 결정](../../adr/0059-postgres-backup-restore.md).
