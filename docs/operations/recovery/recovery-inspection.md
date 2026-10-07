# 복원 DB 조회 점검

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

복원한 `edgeai_restore_*` DB에 일반 API를 연결하면 기동이 거절된다. 과거 runtime/명령이
기존 외부 작업과 겹쳐 실행되지 않도록 한 격리다. 백업 도구가 남긴 복원 marker가 있는 DB는
명시적인 조회 전용 모드로 점검한다.

아래는 기존 개발 API와 다른 loopback 포트에서 점검하는 예다. 저장소 루트에서 실행한다.
이미 만든 최신 JAR이 필요하며, 없으면 먼저 `bash scripts/test/test-unit.sh`와
`backend/gradlew -p backend :app:bootJar`로 검증·패키징한다.

## 실행

```bash
source scripts/lib.sh
load_env
export EDGEAI_DB_NAME=edgeai_restore_drill
export EDGEAI_RECOVERY_INSPECT_ONLY=true
export EDGEAI_RUNTIME_ENABLED=false EDGEAI_VD_ENABLED=false EDGEAI_REMOTE_ENABLED=false
export EDGEAI_STREAM_ENABLED=false EDGEAI_STREAM_BINDINGS_ENABLED=false EDGEAI_STREAM_RUNS_ENABLED=false
export EDGEAI_KUBE_ENABLED=false
export EDGEAI_BIND_ADDRESS=127.0.0.1 EDGEAI_API_PORT=18082 EDGEAI_API_TLS_ENABLED=false
java -jar backend/app/build/libs/edgeai-control-plane.jar
```

## 결과 확인과 제한

DB 이름은 실제 복원 대상으로 바꾼다. 새 `.env` 값을 읽어 설정을 다시 덮어쓰는 개발 시작
wrapper 대신 위 JAR을 직접 실행한다. 예시는 loopback 전용이며 기존 Platform-Service 포트와 구분한다.

`http://127.0.0.1:18082/swagger-ui.html`에서 조회할 수 있다. 실제 실행 기능은 모두
꺼져 있으므로 실행 기능에 의존하는 endpoint는 사용할 수 없다. 유효한 CSRF를 갖춘
쓰기 요청도403이며, PostgreSQL 연결도 쓰기 불가다. 기존 Profile/Workflow/Run 조회 등으로
복원 내용을 확인한다. DB 점검과 [고정 S3 전체 참조 대조](recovery-references.md)는 별개다.

점검 시 Flyway는 현재 schema를 검증만 한다. 끄거나 custom connection-init SQL을 넣으면
점검 기동이 거절된다. marker가 없는 이전 복원은 최신 도구로 새 DB에 다시 복원한다.
일반 원본 DB에는 이 점검 모드를 사용할 수 없다.

이 모드에는 운영 재가동 기능이 없다. 원래 Pod/Remote/장치 producer의 종료·권한 회수와
Secret/CA·journal 복구를 확인하기 전에 marker를 지우거나 DB 이름을 바꾸지 않는다.
[M9 전체 수용](../../requirements/m9-requirements.md), [설계 결정](../../adr/0062-restored-database-quarantine.md).
