# 복원 DB와 현재 Kubernetes 실행 대조

먼저 [새 DB 복원](postgres-backup.md)과 [DB/S3 참조 대조](recovery-references.md)를 수행한다.
일반 API와 worker를 복원 DB에 연결하지 않는다. 아래 명령은 실행을 종료하거나 재가동하지 않는다.

```bash
bash scripts/inspect-recovery-kubernetes.sh \
  --context <명시한-context> \
  --namespace edgeai-runtimes \
  --database edgeai_restore_<복원명> \
  --restore-report <복원-diagnostics>/restore-report.json \
  --output .tools/recovery-kubernetes-<새-점검명>
```

namespace를 여러 번 지정할 수 있다. Compose PostgreSQL을 조회하면 `--transport compose`를
추가한다. native 클라이언트 경로는 `--pg-bin`으로 선택한다. DB 인증은 프로젝트 `.env`를 사용하고
Kubernetes는 지정한 context를 사용한다. 현재 context를 변경하지 않는다.

소유자 전용 `inventory.json`에서 다음을 확인한다.

| 분류 | 의미 |
|---|---|
| `ABSENT_FROM_RESTORED_DATABASE` | 현재 실행이 복원 DB에 없음. 백업 이후 생성됐을 수 있음 |
| `DATABASE_UID_MATCH` | 관측한 객체 UID와 복원 DB UID가 같음. 실행 상태·안전한 재개를 보장하지 않음 |
| `DATABASE_UID_NOT_RECORDED` | DB에 실행은 있지만 해당 객체 UID가 아직 기록되지 않음 |
| `DATABASE_UID_MISMATCH` | 이름/실행 식별자는 같아도 Kubernetes 객체 UID가 다름 |
| `OWNERSHIP_CONFLICT` | 소유 표시 또는 실행 식별자가 기대와 다름 |
| `ORPHAN_OR_CONFLICTING_RUNTIME_POD` | 대응하는 소유 Job/controller 관계를 확인할 수 없음 |
| `NOT_OBSERVED` | DB 객체가 현재 조회 범위의 목록에 없음. 물리적 종료 판정 불가 |

`databaseNamespacesNotObserved`가 있으면 해당 namespace는 조회되지 않았다. 여러 API 요청과
DB snapshot은 원자적 관측이 아니며 기존 제어기가 새 객체를 만들 수 있다. 이 결과를 나중의
삭제 권한이나 재가동 조건으로 사용하면 안 된다. 실제 회수 명령은 현재 UID와 소유권을 다시
확인해야 한다. Remote·MQTT 권한·장치·claim Secret·키/journal은 이 명령의 관측 대상이 아니다.

메타데이터 분류·페이지 경계 회귀는 `python3 scripts/test-recovery-kubernetes.py`로 실행한다.
실제 복원 DB와 별도 소유 namespace의 시험은 다음과 같다. 현재 패키징 API JAR이 필요하다.

```bash
bash scripts/test-recovery-kubernetes-live.sh --context <시험-context>
```

시험용 Pod는 존재하지 않는 노드에 지정해 모델 프로세스를 시작하지 않는다. 실제 Kubernetes
메타데이터 목록과 DB 조회 시험이며 실제 producer 종료/복구 시험과 구분한다. 시험은 생성한
namespace UID와 DB OID를 확인하고 정리한다. [검증 근거](evidence/m9-recovery-kubernetes.md).
