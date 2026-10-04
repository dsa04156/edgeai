# M9 운영·복구·보안·백업 수용 범위

2026-10-04. [실행 지시 M9](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a)와
[전체 설계](https://app.notion.com/p/3ecbafd382d681b295f4f878aad79160)의 Recovery / Security /
Backup을 진행한다. 원문의 확인된 수정 시각은 [출처 목록](sources.md)과 같다.
이 문서는 남은 수용 범위이며 M9 완료 선언이 아니다.

| 요구 영역 | 현재 근거 | 남은 수용 |
|---|---|---|
| 영속 명령·재조정·재시작 | 실행 lease·producer fencing·broker 권한 조정·API/Runner/VD 교체·재시도, ADR0062 복원 DB 기동 격리/조회 점검 | 서로 다른 장애가 겹친 상태와 DB 복원 이후 외부 실행의 회수·일치·활성화 |
| 사용자 신원·RBAC | 관리 Basic/CSRF, 내부 Device·Runner 인증 및 토큰 경계 | 실제 신원 제공자/사용자별 역할·권한 행렬·회수·운영 키 수명 |
| 감사 | Run/Task/Attempt·전환·작업 이력 | 사용자 행위/권한 거절/설정 변경의 감사 주체·보존·조회·비밀값 제외 검증 |
| 전송 보호 | dev API·MinIO·MQTT TLS 및 CA 전달·인증서 오류 시험 | 실제 접근 경로 전체와 인증서 갱신·만료·키 유실 시 절차 |
| DB 백업·복원 | ADR0059의 실제 archive·새 DB 복원 구성 요소 | 배포 환경 복원·별도 장애 영역 저장·암호화·보관 정책·주기 실행 |
| 파일·상태·키 복원 | ADR0060 버전 보존·ADR0061 복원 DB 참조 대조·ADR0065 선택한 정적 키 파일의 암호화/새 경로 복원 | 배포 환경 결합 복원, 실제 Secret/CA 인벤토리·키 전달/회전, broker·장치 journal, 외부 producer 재조정·활성화 |
| 종합 장애 수용 | 구성 요소별 실제 PostgreSQL/S3/TLS/Kubernetes 회귀 | `test-fault.sh`의 종합 장애 게이트·허용 데이터 유실과 복구 시간 측정 |

ADR0063의 [Kubernetes 복구 점검](recovery-kubernetes.md)은 DB에 없는 실행까지 조회하고
UID·소유 충돌을 구분한다. 실제 DB/클러스터 메타데이터 검증은 완료했으며 실행 회수는 후속이다.
ADR0064의 [producer 중지](recovery-producer-stop.md)는 전용 namespace에서 새 Pod/Job
생성을 막고 관측한 컨테이너의 종료를 확인한다. 실제7개·판정5개를 검증했으며 quota/종료
기록을 보존한다. 전역 쓰기 차단, Remote/broker/장치 회수와 해제/활성화는 후속이다.
ADR0065의 [키 파일 백업](private-material-backup.md)은 명시한 정적 파일을 공개 수신자 키로
암호화하고 별도 개인 키로 새 경로에 복원한다. 실제 age/OpenSSL의 합성 키 시험을 수행한다.
운영 Secret 자동 수집·Kubernetes Secret 적용·동시 갱신 중인 journal 스냅샷·활성화는 별도다.

M9 복원 수용에는 과거 DB만 복원한 상태에서 worker가 중복 작업을 시작하지 않도록 원래
Pod/Remote/장치 producer와의 경계를 확인하는 절차가 필요하다. 이전 실행의 권한을 회수하고,
고정 S3 version/checksum·checkpoint가 보존되는지 확인한 다음 서비스를 활성화해야 한다.
다른 bucket에 같은 key로 다시 PUT해서 version ID가 달라지는 경우를 보존 성공으로 판정하지 않는다.

RPO/RTO, 보관 기간·저장 위치, 사용자/역할 정책과 외부 신원 제공자는 확정되지 않았다.
이 값은 측정/실험 설정과 운영 수용 기준을 구분해 기록한다. 별도 승인 없이 임의 수치를
최종 합격 기준으로 정하지 않는다. 독립 구현·검증을 이어가되 미확정 계약은 완료로 표시하지 않는다.

현재 DB 명령은 백업 시 원본을 유지하고 새 DB로만 복원한다. 실제 업무 데이터 대신 공개
API로 만든 합성 데이터를 사용한다. 상세 동작은 [ADR0059](adr/0059-postgres-backup-restore.md),
실행법은 [백업 문서](postgres-backup.md), 근거는 [DB 복원 시험](evidence/m9-postgres-backup.md)을 따른다.
전체 단계의 판정은 [PLAN](../PLAN.md)과 [검증 목록](verification-matrix.md)을 유지한다.
