# M9 운영·복구·보안·백업 수용 범위

2026-10-04. [실행 지시 M9](https://app.notion.com/p/3ebbafd382d681bd920ae91452b0463a)와
[전체 설계](https://app.notion.com/p/3ecbafd382d681b295f4f878aad79160)의 Recovery / Security /
Backup을 진행한다. 원문의 확인된 수정 시각은 [출처 목록](sources.md)과 같다.
이 문서는 남은 수용 범위이며 M9 완료 선언이 아니다.

| 요구 영역 | 현재 근거 | 남은 수용 |
|---|---|---|
| 영속 명령·재조정·재시작 | 실행 lease·producer fencing·broker 권한 조정·API/Runner/VD 교체·재시도, ADR0062 복원 DB 기동 격리/조회 점검·ADR0069 원본 DB 연결 차단 | 서로 다른 장애가 겹친 상태와 DB 복원 이후 외부 실행의 회수·일치·활성화 |
| 사용자 신원·RBAC | 관리 Basic/CSRF, 내부 Device·Runner 인증 및 토큰 경계 | 실제 신원 제공자/사용자별 역할·권한 행렬·회수·운영 키 수명 |
| 감사 | Run/Task/Attempt·전환 이력, ADR0066 관리 HTTP의 영속 접수/관측 결과·현재 Basic 주체·401/403·조회·비밀값 배제 | 사용자별 신원/RBAC, 내부 worker·직접 설정 변경, 도메인 변경과 원자적 연결·외부 보존/보관 정책 |
| 전송 보호 | dev API·MinIO·MQTT TLS 및 CA 전달·인증서 오류 시험 | 실제 접근 경로 전체와 인증서 갱신·만료·키 유실 시 절차 |
| DB 백업·복원 | ADR0059의 실제 archive·새 DB 복원 구성 요소 | 배포 환경 복원·별도 장애 영역 저장·암호화·보관 정책·주기 실행 |
| 파일·상태·키 복원 | ADR0060 버전 보존·ADR0061 복원 DB 참조 대조·ADR0065 선택한 정적 키 파일의 암호화/새 경로 복원 | 배포 환경 결합 복원, 실제 Secret/CA 인벤토리·키 전달/회전, broker·장치 journal, 외부 producer 재조정·활성화 |
| 종합 장애 수용 | 구성 요소별 실제 PostgreSQL/S3/TLS/Kubernetes 회귀 | `test-fault.sh`의 종합 장애 게이트·허용 데이터 유실과 복구 시간 측정 |

ADR0063의 [Kubernetes 복구 점검](recovery-kubernetes.md)은 DB에 없는 실행까지 조회하고
UID·소유 충돌을 구분한다. 실제 DB/클러스터 메타데이터 검증은 완료했으며 실행 회수는 후속이다.
ADR0064의 [producer 중지](recovery-producer-stop.md)는 전용 namespace에서 새 Pod/Job
생성을 막고 관측한 컨테이너의 종료를 확인한다. 실제7개·판정5개를 검증했으며 quota/종료
기록을 보존한다. 전역 쓰기 차단, Remote/broker/장치 회수와 해제/활성화는 후속이다.
ADR0069의 [원본 DB 연결 차단](recovery-database-fence.md)은 명시한 DB 이름/OID/복구 UUID로
새 연결을 막고 기존 API/쓰기 backend 종료를 확인한다. 실제 PG/API10개에서 다른 DB 보존과
대상 교체 경쟁 rollback을 검증했다. DB 밖의 권한·기존 외부 요청 회수와 활성화는 후속이다.
ADR0070의 [원본 MQTT 차단](recovery-mqtt-fence.md)은 API의 브로커 관리 자격을 먼저
교체하고 기존 Device/Task 연결과 재접속을 막는다. 실제 TLS15개에서 중단/재개·역할 보존·
브로커 재시작을 검증했다. 키/journal과 복원 DB 실행 조정·새 자격의 운영 적용은 후속이다.
ADR0071의 [원본 S3 root 차단](recovery-storage-fence.md)은 현재 API의 저장소 자격과 기존
PUT/GET URL의 새 요청을 차단한다. 실제 TLS18개에서 고정 버전 보존·중단/재개·재시작·
환경변수 override를 검증했다. 이미 진행 중인 업로드·외부/내부 writer 전체 종료와 재활성화는 남는다.
ADR0072의 [S3 요청 소진 확인](recovery-storage-drain.md)은 실제로 진행 중인 PUT2개를 유지해
차단 후 늦은 완료/연결 종료·신선한0 counter·오래된 응답 거절을 검증했다. 결합25개가 PASS다.
단일 서버 S3 요청 범위이며 내부 writer·Remote/장치 journal·종합 복구 활성화는 남는다.
ADR0073의 [참조 Remote 차단](recovery-remote-fence.md)은 별도 운영 자격·설치/복구 ID로
제공자 전체를 차단하고 실제 계산 스레드 종료를 확인한다. 늦은 인증 요청·응답 유실·재시작·
기존 DB 업그레이드·timeout을 실제 TLS7개로 검증했다. 외부 제공자 계약·복원 DB 조정·활성화는 남는다.
ADR0074의 [복원 Remote 이력 점검](recovery-remote-inventory.md)은 차단된 제공자 전체를
페이지 조회하고 복원 DB의 신원·binding·요청·관측을 대조한다. 실제 PG/TLS10개에서
백업 이후 할당과 양쪽 누락/충돌·43테이블/제공자 이력 보존을 확인했다. DB 조정 쓰기와 활성화는 남는다.
ADR0075의 [복원 Remote 실행 정리](recovery-remote-retirement.md)는 새 실제 TLS 관측과 DB 잠금/
변경 대조 뒤 관측·runtime 종료·기존 명령 완료를 한 트랜잭션으로 반영한다. 실제 PG/TLS13개에서
경쟁/잠금/rollback·응답 유실·멱등·결과/다른 DB 보존·기동 격리를 확인했다. workflow 결과 확정·
미반영 성공 파일 회수·장치 journal·외부 계약·종합 활성화는 남는다.
ADR0076의 [Remote 성공 파일 회수](recovery-remote-outputs.md)는 미확정 성공의 실제 bytes를
검증·보존하고 원본/DB 자격 없이 bundle의 전체 일치를 확인한다. S3 등록·고정 version·
Result/Task/Run 확정과 종합 복구 활성화는 후속이다.
ADR0077의 [복구 파일 저장소 등록](recovery-remote-storage.md)은 실제 회수 파일을 기존
artifact 버킷에 조건부 PUT하고 고정 version·bytes/SHA를 검증한다. 실제 TLS MinIO12개에서
응답 유실·동시 등록·원래3개 version 보존·재시작·충돌/유실 거절을 확인했다. DB 결과 확정·
키/journal·종합 활성화는 남는다.
ADR0078의 [복원 Remote 결과 확정](recovery-remote-results.md)은 원본 없이 실제 S3 고정 파일과
복원 DB를 확인하고 Result·Task·Run을 원자적으로 반영한다. 실제 결합14개에서 동시 복구·
취소/새 시도 거절·rollback·응답 유실·Java digest·후속 작업 대기·격리 유지를 확인했다.
실패/취소 작업 재조정·STREAM·장치 journal·종합 복구 활성화는 남는다.
ADR0079의 [복원 Remote 실패·취소 정리](recovery-remote-failures.md)는 원래 재시도 기한/횟수,
사용자 취소와 기존 성공 결과를 보존한다. 실제15개에서 실패/예약/만료·후손 정리·rollback·
응답 유실·새 시도/기존 취소 사유 보존을 확인했다. 진행 중 offload·STREAM·장치 journal·
다른 producer·전체 복구 활성화는 남는다.
ADR0080의 [복원 Kubernetes 실행 정리](recovery-kubernetes-retirement.md)는 실제 quota/
종료 Pod와 복원 DB 신원을 재확인해 runtime·기존 명령·VD binding을 원자적으로 정리한다.
실제16개에서 잠금/rollback·응답 유실·UID 미기록/404·이력 보존을 검증했다. 미관측 producer,
VD Task allocation 결과·offload/STREAM/journal·종합 활성화는 별도다.
ADR0081은 이 종료 확인을 [VD 내부 Task/할당 정리](evidence/m9-recovery-vd-tasks.md)로 확장한다.
실제23개에서 runtime/할당3개 종료, 기존 closure/성공 결과·미배정 이력 보존과 경쟁/원복을
검증했다. Task/Run 업무 결과와 재시도/offload·종합 활성화는 후속이다.
ADR0082–0084는 기록된 workflow 취소·재시도/전환 기한과 claim 전 Job의 보존 자식까지
[실제56개](evidence/m9-recovery-unclaimed-jobs.md)로 연결했다. ADR0085는 같은 복구 ID의
참조 Remote를 실제 TLS로 새로 대조해 혼합 전환을 조정한다.
[실제64개](evidence/m9-recovery-mixed-remote-offloads.md)에서 양방향 시작 만료/취소,
성공 결과 보존·접속/신원 거절·경쟁/원복/응답 유실을 검증했다. 전환 이력은 DB fixture이며
Remote 성공/실패의 종합 결과 조정·STREAM/group/journal·활성화는 남는다.
ADR0086은 [혼합69개/Result15개/실패15개](evidence/m9-recovery-remote-offload-outcomes.md)로
실제 Remote 전환 대상 실패에 원래 재시도 예산을 적용하고 원자적 반영/원복을 확인했다.
STARTING 성공 파일만으로 누락된 시작 권한을 대신하지 않으며 별도 Result 우회와
검사 뒤 전환 삽입 경쟁도 거절한다. 성공의 시작 권한 복원·STREAM/group/journal·활성화는 남는다.
ADR0065의 [키 파일 백업](private-material-backup.md)은 명시한 정적 파일을 공개 수신자 키로
암호화하고 별도 개인 키로 새 경로에 복원한다. 실제 age/OpenSSL의 합성 키 시험을 수행한다.
운영 Secret 자동 수집·Kubernetes Secret 적용·동시 갱신 중인 journal 스냅샷·활성화는 별도다.
ADR0087의 [장치 journal 백업](device-journal-backup.md)은 동일 Device Session의 LOCAL
상태·fanout·ACK·DATA/END를 한 읽기 transaction에서 추출해 암호화하고 새 볼륨에 격리 복원한다.
[실제13개](evidence/m9-device-journal-backup.md)에서 별도 writer·원본 유실·완료 파일 경쟁·
SIGKILL·자동 재개 차단을 확인했다. 현재 DB/route/watermark 대조·원본 송신 차단·새 권한 적용과
활성화는 별도이며 snapshot 이후 센서 데이터까지 보존한다고 주장하지 않는다.
ADR0088의 [DB/journal 대조](recovery-device-journal.md)는 복원 신원·고정 Session/경로·
소비자 checkpoint 메타데이터·처리 순번/END를 읽기 전용으로 비교한다. 실제 16개에서
원본 유실·ACK 격차·세션/경로/전환 충돌·DB/marker 변경과 격리 유지를 확인했다.
checkpoint 바이트·원본 종료·브로커 권한·복원 환경 활성화는 여전히 별도다.
ADR0089의 [결합 검증](recovery-device-readiness.md)은 실제 replica 고정 버전과 최신
checkpoint 내용, 원본 브로커의 관리자 자격 회수/계정 차단을 재관측한다. 실제 27개에서
상태 summary 불일치·객체 삭제·권한 재활성화·DB 경쟁을 거절했다. 원본 Device 프로세스와
다른 producer/API 권한 회수·새 권한 배정·전체 서비스 활성화는 남는다.
ADR0090의 [원본 Device source 종료](recovery-device-source-retirement.md)는 실제 journal owner
잠금 해제와 최종 snapshot을 복원 입력에 연결한다. 종료11개/결합33개로 마지막 프레임 보존·
실제 DeviceSource 종료·잠금/파일/원본 쓰기 경쟁·격리를 확인했다. 전체 물리/전역 종료는 별도다.
ADR0091의 [복원 STREAM 경로 종료](recovery-stream-retirement.md)는 현재 원본 broker의 실제
차단을 재관측해 같은 digest의 generation만 fence/close한다. 결합47개에서 실제 DB 잠금·원복·
응답 유실·기존 이유/42테이블·다른broker 보존을 검증했다. STREAM 그룹 업무 결과/재시도·
진행 중 전환과 새 자격·종합 활성화는 남는다.
ADR0092의 [STREAM 그룹 업무 상태 조정](recovery-stream-workflows.md)은 실제 producer/broker
종료를 재관측해 기록된 취소와 원래 그룹 retry 기한 만료를 반영한다. 실제18개에서 같은 Device
fanout의 원자성·checkpoint2/고정version2 보존·잠금/원복/응답 유실과 BATCH 명령의 잘못된
우회 처리를 재현·수정했다. 기한 전 예약·최종 처리 중인 그룹·활성 전환은 보존하며 새 실행은
만들지 않는다. STREAM 전환/최종 처리 복구·외부 시작 권한·종합 재가동 수용은 남는다.
ADR0093은 같은 명령의 `--offloads`로 기록된 전환 취소와 원래 drain/start 기한 만료를
조정한다. 실제29개에서 source2→target2 종료·복원DB13개·checkpoint/배치/기한 보존과
원복/응답 유실을 확인했다. VD/자동 전환의 복원 종단·기록된 target 실패·전환 성공의 시작
권한·finalization·새 자격/종합 활성화는 여전히 남는다.
ADR0066의 [관리 감사 기록](management-audit.md)은 변경 실행 전 접수 저장과 처리 후 HTTP 결과를
분리한다. 접수 저장 실패는 실행 전503, 결과 저장 실패는 실제 응답을 유지하며 미확정으로 남긴다.
현재 관리 인증으로 API/화면에서 조회하며 실제 PostgreSQL 오류 주입·API 재시작·불변/비밀값 배제를
검증한다. 운영 사용자별 역할·외부 감사·보관 정책 및 전체 M9 수용은 포함하지 않는다.

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
