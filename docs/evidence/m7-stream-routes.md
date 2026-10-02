# M7 DataRoute·RouteGeneration 제어 상태 검증

2026-10-03 KST. ADR0023/V19의 내부 서비스·실제 PostgreSQL 검증이다.
공개 STREAM Run은501을 유지한다. 이번 범위에는 broker 권한 발급/회수 adapter,
주기적인 reconciliation worker, 인증된 스트림 배정 API, 실제 Runner 스트림 실행이 없다.

## 구현

DataRoute는 같은 Run의 Task 입력과 불변 STREAM DAG edge 또는 Device 출처를 연결한다.
입력별 한 경로를 유지하고 Device Profile/sourceMode와 포트를 고정한다. 실제 주체인
DeviceSession/TaskAttempt·epoch는 RouteGeneration에 저장하며 변경할 수 없다.
세대 준비 요청은 ID/digest로 재전송을 복구한다. 일치하는 broker 설정/정책 확인 뒤 활성화하고,
fence 이후에는 늦은 활성화와 갱신을 거절한다. 이전 권한의 회수 확인 전에는 다음 세대를 열지 않는다.

5–120초 lease·Run/Task 현재 상태·Device 세션을 검사하고, 연결/재시도/취소 이후에는
reconcile 호출 전에도 accepts 검사가 false가 된다. 실제 MQTT 클라이언트가 이 권한과 lease를
지키도록 연결하는 작업은 남았다. BrokerReceipt는 신뢰된 adapter가 공급할 내부 입력이며
문자열 일치만으로 broker의 실제 권한 회수나 caller 인증이 증명되는 것은 아니다.

VD→Device→Run→Route 잠금은 기존 VD registry 수정 및 실행 취소 순서를 보존한다.
SQL FK·partial unique·trigger가 동일 Run/주체/epoch·한 열린 세대·순차 증가·불변 이력·상태 전이를
제약한다. 직접 SQL로 상태를 바꾸는 코드도 서비스와 같은 잠금 계약을 지켜야 한다.

## 실제 검증

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 새 경로 PostgreSQL 통합12개 | 20261002T220858Z-fd48a58b | PASS/0 |
| 기존 포함 전체 PostgreSQL148개 | 20261002T220942Z-7573b360 | PASS/0, 실패/skip0 |
| 백엔드 단위/MVC82개 | 20261002T221047Z-2474539d | PASS/0, 실패/skip0 |
| OpenAPI4개 생성 타입/패키징 계약·MVC25개 | 20261002T221124Z-f8365e53 | PASS/0 |

`StreamRouteIntegrationTest`는 실제 DB에서 다음을 확인한다.

- Task edge/port/budget·Device 출처 고정·중복 생성과 다른 Run/점유 입력 거절.
- 같은 요청8개 동시 전송은 동일 세대1개, 다른 요청8개는 성공1개와 열린 세대 충돌7개.
- broker 설정/정책 불일치 거절, QUEUED와 RUNNING의 데이터 사용 권한 구분.
- fence 직후 권한 상실, 늦은 grant/renew 거절, revoke 전 다음 세대 거절, 닫힌 요청 재전송 시 재활성화 없음.
- lease 축소 방지·주체/상한 검사·만료된 ACTIVE/PREPARING의 부활 차단.
- Device 재접속/해제, producer와 consumer 재시도, 공개 서비스의 Run 취소 후 이전 권한 차단.
- 실제 DB의 외래 주체/epoch·세대 건너뛰기·동시 열린 세대·불변 필드 수정·이력 삭제/truncate 거절.
- 실제 PostgreSQL blocking PID로 VD 잠금 대기를 관측하고 Device/Run NOWAIT 잠금 성공으로 잠금 순서 확인.

Task RUNNING 상태와 broker grant/revoke 확인은 **명시적 fixture**다. 실제 장치 payload·Pod·브로커
권한 관리 수용 증거는 아니다. V19는 실제 로컬 DB에 적용했으며 적용 후 변경하지 않았다.
V1–V18 원본 SHA-256 일치도 확인했다. 전체 PG148개는 기존136개에 새12개를 포함한다.

초기 시험 작성에서 JSON decoder의 Object 반환을 Map으로 다루지 않은 컴파일 오류
(`20261002T220712Z-a29d2801`)와 SERVICE 입력의 required 필드 누락
(`20261002T220753Z-c6dfafe1`,12개 실패)을 확인했다. 기존 parser 계약에 맞게 fixture를 수정한 뒤
새12개 및 전체148개가 통과했다. V19 SQL이나 기존 SERVICE 계약을 완화하지 않았다.

재현: `bash scripts/test-integration.sh`. 전체 CI에는 같은 통합시험이 자동 포함된다.
공개 API/화면에는 이번 변경이 없다.

## CI·배포 확인

소스224befe의 [CI37071378244](https://github.com/dsa04156/edgeai/actions/runs/37071378244)는
runner/scaffold/storage/images/gitops5개 모두 success다. 내려받은4개 artifact의 result.json16개를
직접 확인했으며 모두 PASS/0이다. 실제 kind `20261002T222435Z-e88e0394` 로그에서
Kubernetes BATCH·재시도/전환·Remote·API 재시작/취소·S3 결과20개와 VD 수명·Task 실행·S3 결과5개,
자원 정리와 최종 API/UI/DB/MinIO/Remote Ready를 확인했다.

Actions의 GitOps pin b742894를 반영한 기존 클러스터도
`20261002T224649Z-9f80f766`에서 PASS/0이다. 소스224befe의 API/Dashboard/MinIO imageID 일치,
Ready·PVC Bound·정확한 Argo revision/Synced와 VD 실행 활성화를 확인했다.
기존 공유 Ingress 상태 때문에 aggregate health는 Progressing이며 Healthy라고 판정하지 않는다.
이 배포는 DataRoute 내부 제어 상태까지 포함하며 이후 broker adapter/worker 배포 증거는 아니다.

## 후속 연결

broker 계정/정확한 topic ACL의 발급·회수와 재시작 복구, 실제 Pod/Device 신원 확인과 배정,
Runner/SERVICE 스트림 인터페이스, 외부 S3 checkpoint/새 Pod 복원, 공개 조회/Swagger/UI와
실제 Kubernetes 다중 장치 BATCH/STREAM 데모가 남았다. 전체 M7·M5 복원 완료로 판정하지 않는다.
