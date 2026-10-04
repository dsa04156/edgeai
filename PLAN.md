# 개발 계획

## 활성 목표: M0–M10 전체 구현과 검증

ADR0076은 차단된 Remote의 미반영 성공 파일을 개인 묶음으로 회수하고 독립 검증한다.
최종 실제 PG16/TLS 결합17개(100728Z-2813d1a2), Python17/Java gateway13개(100728Z-f6d01f72) PASS다.
원본 변조/경로·DB 경쟁·원본 없는 파일 검증과 이력 보존을 확인했다.
[검증 근거](docs/evidence/m9-recovery-remote-outputs.md). S3 등록·고정 version·Result/Task/Run
확정·journal·전체 활성화는 남는다. 새 코드의 CI/배포는 별도 확인한다.

ADR0075는 실제 종료한 Remote의 복원 DB 관측·runtime·기존 명령을 원자적으로 정리한다.
실제 PG16/TLS13개(095215Z-59a4cda4), 기존 이력10개 회귀(095133Z-eaa68841) PASS다.
DB 경쟁·잠금 제한·중간 오류 rollback·커밋 응답 유실·0변경 재실행·43테이블/결과/다른 DB 보존·
일반 API 기동 차단을 확인했다. [검증 근거](docs/evidence/m9-recovery-remote-retirement.md).
Compose17 CI gate를 추가했다. workflow 결과 확정·미반영 성공 파일·journal·전체 활성화는 남는다.

ADR0074는 복원 DB와 차단된 참조 Remote 전체 이력을 읽기 전용으로 대조한다.
실제 PG16/별도 TLS 제공자2·복원DB2의10개(092539Z-1d04c194), Python14/Java gateway13개
(092408Z-ed5d7eed) PASS다. 백업 후 실제 할당·양쪽 누락·binding/신원/요청/관측 충돌과
43테이블/제공자 이력 보존·페이지 누락 거절을 확인했다. [Remote 이력 근거](docs/evidence/m9-recovery-remote-inventory.md).
Compose17 CI gate를 추가했다. DB 조정 쓰기·장치 journal·외부 업체 계약·종합 활성화는 남는다.
선행9caa7dd CI37188905983은5jobs/원시26개 PASS(092606Z-b89a28f6), GitOpsd351a3b의
정확한imageID/Ready/PVC/ArgoSynced(092608Z-eeb39b40), 기존10파일/두PVC·TLS보존
(092659Z-cff29cb6)도 PASS다. 공유Ingress healthProgressing이며 새 ADR0072–0074 CI/배포는 후속이다.

ADR0073은 참조 Remote 전체의 새 요청을 영속 차단하고 실제 계산 스레드 종료를 확인한다.
TLS7개(090831Z-b7537085)·기존 Java gateway13·실제 별도 PG/MinIO27개 PASS다.
늦은 인증 예약/입력·완료/실패 이력·응답 유실/재개·기존 SQLite 업그레이드·SIGKILL 후 차단 유지·
timeout을 검증했다. [Remote 복구 근거](docs/evidence/m9-recovery-remote-fence.md).
기존 CI scaffold에 포함하며 새 변경의 원격 CI/배포는 후속이다. 실제 외부 Remote 계약·
복원 DB 실행 이력 조정·장치 journal·종합 복원 활성화와 전체 M5/M7–M10 수용은 남는다.

ADR0072는 root 차단 이전에 이미 인증된 S3 요청의 소진을 별도로 확인한다.
실제 PUT2개를 유지한 뒤 차단하고, 진행2→1에서는BLOCKED, 하나의 늦은 완료·다른 연결 종료 뒤
새 완료 counter와 진행0을 확인했다. 기존18개를 포함한25개 PASS(083947Z-c4e158e0).
[요청 소진 근거](docs/evidence/m9-recovery-storage-drain.md). 단일 서버의 읽기 검증이며
내부 writer·Remote·장치 journal·종합 복구 활성화는 남는다.
선행9caa7dd CI37188905983의 scaffold/storage/runner 성공·원시24개/PG230·S3차단18·
MQTT차단15/35계정·Runner111/MQTT95를 확인했다. images의 실제 Kubernetes 검증은 진행 중이며
이번 새 요청 소진 코드의 CI/배포는 후속이다.

ADR0071 원본 S3 root 접근 차단의 실제 TLS18개가 PASS(082045Z-c2caa5f1)다. 기존 PUT/GET
URL 거절·고정2버전 보존·부분 중단/같은 ID 재개·SIGKILL 재시작·환경변수 우선순위를 확인했다.
[저장소 차단 근거](docs/evidence/m9-recovery-storage-fence.md). 새 CI storage 게이트를 추가했다.
진행 중 업로드의 종료, 외부 writer/journal·종합 복원 활성화는 남는다.
선행5ed7b03 CI37185328851의5jobs/원시24개 PASS(081857Z-83bd1448), GitOps8cf5a23의
정확한API/dashboard/MinIO imageID·Ready/PVC·ArgoSynced(082000Z-1b9a0d9a), 기존10파일/
두PVC·TLS/S3보존(082000Z-565ea715)을 확인했다. 공유Ingress aggregatehealthProgressing이다.
새 MQTT15·S318개 게이트의 원격 CI/배포 및 전체 M9 수용은 별도다.

ADR0070 원본 MQTT 차단의 실제 TLS15개가 PASS(075002Z-74d8ac1a)다. 기존 관리자 자격을
교체한 뒤35계정·실제 Device/Task 연결2개의 재접속을 차단하고 부분 중단/동일 ID 재개·역할 이력·broker
재시작 보존을 확인했다. [브로커 차단 근거](docs/evidence/m9-recovery-mqtt-fence.md).
새 CI gate를 추가했으며 journal·Remote/S3 writer·복원 실행 조정/활성화는 남는다.
선행5ed7b03 CI37185328851은3jobs/원시22개·PG230·Runner111·MQTT95·Compose DB fence10개
PASS를 확인했으며 이후 images·GitOps까지 위에서 검증했다. 새 MQTT 복구15개는 해당
CI에 포함되지 않는다. 전체 M9 수용은 미완료다.

ADR0069 원본 DB 연결 차단의 실제 PG/API10개가 PASS(065711Z-4d27de4e)다. API pool과
미완료 쓰기 연결3개 종료·새 연결 거절·다른 DB 보존·동일 복구 재개·DB 교체 경쟁 rollback을
확인했다. [연결 차단 근거](docs/evidence/m9-recovery-database-fence.md). 새 Compose CI gate를
연결했으며 종합 writer 회수·복구 활성화와 전체 M9 수용은 남는다.

부하 실패 시 DB 관측값이 사라지는 문제를 수정했다. 실제 API/PG의 기존 판정4개와 새 큐 포화
회귀가 PASS(062331Z-f6a52a6a)이며 실패 판정·부하량·정리 조건은 유지한다.
별도20ms 동시 커밋은 기본 전체9,240건과 비계측1,000대180초19,800건을 모두 처리했으나,
진단 실행은 기록 종료 경계에서8건 미발송이었다. 기존 제품의 같은 비계측180초는290건 미발송
(063006Z-36e08240)이며 관측18,736행·감사20,741쌍을 보존했다. 공유 호스트 변동과 계측
영향을 확정하지 않았고 제품은 개별 transaction을 유지한다. [후속 진단](docs/evidence/m8-audit-storage-diagnostics.md).
5f80455 CI37181374516은 runner/storage/scaffold 성공·images 실패·gitops skipped로 끝났다.
완료된3jobs의 산출물21개와 PG230/MQTT95/Runner111 성공을 확인했으나 VD 전환 뒤 Attempt
상태 검사가 실패했고 당시 값이 없어 원인은 미확정이다. 취소 전 제한된 상태를 보존하는 진단3개
PASS. 같은 Runner/MinIO·기존 JAR의 실제 클러스터 단일 전환은 PASS(070243Z-4715e4c0,
Node1/VD3Pods·S3결과3개·정리)지만 CI 실패 해결이나 새 배포 성공으로 판정하지 않는다.
[실패·후속 진단](docs/evidence/m7-offload-ci-diagnostics.md).

ADR0067 감사 동시 커밋 실험은 단위129·PG231·실제API8·백업13개를 통과했으나 미채택이다.
기본 전체 부하의 첫 실행은200건 미발송 FAIL, 같은 조건 진단은9,240건/감사12,105쌍 보존이다.
추가1,000대180초 진단은374건 미발송과 같은 구간의 JDBC commit/WAL 대기를 기록했다.
제품은 기존 개별 transaction으로 복원했다. 부하 안정화와 합의 성능 수용은 미완료다.
[동시 커밋 근거](docs/evidence/m9-audit-group-commit.md).
실행저장소 회귀47개 중 공유 VD 복구1개가 MQTT 거절로 실패했고, 별도 실제 브로커 시험에서
재접속 상태 오류와 heartbeat 이전 권한 회수 순서를 재현했다. ADR0068 수정의 SDK19·
전체 MQTT95개·Runner111개는 PASS다. 프로토타입 조합의 전체 저장소47개도 통과했으며
개별 감사 transaction을 유지하는 최종 PG230개와 실행저장소47개도 PASS다.
최종 API JAR은 기존 검증·배포 JAR과 SHA256이 같아 단위122·API/Swagger·복원 근거를 재사용한다.
최종 부하 판정4개도 PASS(055524Z-55dffcf0)이며 각60감사쌍/117transaction을 확인했다.
[재연결 근거](docs/evidence/m7-broker-first-reconnect.md).

선행0e9d7a0 CI37177835436의5jobs/원시23개(053859Z-52c9a721), GitOpsfe1a995의
정확한imageID·Ready/PVC·ArgoSynced(053931Z-f013990c), 기존10파일/두PVC·TLS/S3보존
(054004Z-40e28b5d)을 확인했다. 공유Ingress aggregatehealthProgressing이다.
아래는 감사 기본 구현과 선행 배포 이력이며 ADR0068 새 CI·배포와 구분한다.

ADR0066/V34 관리 HTTP 감사 접수·관측 결과·조회 API/UI를 연결했다. 단위122·실제PG229·
패키징API8·실행저장소47·감사포함백업13/43테이블·참조9·Kubernetes점검6개 PASS다.
실제API/브라우저/Swagger12개와 DB장애복구, 기존Task9,371개 V34보존도 확인했다.
전체부하는 첫 실행1,000대에서108건미발송으로FAIL, 같은 설정의 진단 재실행은9,240요청/
누락0·감사12,105쌍 보존이나 p95 228.13ms/WAL대기를 관측했다. 최초 실패 해결·합의 성능
수용으로 판정하지 않는다. [관리 감사 근거](docs/evidence/m9-management-audit.md).
감사 기본 구현의 CI/이미지·배포는 위에서 확인했다. 사용자별신원/RBAC·외부감사/보관정책은 남는다.

선행4f408dd CI37174711098의5jobs/원시22개와 새키파일10·kind producer종료7개를
확인했다(043600Z-294040c0). GitOpsee91977의 정확한이미지·Ready/PVC·ArgoSynced
(043601Z-82aa8520), 기존10파일/두PVC·TLS/S3보존(043807Z-5d799a7c)도PASS다.
공유Ingress aggregatehealthProgressing이며 신규 감사 코드의 CI/배포와 구분한다.

ADR0065는 선택한 정적 키 파일의 암호화 백업과 새 경로 복원을 연결한다. 실제 age/OpenSSL의
10개 시험이 PASS(033214Z-e49bced8): 원본 삭제 뒤4개 파일/TLS 키쌍 복원, 잘못된 키·손상·
경로/링크·덮어쓰기·변경 입력 거절, X25519/PQ를 확인했다. [키 복원 근거](docs/evidence/m9-private-material.md).
새 CI 게이트를 추가했으며 운영 Secret/journal·서명·키 회전/적용·종합 활성화는 남는다.

ADR0064는 전용 namespace의 새 Pod/Job 생성을 차단하고 관측한 실행의 종료를 확인한다.
판정5·실제클러스터7개 PASS(025504Z-74efb678):컨테이너2/자식2 종료·timeout/재개·차단/
기록/다른finalizer 보존·소유namespace정리. 새kind게이트는후속이다.
명시적CI image/source입력의7개재검증도PASS(030222Z-f8fe9a12)다.
[종료 근거](docs/evidence/m9-recovery-producer-stop.md). 전역쓰기차단·해제/활성화는미완료다.
aa21e46 CI37171839136은5jobs/원시21개PASS(033736Z-c4b9c68f)다. 단위113/PG226·
PG복원13/S3백업11/복원참조9·STREAM24개/Node43·VD33Pods/결과54개를 포함한다.
GitOpsfd86520 실제 이미지·Ready/PVC·ArgoSynced(033749Z-e4f8ce6c), 기존10파일/두PVC
보존(033850Z-a2a4c713)도PASS다. ADR0064/0065 새 CI 게이트는 후속이다.

최신: ADR0063 복원 DB/Kubernetes 조회 대조의 분류7·실제 PG/별도 namespace6개가
PASS(023306Z-de6fe9bb)다. DB에 없는 실행과 소유 충돌을 찾고41테이블·객체5개를 보존했다.
[관측 근거](docs/evidence/m9-recovery-kubernetes.md). 실제 producer 회수·활성화는 남는다.
6617d939 CI37168798128은19/20원시 결과PASS지만 마지막 배포 데모 driver에서 실패하여
gitops는 미실행이다. STREAM24개/S354는 통과했다. driver 상세가 artifact에서 빠져 원인은
미확정이며, 후속은 안전한 실패 위치를 정해진 보고서에 보존한다. 성공 배포로 판정하지 않는다.
기존 클러스터의 같은 데모3개/Pod8개/S36개 재실행은 PASS(023612Z-ad91d440)다.
CI 실패의 원인 해결로 간주하지 않는다. [실패·재확인 근거](docs/evidence/m9-backup-ci-failure.md).

2026-10-02 사용자가 전체 단계의 구현·검증을 지시했다. M1 이후를 진행하며 전체 완료를
현재 구현 범위로 축소하지 않는다. 매 단계 OpenAPI/DDL/코드/UI/시험/증거를 함께 갱신한다.

| 단계 | 남은 구현·검증 게이트 |
|---|---|
| M0 | 완료 — 초기 개발 환경·저장소·Swagger·GitHub Actions/GHCR·ArgoCD 연결 |
| M1 | 완료 — DEVICE/SERVICE/VD Profile 등록·불변 버전 관리·API/UI·실DB 검증 |
| M2 | 완료 — Device/Node/Observation, UI·실DB·CI·실 Kubernetes 읽기·배포 검증 |
| M3 | 완료 — DAG/Run/Task/Attempt·로컬·CI·이미지·ArgoCD·실제 Ingress 검증 |
| M4 | 완료 — 실제 kind·기존 클러스터 Runner/MinIO/Result·실패/취소·CI/배포 검증 |
| M5 | 재시도·전환·참조 Remote 검증 완료. 상태형 checkpoint 복원과 실제 외부 시스템 계약 수용은 남음 |
| M6 | 완료 — 영속 VD·원본/실행 이력·Operation·실제 자식 Task/Result·교체/취소/재시도·CI·배포·UI 검증 |
| M7 | 진행 중 — 다중 장치 BATCH/STREAM DAG·전환·복구 구현/시험, 외부 계약·전체 수용 잔여 |
| M8 | 부분 검증 — 100→300→1,000 장치 관리 부하·인증 병목 개선, 합의 성능 수용 기준 잔여 |
| M9 | 일부 구현 — TLS·재시작 복구·DB/S3/키 백업·관리 HTTP 감사, identity/RBAC·외부 감사/보관·종합 복구 잔여 |
| M10 | 미완료 — 실제 KubeEdge·ARM/x86·GPU/NPU, 실제 모델/2세부 연동, 합의한 성능 수용 기준 충족 |

M0–M4 및 M6 범위의 구현·검증을 완료했으며 현재 M5 잔여 검증과 M7 구현을 진행한다.
독립 M9 DB 백업·복원도 ADR0059로 구현했다. 실제 PostgreSQL16/패키징 API의10개 시험에서
41테이블·복원 조회·불변 제약·백업 시점 경계·기존 DB/손상/권한 거절·실제 복원 오류 정리를
통과했다(010138Z-7ebea0c3). Compose17 CI 게이트를 추가했으며 새 CI 검증은 후속이다.
키/journal·운영 활성화와 배포 환경의 종합 복구는 남는다. [M9 수용 범위](docs/m9-requirements.md),
[DB 검증 근거](docs/evidence/m9-postgres-backup.md).
ADR0060은 별도 MinIO에 고정 S3 version을 복제하고 독립 검증한다. 실제 TLS source/replica의
11개 시험에서4개 version/262,176bytes, 원본 종료·replica 재시작·기존 설정/미연결 대상 보존·실패 정리를
통과했다(013635Z-8b24378d). [저장소 검증](docs/evidence/m9-storage-backup.md).
ADR0061로 복원 DB의 모든 result/checkpoint를 별도 MinIO의 고정 버전과 대조한다.
실제PG16/TLS의9개 시험(014850Z-ae31f51c)은 원본 DB/MinIO 없이 전체4참조/1,287bytes와
누락·불일치·다른 복원 신원·미지원 schema 거절을 확인했다. 복원 식별자를 추가한 DB10개
회귀도 PASS(014814Z-0a42874e)다. 새 CI·운영 Secret/journal·producer 재조정/활성화는
남는다. [참조 대조 검증](docs/evidence/m9-recovery-references.md).
ADR0062는 복원 DB의 일반 기동을 차단하고 명시적 조회 전용 점검만 허용한다. DataSource
단계에서 Flyway 비활성 우회도 거절하고, 실행/inventory 비활성·schema 검증·새 연결의 실제
읽기 전용 설정·인증/CSRF 쓰기403을 연결했다. 단위113·실제PG226·패키징 API/백업13·계약은
PASS다. 격리 실제 저장소47(020809Z-89d0fd0c)·새 JAR 참조 대조9(021137Z-d86797ad)도
통과했다. 새 CI/배포와 외부 producer 회수/운영 활성화는 남는다.
[격리 검증](docs/evidence/m9-recovery-quarantine.md), [점검 실행](docs/recovery-inspection.md).
M7 외부 계약·전체 수용을 유지한 채 독립 M8 관리 부하 측정을 병행한다. ADR0057은 실제
API/전용 PG DB에서100→300→1,000 장치의 예정 시각 기준 지연·오류·누락·정합성·자원을 기록한다.
전체60초씩의9,240요청·오류/누락0·DB정합성·자원 정리를 확인했다(234836Z-e9dbbb5f).
네 가지 실제 판정 회귀도 PASS(235714Z-8cb1074e)다. 성능 수치는 아직 미합의이며
측정 성공과 M8 완료를 구분한다. 후속 JFR 표본99.2%가 BCrypt 반복 검증이었다.
ADR0058의 성공 비교 캐시(64개/30초)와 전체 단위111개를 검증했고 실제 전체 규모 재측정에서
1,000대 p95는54.64→8.16ms, API CPU는5.15→0.164core로 줄었다. 오류/누락0·DB정합성·
실제 Basic/CSRF 거절·정리도 유지했다. [인증 병목 개선](docs/evidence/m8-authentication-load.md)의
PG223·실제 저장소47·최종 네 가지 판정 회귀도 통과했다. 035eb0e CI37165270385의5jobs/
원시18개·새 부하 판정4개·STREAM24개/Node43·VD33Pods/S354도 확인했다(012601Z-ccc48b9c).
GitOpsa7702d7의 실제 imageID·Ready/PVC·ArgoSynced(012450Z-5bd5ecac), 이후 기존10파일/
두PVC 보존(012817Z-4bf104a3)도 PASS다. 합의 성능 수용은 남는다.
[실행 방법](docs/load-testing.md), [측정 근거](docs/evidence/m8-management-load.md).
ADR0056/V33의 VD STREAM 그룹 수동 NODE 전환을 구현했다. 동료 VD 유지·전체 종료/회수 장벽·
마지막 VD claim·Swagger/UI를 연결했다. PG223·단위105·실제 저장소47·UI46·실API/Swagger10,
V33 업그레이드의 기존 Task9,371개 보존과 실제 Kubernetes 전환3개/Node3Pods·VD7Pods/S36개를
통과했다. 후속 대기 중 API 교체·전환 취소2개/Node1Pod·VD5Pods/S33개도 PASS다
(232628Z-8d401390). c3b7122의 CI37162109091은5jobs/원시17개·STREAM24개/Node43·VD33/S354
PASS다(002842Z-60817531). GitOpsce9b834의 정확한 이미지·Ready/PVC·ArgoSynced도 확인했다
(002756Z-b7ddced1). 배포 후 기존10파일/두PVC 보존도 PASS(003127Z-fb775559)다.
M8 인증 캐시의 새 CI/배포와 구분한다.
[VD 그룹 전환 근거](docs/evidence/m7-vd-stream-group-offload.md)를 따른다.

아래는 선행 VD STREAM 구현·검증 이력이다.
소스415a1ce CI37159106124의5jobs/원시17개·STREAM20/Node40·VD24/S348을 감사했고,
GitOps2227a91의 실제 이미지·Ready/PVC·ArgoSynced·기존 파일10개/두PVC 보존도 확인했다.
이 배포 판정은 V31–V32이며 아래 b144의 CI 실패 원인이 해소됐다는 주장은 아니다.
ADR0055/V31–V32의 VD STREAM 제어·동시 실행 용량·권한·그룹/최종 처리 재시도를 연결했다.
PG219·새 VD5개를 포함한 STREAM/route53개·단위105·기존 실제 저장소40·계약5/MVC26을 통과했다.
화면 lint/type/build·기존42개와 수정한 새 VD 선택 PC/모바일2개도 통과했다. Pod/S3/브로커
receipt를 사용한 새 DB 시험과 실제 VD 스트리밍 종단 수용은 구분한다. 후속 실제 supervisor/
자식 Runner5개·전체 실제 저장소45개에서 TLS/S3·그룹 상태 복원·취소·결과28/37을 확인했다.
후속 실제 Kubernetes6개/VD14Pods·Node1Pod/S3결과15개·API 교체·자식 SIGKILL 복구·취소와
자원 정리도 PASS다(215558Z-8e800c29). 후속 VD 교체·Pod 유실 복구·최종 처리 복구도
실제 Kubernetes3개/VD10Pods/S3결과9개에서 통과했다(222247Z-bfb805ce).
교체 중 취소 보고의 실패 분류를 수정한 전체 PG220개도 PASS(222557Z-1fe8caf0)다.
소스b144c8b CI37157334661은 storage 실패로 images/gitops를 실행하지 않았다.
CI 실패 원인은 미확정이며 새 수정의 CI·이미지·배포와 Remote STREAM/VD 그룹 전환은 남는다.
실제 API/DB/Swagger PC·모바일10개와 로컬 V30→V32의 기존 Task9,371개 보존도 확인했다.
[VD 스트리밍 근거](docs/evidence/m7-vd-stream-execution.md)를 따른다.

ADR0054/V30은 BATCH 작업별 VD/REMOTE 혼합 배치를 추가했다. PG214·실제 저장소40·
단위105·UI42·계약5/MVC26·실API/DB/Swagger10·기존 Task9,359개 보존을 통과했다.
실제 Kubernetes VD→NODE→VD·API 교체·고정 S3와 기존 VD 회귀5개/결과8개도 PASS다.
후속 실제 Kubernetes↔Remote 최초 혼합3개/Node Pod3개/S3결과5개·API 교체·취소·계산1회도
통과했다(204108Z-330b498b). abf6bfd CI37151914101은5jobs/원시17개·PG214·VD5/S38·
STREAM11/Pod39/S324 PASS이며 GitOps720203b 실제 이미지/Ready·PVC·ArgoSynced와
기존10파일/두PVC 보존을 확인했다(211258Z-1187337c/211350Z-0ef63124).
Remote 혼합 게이트0d31eb8 CI37154396986은5jobs/원시17개·혼합3/S35·기존STREAM11 PASS다
(220028Z-1e19bb71). GitOps8b17e24 실제imageID/Ready/PVC/ArgoSynced(215910Z-ab971f5e)와
기존10파일/두PVC 보존(215911Z-fa4598d5)도 확인했다. 새 VD STREAM의 CI/배포 증거는 아니다.
[혼합 배치 근거](docs/evidence/m7-mixed-task-targets.md)를 따른다.
선행fe32ed8 CI37149032705의5jobs/원시17개·STREAM11/Pod39/S324와 GitOpsaabb815 실제
imageID·Ready/PVCBound/ArgoSynced·V29·기존10파일/두PVC 보존은 확인했다.

아래는 선행 AUTO/NODE 배치 검증 이력이다.
ADR0053/V29의 작업별 최초 AUTO/NODE 배치를 구현했다. 공개 API·불변 DB·대기 Task·
BATCH 하위·STREAM 그룹/최종 처리 복구·전환 후 재시도·Swagger/화면을 연결했다.
PG212·실제 저장소38·단위105·UI42·실API/DB/Swagger10개 및 실제 Kubernetes 전체11개/
Pod39개/S3결과24개 PASS다. 이 기록의 이미지/배포 판정은 해당 CI 근거를 따른다.
선행204645f CI37145780408의 자동 취소 driver 실패는 원인 미확정이다. 실패 위치를 다음 CI
artifact에 보존해 확인한다. [작업별 배치 근거](docs/evidence/m7-task-initial-placement.md).

아래는 구성 요소별 구현·검증 이력이다.
ADR0052/V28에서 공개 STREAM 자동 전환을 연결한다. 모든 구성원의 체크포인트·대기 시간·
전환 예산을 확인하고 선택 작업만 방문 노드를 제외한 AUTO로 옮긴다. Runner 측정도 스트리밍
계산부터 최종 처리까지 이어간다. 실제 PG206·Runner111·TLS MQTT90·서버8·UI40을 로컬
검증했다. Pod 신원/배치와 서비스 지연 입력은 해당 서버 시험의 fixture다. 후속 실제 Kubernetes
시험에서 모델 프로세스의 메모리 부하→자동 그룹 전환·API 교체·취소·상태/결과 보존·동료 전환 한도를
통과했다. 현재 JAR 전체9개/Runner Pod31개/S3결과18개 PASS(183338Z-5496a478).
게시된 API/Runner 이미지의 자동2개도 PASS(184641Z-a6fc3e70)다. CI37142931811의5jobs/
원시17개·기존7개 STREAM kind와 GitOpsf869da5 실제 이미지·V28·기존 데이터 보존을 확인했다.
새9개 CI 게이트가 다음이며 단계별 실행 배치·VD/REMOTE 스트림·외부 장치 수용은 남는다.
[자동 전환 근거](docs/evidence/m7-stream-automatic-offload.md).
ADR0051/V27의 공개 STREAM 그룹 노드 전환을 연결했다. 전체 종료/회수 장벽·고정 체크포인트,
같은 Task/새 OFFLOAD Attempt와 실제 독립 Runner의 상태 인계를 로컬 검증했다.
전체 PG197·실서버7·단위105·UI40 및 계약 검증을 통과했다. 실제 Kubernetes의 다른 노드 전환·
대기 중 API 재시작/재전송·취소·늦은 producer 차단도 검증했다. 기존 장애 복구를 포함한 전체7개,
실제 Runner Pod24개·고정 S3파일15개 PASS다. d3797d6 CI37137184323의5jobs/17JSON과
GitOps4263ecb 실제 이미지·V27 배포/기존 데이터 보존, 완성 이미지의 새 전환2개도 통과했다.
983ef6d의 CI37139978354도7개 기본 게이트·24Pods/S315·5jobs/17JSON을 통과했고,
GitOps2f11f3e의 정확한 이미지·Ready/PVC·ArgoSynced·기존 파일 보존을 확인했다.
상세는 [전환 근거](docs/evidence/m7-stream-group-offload.md)를 따른다.
ADR0047에서 공개 Run의 retry 정책을 그룹/최종 처리 복구에 연결했다. 공개 API·실서버6개·
PC/모바일과 실제 Kubernetes 그룹·최종 처리 장애 복구를 통과했다. 새 CI/배포를 확인한다.
[현재 검증 범위](docs/evidence/m7-public-stream-retry.md).
운영 연결을 위해 ADR0048에서 같은 API의 추가 HTTPS 포트를 구현·검증한다. 후속으로 영속 TLS
브로커/S3·런타임 공개 CA·개인 키 준비와 운영 다중 장치 데모를 연결한다.
ADR0049에서 영속 TLS broker·불변 신원/CA·복구6개·실제 Pod 교체를 검증했다. 선택 컴포넌트의
API/MinIO 활성화와 실제 운영 다중 장치 데모를 다음으로 진행한다. 새 기반 CI는 별도 게이트다.
ADR0050의 dev TLS 활성화·기존파일10개/두PVC 보존과 실제 운영 데모3개/Runner8개/S3결과6개를
통과했다. CI37134164748의 broker 준비 대기 결함을 수정한 뒤37137184323 전체 kind와
영속 TLS 기반·배포 데모가 통과했다. M7의 자동 전환/단계별 배치·외부 장치 수용으로 이어간다.
ADR0038/V24에서 서버의 경로 고정·실행 배정·Task/Device 공동 완료 허가를 연결했다.
새 실제 HTTP/PG와 SDK/MinIO 시험, 기존 DB·Result 회귀를 통과했다. ADR0039는 DeviceSource 완료
대기·응답 유실/재시작과 실제 Spring/PG/S3/TLS broker의 종료·Result 확정을 연결했다.
ADR0040은 허가 뒤 현재 Runner 재시작의 최종 상태 복구를 실제 Spring/S3/TLS와 연결했다.
ADR0041/V25에서 공개 Run의 Device 세션 고정·route 생성·그룹 동시 배정과 경로 조회 UI를 연결했다.
공개 실행은 별도 opt-in이며 운영 기본 STREAM501을 유지한다. 독립 다중 Runner 간 실제 데이터와
BATCH 결과·취소는 Spring/PG/S3/TLS MQTT에서 검증했다. ADR0042의 공개 CA 번들·fsGroup 수정 뒤
실제 Kubernetes TLS AUTO/NODE DAG·API 교체·취소·S3 결과를 통과했다. 후속 e93d9e1 CI·배포와
완성 API 이미지 자체의 같은 실제 클러스터 시험도 통과했다. 후속 c152d4d의 STREAM kind/CI 게이트도 통과했다.
그룹 인계 수용·운영 STREAM 배포와 다중 장치 데모는 남는다.
현재 검증 범위는 [Kubernetes 진행 기록](docs/evidence/m7-kubernetes-stream.md)을 따른다.
ADR0043은 장치 토큰으로 본인 Run 경로를 찾는 SDK/API를 실제 Spring·PG·TLS MQTT·S3·독립
Runner와 연결했다. metadata 조회로 권한이나 journal을 전환하지 않으며 다음은 전체 그룹의
안전한 재배정·checkpoint 인계와 장치의 명시적 재연결이다.
[장치 조회 근거](docs/evidence/m7-device-route-discovery.md)의 로컬 검증과 후속 이미지 검증을 구분한다.
ADR0044에서 계산 중 그룹의 동시 재시도 예약·전체 종료/회수 장벽·새 Attempt와 세대를 연결했다.
실제 두 Runner의 외부 상태9 복원·Device journal 인계·결과37을 검증했다. 완료 허가 뒤 복구와
Device 자동 재연결·공개 정책·Kubernetes 장애 수용은 [그룹 재시도 근거](docs/evidence/m7-group-retry.md)의 남은 범위를 따른다.
ADR0045/V26은 공동 완료 허가 뒤 실패한 Task만 새 Attempt로 재시도하고 원래 최종 상태에서
Result를 확정한다. 실제 PG·HTTP/SDK·Spring/S3/TLS·독립 Runner의 결과14를 검증했다.
범위·남은 MQTT 간헐 실패·공개 정책/운영 수용은 [최종 처리 인계 근거](docs/evidence/m7-finalizer-attempt-recovery.md)를 따른다.
ADR0046은 같은 DeviceRunSource가 새 세대를 자동 조회하고 LOCAL journal을 인계하도록 연결했다.
실제 서버·장치 SDK·독립 Runner 그룹 복구에서14/23/BATCH37을 검증했다. 공개 retry 연결과
실제 Kubernetes 그룹/최종 처리 장애 수용은 [장치 재연결 근거](docs/evidence/m7-device-reconnect.md)를 따른다.
새 검증 범위는 [공개 실행·그룹 배정](docs/evidence/m7-public-stream-runs.md)을 따른다.
복구 범위와 같은 Attempt/Pod 제한은 [최종 상태 복구](docs/evidence/m7-finalizer-recovery.md)를 따른다.
상세 검증 범위는 [서버 완료 처리](docs/evidence/m7-stream-execution-completion.md)를 따른다.
장치 SDK의 실제/fixture 경계는 [완료 대기 검증](docs/evidence/m7-device-source-completion.md)을 따른다.
M6 최종 판정과 한계는 [완료 감사](docs/evidence/m6-completion-audit.md)를 따른다.
아래는 단계별 검증 이력이다. 실행 규격·Job compiler·S3 adapter·독립 Runner의
구성 요소 구현과 CI·배포 시험을 완료했다. 이어 V5의 실행 상태·producer claim·명령 lease·결과 확정과
BATCH 해제·취소를 실제 PostgreSQL/MinIO로 시험했다. Kubernetes 생성/관측 worker·내부 인증/API를
연결했고 실제 클러스터의 scheduler·Pod TokenReview·UID 삭제를 대기 컨테이너로 검증했다.
로컬 실행은 기본 비활성이고 실제 배포는 실행 활성화·Result API/UI를 반영했다. Pending Pod 관측 지연
재시도 수정 뒤 실제 kind의 AUTO/NODE BATCH·재시작·artifact/producer fault와 기존 클러스터 종단
실행을 통과했다. CPU 부족·출력 누락·프로세스 실패의 추가 조건도 기존 클러스터에서 통과했고,
동일 조건의 CI36986090769도 통과하여 M4 완료를 판정했다.
M5는 ADR0006·V6의 재시도 예약/예산·새 Attempt/epoch를 실제 kind·CI·배포까지 검증했다.
ADR0007·V7의 실행 중 NODE 전환은 실제 kind·CI·배포 검증을 통과했다.
ADR0008·V8 실행 측정은 CI36996007482·실제 kind·배포까지 통과했다.
ADR0009·V9의 선택적 측정 기반 자동 전환 정책·판단 이력·AUTO 노드 제외는 CI36999672446의
실제 kind18Run 및 기존 클러스터의 source951c4bd 배포까지 검증했다.
ADR0010 Remote 참조 계약/HTTP adapter/영속 계산 시뮬레이터는 로컬 HTTP/TLS10개·실제 프로세스13개와
CI37003825328의 5 jobs/결과JSON15개 및 source0143094 실제 배포 검증을 통과했다.
ADR0011/V10–V11의 RemoteAllocation·producer fencing·결과 API/화면은 CI37008176219의
5 jobs/결과JSON15개와 source6009136 실제 배포 검증을 통과했다.
ADR0012/V12는 자동 Remote worker·직접 S3 전송·공개 Run/Offload REMOTE 선택·불변 제공자 binding을
연결했다. 단위60·PostgreSQL80·실제 S3/DB/provider14·UI26·실DB브라우저8·DB 장애/복구를 로컬 검증했다.
실제 Spring 스케줄러 BATCH, 제공자 프로세스 재시작·재시도·취소·변조·중복 처리도 포함한다.
초기 Kubernetes↔Remote 통합 시험의 Kubernetes 부분은 fixture였다. 후속45ce85f CI37016556197에서
실제 kind22Run(새Remote4개 포함)·S3 결과20개·API 교체/취소와 클러스터 삭제를 확인했다.
상태형 복원 및 실제 외부 계약 수용은 남는다. worker f6dc087의 CI37013658656은5 jobs/JSON15개와
기존 실제 kind18Run을 통과했고 GitOps f6c5a2d에 이미지 digest를 기록했다.
실제 imageID·Ready/PVCBound/ArgoSynced도 `20261002T135530Z-df0f3f31`에서 확인했다.
상세는 `docs/evidence/m5-remote-worker.md`다. M5 완료로 판정하지 않는다.
후속으로 독립 TLS Remote Pod/PVC와 실제 양방향 전환·API 교체/취소의 kind4개 Run을 추가했다.
TLS2·참조 제공자13·Runner14·실서버 manifest 검증과 새 실제 kind 종단을 통과했다.
CI37016556197은5jobs/JSON15개 PASS이며 GitOps861663f와 실제 배포 imageID/Ready/PVC/ArgoSynced도
확인했다. 상세와 kind 종료 직전 Dashboard Ready 진단 한계는 `docs/evidence/m5-remote-kind.md`를 따른다.
외부 Remote API·장비/모델·성능 합격 기준은 원문에서 미정이며
사용자에게 자료 위치를 요청했다. 독립 구현·시뮬레이터 계약 시험은 계속 진행하되 실제 외부
수용시험과 구분한다. LOCAL_VERIFIED와 FULL_ACCEPTANCE는 각각 전체 필수 증거를 요구한다.

## 완료: M0 초기화

1. 설계 출처·범위·미확정 사항을 로컬 docs로 정리한다.
2. Spring/Next.js/PostgreSQL health path, Flyway, OpenAPI, 실행 스크립트를 구현한다.
3. 단위·계약·실DB·브라우저·health 시험을 수행하고 evidence를 기록한다.
4. 공개 GitHub 저장소를 생성하고 커밋·푸시한 뒤 CI 결과를 확인한다.

위 항목과 DB 장애·복구 및 실제 MinIO S3 검증을 완료했다. 코드 b469f62의 CI 36834353000은 두 job 모두 success다.
요구사항별 증거는 `docs/evidence/m0-completion-audit.md`에서 확인한다.

## 완료: M1 Profile

계약·Flyway V2·순수 도메인/저장 adapter·HTTP·Dashboard 수직 슬라이스를 구현했다.
구체적 결정은 ADR 0002에 기록한다. 검증 결과는 PROGRESS와 M1 evidence를 따른다.
다음 수직 슬라이스는 ProfileVersion을 참조하는 M2 Device/Node/Observation이다.

## 후속 순서

M1 Profile → M2 Device/Node → M3 Workflow/Run/Task/Attempt → M4 PodSpec/Kubernetes/Runner/Result →
M5 Retry/Offload/Remote → M6 VD → M7 다중 장치 DAG/Streaming → M8 부하 → M9 운영/복구/보안 → M10 실장비.

M1부터 각 기능은 설계 → OpenAPI → Flyway → 구현 → unit/integration/contract → 가능한 kind → 증거 순서다.
첫 기능 목표는 Profile 등록부터 검증된 Result까지 연결하는 한 경로다.

## 위험과 재개

- Docker 접근: `bash scripts/preflight.sh compose`. 현재 권한 차단; 권한 있는 개발 환경에서 `dev-up.sh` 재실행.
- 외부 상세 계약: M1 registry 계약은 ADR 0002로 정합화. M2+ 상태 전이·외부 계약은 해당 단계에서 확정.
- MinIO: source build/live health/실제 S3 검증 완료. `bash scripts/dev-storage.sh`와 `bash scripts/test-storage.sh`로 재현한다.
- kind/실장비: 전용 context·namespace·소유 label을 준비한 뒤 해당 단계에서 구현. 기존 context를 변경하지 않는다.

## M4 실행 경로 구현 순서

M3가 저장한 요청을 실제 작업으로 연결한다. 아래는 구현 계획이며 검증 완료 기록이 아니다.
구성 요소별 확인 결과와 남은 연결 작업은 `docs/evidence/m4-runtime.md`를 따른다.

1. **실행 계약**: SERVICE spec의 digest 고정 이미지·entrypoint·입출력 포트·자원·arch/OS·timeout을
   정의하고, 기존 임의 JSON Profile은 변경하지 않은 채 소비 시 실행 가능성을 검증한다.
2. **PodSpec와 adapter**: AUTO 요구조건과 NODE hard affinity를 컴파일한다. nodeName을 쓰지 않고
   scheduler bind를 관측한다. 전용 runtime namespace·최소 RBAC·소유 labels로 실행 범위를 제한한다.
3. **영속 실행 상태**: RuntimeInstance·명령/outbox·claim/epoch를 새 migration으로 추가한다.
   결정적 Job 이름과 UID 대조, 요청 타임아웃 뒤 재조회, 재시작 후 조정으로 중복 생성을 막는다.
   목록 resourceVersion에서 watch를 시작하고 종료/410 뒤 재목록하며 주기적으로 상태를 조정한다.
4. **Runner와 결과**: Attempt 범위 인증, 입력 artifact 참조, 실제 실행, S3 업로드와 commit을 연결한다.
   저장소에서 object 크기·내용 SHA-256·version을 검증하고 현재 claim/epoch를 다시 확인한 뒤
   Result와 Task/Attempt 상태를 원자적으로 반영한다. Job Complete만으로 성공하지 않는다.
5. **BATCH와 취소**: 검증된 선행 결과만 하위 task의 입력으로 전달한다. 취소는 먼저 producer를
   차단하고 실제 Job/Pod 종료를 확인한다. 실패·누락 artifact·중복/늦은 commit을 실제 경로에서 검증한다.
6. **수용 증거**: Result API·UI·Swagger, 실제 MinIO, 격리 kind의 AUTO/NODE·불가능한 affinity·
   취소·API 재시작·잘못된 artifact·최소 BATCH DAG를 검증한다. 로컬 Docker 제한은 유지하며
   GitHub runner의 실제 kind 시험을 준비한다. 기존 demo-workflow/test-kind의 기준을 축소하지 않는다.

Kubernetes의 [노드 지정](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/),
[Job lifecycle](https://kubernetes.io/docs/concepts/workloads/controllers/job/),
[watch/relist](https://kubernetes.io/docs/reference/using-api/api-concepts/)를 확인했다.
S3 [무결성 계약](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html)에 따라
ETag 또는 사용자 제공 SHA metadata만을 실제 내용 검증으로 사용하지 않는다.


## M6 완료: VD 등록·원본 연결·실제 실행

2026-10-03 KST 최종 CI·배포·화면 검증을 통과했다. 아래 구성 요소별 기록의 후속 게이트는
ADR0020에서 연결하고 검증했다. [완료 감사](docs/evidence/m6-completion-audit.md).

ADR0013/V13의 영속 VD·불변 Profile 참조·원본 호환성·연결 이력·revision 수정·논리 해제와
장치 해제 보호를 구현했다. 공개5 API, 한국어 Swagger35개, `/virtual-devices` 관리 화면을 연결한다.
실제 PostgreSQL 동시 생성/수정/장치 해제 경합과 DB 제약, PC·모바일 실제 API 흐름을 검증한다.
상세 결과는 `docs/evidence/m6-vd-registry.md`다. CI37022079299의5 jobs/JSON15개 및
source0b4693c 실제 이미지·Ready/PVC/ArgoSynced까지 확인했다. 이 단계는 M6 전체 완료가 아니다.

이후 지속 runtime·source/runtime binding 분리, provision/readiness·교체/drain Operation,
Run VD 정책의 실제 활성 runtime Task 실행과 demo-vd를 구현했다. 등록 상태 REGISTERED를 Ready로 바꾸거나
Node ID만 복사한 별도 Job으로 실제 VD 실행 수용 게이트를 대신하지 않는다.

ADR0014의 지속 supervisor·순수 Pod compiler와 내부 poll 계약을 구성 요소로 추가했다.
실제 자식 Runner 작업·취소·lease·drain·강제 종료 후 자식 정리와 기존 Runner 회귀를 검증한다.
V14/ADR0015의 영속 VDRuntime/Operation·runtime binding·명령 lease와 registry 수정/해제 hook을
추가했다. 실제 DB 동시성·서비스 객체 재생성·생성 응답 지연·종료/lease/세대 fence를 검증한다.
증거는 `docs/evidence/m6-vd-runtime.md`, `docs/evidence/m6-vd-lifecycle.md`다.
ADR0016의 실제 Pod gateway/worker·VD HMAC 자격을 추가했다. Pod-bound TokenReview·AUTO/NODE
scheduler·Ready 관측·소유 관계·UID 삭제를 실제 Kubernetes에서 검증하며, 영속 명령 재시도와
종료 이력의 늦은 Pod 정리를 연결한다. 상세는 `docs/evidence/m6-vd-gateway.md`다.
VD 실행은 기본 비활성이며 공개 등록은 아직 runtime을 자동 기동하지 않는다.
ADR0017/V15는 인증된 poll 서버와 session/sequence 재전송 상태·lease/Ready·idle drain 및 감독
프로세스의 자체 교체 요청을 연결한다. 실제 Spring HTTP·PostgreSQL·Python 감독 프로세스의
준비/종료·503/lease 만료를 검증한다. Kubernetes 관측은 이 통합시험에서 fixture다.
ADR0018은 공개 provision/replace/drain·Operation/실행 상태 API와 UI를 연결했다. 실제 DB의
동시 요청·멱등성·준비/종료 이력과 UI 상태/재전송을 검증한다. 상세는 docs/evidence/m6-vd-public-execution.md다.
실제 Kubernetes Pod→supervisor→poll→Ready/교체/drain과 API Pod 재시작·시작 실패 정리는
20261002T182533Z-cfdb17d2에서 통과했다. docs/evidence/m6-vd-kubernetes.md에 범위·실패 근거를 기록한다.
새 demo-vd/kind 검사를 연결했고 CI37048443291의 실제 VD3개·기존 실행/S3 경로와 배포 검증도 통과했다.
이후 VD Run 배정·Task claim/Result·취소/실패와 M6 수용을 연결했다.
ADR0019/V16의 VD 대상·작업 배정·공급자/결과 FK와 domain/repository를 추가하고
실제 PostgreSQL의 용량·이력·신원·잠금 순서를 검증했다. 상세는 docs/evidence/m6-vd-task-persistence.md다.
후속 ADR0020/V17–V18에서 공개 VD Run·poll 배정·Runner 인증/Result 서비스를 연결했다.
배정·취소·완료 확인을 같은 VD→Run 잠금으로 처리하고 미시작 종료는 후속 순번의 증명을 요구한다.
실제 PG136개·단위82개·Runner28개·실제 S3/DB16개·실API/DB 브라우저10개 및 VD UI2개를 통과했다.
실제 Kubernetes의 VD 수명3개와 Task/DAG·API 재생성·활성 교체·개별 취소·재시도·S3 파일5개도
20261002T195417Z-ea2a6d9b에서 통과했다. 후속 CI37059110890의5 jobs/실제kind와
c2862a7 배포·실제 VD 데모·PC/모바일 결과 화면도 모두 통과했다.
상세와 fixture/실제 경계는 `docs/evidence/m6-vd-task-execution.md`를 따른다.

## M7 진행: 실제 데이터 전달 구성 요소

ADR0021의 DATA/END/처리 확인 codec과 ADR0022의 SQLite 입력/계산 상태/출력 journal,
MQTT5 adapter를 구현했다. 같은 볼륨의 프로세스 강제 종료 복구·경로별 용량 예약·중복/순번
검사와 실제 broker의 두 합성 장치→join→sink·강제 종료/재연결·ACL·TLS를 검증한다.
상세 상태는 `docs/evidence/m7-stream-transport.md`를 따른다.
ADR0023/V19 DataRoute 영속 세대·내부 제어 상태는 실제 PostgreSQL12개/전체148개 검증을 통과했다.
상세는 `docs/evidence/m7-stream-routes.md`다.
ADR0024 broker 권한 발급/회수 adapter는 실제 TLS broker·DB 세대 전환8개를 통과했다.
ADR0025/V20 DB 권한 worker는 실제 스케줄러·DB/TLS broker14개 로컬 시험을 통과했다.
ADR0026 인증 배정은 Device 세션 토큰과 기존 Runner/Pod 인증을 연결해 실제 HTTP/DB/TLS broker로
로컬 검증했다. SDK의 lease 준수/갱신과 실제 Pod의 스트림 수용은 남았다.
worker cd61529의 CI37077442217·실제kind와 GitOps2410f10 배포 검증도 통과했다.
ADR0027 SDK의 배정 검증·monotonic lease·MQTT/journal guard와 인증 배정337abb3은 각각
CI37082953978·CI37080508316의5 jobs/JSON17개·실제kind를 통과했다. source76651cd의 실제
배포는 GitOps9171827·011108Z-5d82c84b에서 imageID/Ready/PVC/ArgoSynced를 확인했다.
ADR0028/V21의 양쪽 heartbeat·순번 재전송/동시성·SDK live refresh와 실제 Spring→Python→TLS MQTT
연결은 로컬 broker22개·PG153개·Runner60개·MQTT14개·계약/Swagger4개 검증을 통과했다.
상세는 [heartbeat 검증](docs/evidence/m7-stream-heartbeat.md)이다. heartbeat77b687a도
CI37085573042의5 jobs/JSON17개·실제kind와 GitOps19d7cec의 정확한 이미지 배포 검증을 통과했다.
ADR0029의 지속 계산 프로세스·watchdog·journal 연결은 Runner69개·실제TLS MQTT26개·
Spring/DB/broker22개를 통과했다. [계산 검증](docs/evidence/m7-stream-workload.md)의 범위를 따른다.
계산cf4876c의 CI37088407643 5 jobs/JSON17개·실제kind와 GitOps7fad529의 정확한 이미지 배포도 확인했다.
다음은 운영 broker·Runner/SERVICE 실행 인터페이스,
S3 checkpoint/새 Pod 복원·공개 실행/Swagger/UI·실제 Kubernetes 다중 장치 데모다.
ADR0030 자동 Session은 실제 인증 배정·heartbeat 재개/재시도와 계산 수명을 묶었다.
Runner70개·실제 HTTPS/MQTT35개·Spring/DB/broker22개를 로컬 검증했다.
[세션 검증](docs/evidence/m7-stream-session.md)의 공개 실행·외부 checkpoint 경계를 유지한다.
현재 공개 STREAM 요청501은 유지하며 이 구성 요소 시험으로 전체 M7 완료를 판정하지 않는다.

ADR0031의 portable checkpoint·별도 snapshot serial·외부 확인 전 ACK/출력 제한과 새 볼륨의
동일 binding 복원을 구현했다. 실제 TLS MQTT/Session의9→14 재개, 실제 MinIO 고정 version,
원본 볼륨 삭제·손상된 최신 version 거절·확정 중 SIGKILL을 검증했다.
[체크포인트 증거](docs/evidence/m7-stream-checkpoint.md)를 따른다. 다음은 현재 producer
재검사를 포함한 인증 API/영속 metadata, 새 Attempt/generation handover와 자동 저장 연결이다.

ADR0032/V22는 인증된 uploads/commit/latest와 불변 checkpoint metadata를 연결한다.
실제 HTTP/SDK/PG/S3 6개에서 동시 확정·정상 serial 갱신·변조·취소 경합을 검증했고
OpenAPI/단위88개·기존 PostgreSQL·S3/Remote/VD 회귀를 통과했다.
[서버 확정 증거](docs/evidence/m7-stream-checkpoint-api.md)를 따른다. 다음은 Session 자동
업로드/인증된 receipt 적용·새 Attempt/세대 인계와 SERVICE/Runner 운영 실행 연결이다.

ADR0033은 Session에 단계별 인증 checkpoint publisher를 연결했다. 실제 HTTP/SQLite8개,
Runner87개·HTTPS/MQTT39개·실제 Spring/PG/S3/Python publisher7개를 통과했다.
[자동 저장 증거](docs/evidence/m7-stream-checkpoint-publisher.md)를 따른다. 다음은 현재 기한·취소
보호를 유지한 새 Attempt/세대 인계와 SERVICE/Runner·운영 broker·공개 STREAM 종단이다.

ADR0034는 인증 latest·고정 S3 다운로드·권한/이력 재검사와 명시적 Session 새 볼륨 복원을
연결했다. 실제 TLS MQTT에서9→14 계산 재개·출력/END 중복 방지와 실제 Spring/PG/MinIO의
SDK 복원을 검증했다. [복원 증거](docs/evidence/m7-stream-checkpoint-recovery.md)를 따른다.
동일 Attempt/세대 범위이며 새 Attempt 인계와 공개 실행의 전체 수용은 계속 남는다.

ADR0035/V23은 서버가 최신 확정본을 새 Attempt·경로 세대로 인계하고 SDK가 새 볼륨으로
복원하는 경로를 연결했다. 실제 Spring/PG/S3에서 이전 종료·권한 회수 조건, 동시 인계,
취소 경합·DB 불변 제약과 독립 계산 프로세스의9→14 재개를 검증했다.
[인계 증거](docs/evidence/m7-stream-checkpoint-handover.md)를 따른다. Device/인접 Task의
journal 전환, SERVICE/Runner·공개 STREAM·실제 Kubernetes 다중 장치 수용은 남는다.

ADR0036의 DeviceSource는 같은 Device Session의 송신·adapter 상태·자동 heartbeat와
명시적 LOCAL journal 경로 인계를 연결한다. 실제 Spring/PG/권한 worker/TLS broker에서
옛 ACL 회수·새 경로 재전송·독립 계산9→14·END/ACK를 검증했다.
[Device source 증거](docs/evidence/m7-device-source-handover.md)를 따른다. Task/인접 Task의
인계 orchestration·SERVICE/Runner·공개 STREAM·Kubernetes 다중 장치 수용은 이어서 진행한다.

ADR0037은 SERVICE 지속 계산·최종 파일 생성 계약과 Runner 실행 owner를 연결한다.
실제 Runner/model·HTTPS/TLS MQTT의 결과14, 볼륨 손실 후9→14 복원과 잘못된 허가·실패를 검증했다.
취소 중 signal 잠금 재진입을 실제로 재현하고 Runner/VD를 수정했다.
[실행 연결 증거](docs/evidence/m7-service-stream-runner.md)를 따른다. 다음은 서버의 Device 입력/
live port 배정, STREAM 구성 요소 Task 동시 시작·영속 완료 장벽, peer 인계와 운영 TLS 설정,
공개 API/UI·실제 Kubernetes 다중 장치 수용이다. 공개 STREAM501과 M5 잔여/M7–M10 범위를 유지한다.
