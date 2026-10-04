# 검증 기준

| ID | 명령 | 수용 기준 | 범위 |
|---|---|---|---|
| M0-ENV | scripts/preflight.sh local | 도구 존재·버전 확인 | local |
| M0-UNIT | scripts/test-unit.sh | 비인증 401, 인증 metadata 계약 | local |
| M0-CONTRACT | scripts/test-contract.sh | OpenAPI 타입 생성 일치·API 검증 | local |
| M0-DB | scripts/test-integration.sh | 실제 PostgreSQL의 Flyway 성공 | DB 필요 |
| M0-UI | scripts/test-ui.sh | lint/typecheck/build + desktop/mobile 표시 | local browser |
| M0-HEALTH | scripts/test-health.sh | DB→API→UI UP, API 인증 401/200 | 실행 중인 서비스 |
| M0-HEALTH-RECOVERY | scripts/test-health-stack.sh compose (로컬 PG 대안: local) | DB 중지 시 API/UI 503, DB 재시작 시 같은 앱 프로세스 UP | 프로젝트 전용 DB |
| M0-INFRA | scripts/test-infra.sh | PG ready, MQTT 실제 pub/sub | Docker 필요 |
| M0-STORAGE | scripts/dev-storage.sh + scripts/test-storage.sh | 공식 MinIO source build, 실제 S3 PUT/stat/GET byte 일치·SHA-256 metadata·비인증 403 | storage profile |
| M1-UNIT | scripts/test-unit.sh | JSON 정규화·숫자 정밀도·중복 필드·크기/깊이/Unicode·인증/CSRF | local |
| M1-CONTRACT | scripts/test-contract.sh | 생성 타입 일치, HTTP 오류/인증 계약 | local |
| M1-DB | scripts/test-integration.sh | 3종 CRUD 중 생성/조회, 동시 재등록/충돌, 불변 trigger·UNIQUE, paging/filter | 실제 PostgreSQL |
| M1-UI | scripts/test-profiles-stack.sh local 또는 compose | 실제 DB/API와 desktop/mobile 등록·재등록·409·새 버전·상세·인증·정밀도 | 프로젝트 전용 DB, 빌드된 UI |
| SWAGGER-CONTRACT | scripts/test-contract.sh | 문서/자산 인증401, 렌더 자산, packaged YAML과 원본 byte 일치 | local |
| SWAGGER-UI | scripts/test-profiles-stack.sh | desktop/mobile 실제 Swagger 렌더·자동 CSRF POST201·정확한 계약·외부 요청 없음 | 실제 DB/API/browser |
| M2-UNIT | scripts/test-unit.sh | 60초 신선도, 입력 제한, 모든 쓰기 CSRF, Node pagination·실패·HTTP proxy | local |
| M2-DB | scripts/test-integration.sh | 재등록/충돌, 동시 생성·재접속·보고, session fence, FK·활성 UNIQUE·attachment 이력 | 실제 PostgreSQL |
| M2-UI | scripts/test-profiles-stack.sh local 또는 compose | PC·모바일 등록/수정/보고/재접속/해제, 이전 session 409, 큰 숫자 보존, DB 장애·복구 | 실제 DB/API/browser |
| M2-NODE | scripts/test-node-inventory.sh <명시적-context> | 실제 Node UID·메타데이터 대조 및 Ready Node에 합성 장치 연결/중복/해제 | 읽기 가능한 실제 Kubernetes, 로컬 DB, 빌드된 UI |
| M2-DEPLOY | scripts/smoke-deployment.py --through device | 배포 HTTP 경유 장치 lifecycle, CSRF, 201/200/409, Node 조회 | 실행 중인 API/UI·환경 변수 인증 |
| M3-UNIT | scripts/test-unit.sh | DAG cycle/self/reference/port 검증, JSON 정밀도, 인증·CSRF·Idempotency-Key 필수 | local |
| M3-DB | scripts/test-integration.sh | seal·FK·활성 Attempt UNIQUE, 동시 발행/실행 1개, Run/Task 취소 경쟁·독립 분기·재전송 | 실제 PostgreSQL |
| M3-UI | scripts/test-profiles-stack.sh local 또는 compose | DAG 발행/재발행/409, Run 생성/재전송/새 키, Attempt·취소 전파, PC·모바일·DB 장애 복구 | 실제 DB/API/browser |
| M3-DEPLOY | scripts/smoke-deployment.py | 이미지 경유 불변 DAG·Run/Task/Attempt·취소·재전송, 큰 숫자·인증/CSRF | 실행 중인 API/UI |
| M4-SPEC | scripts/test-unit.sh | 실행 규격·자원·QoS·AUTO/NODE affinity·Pod 보안 설정 | 외부 서비스 없는 compiler 시험 |
| M4-STORAGE | scripts/test-runtime-storage.sh | 실제 byte SHA-256·길이·형식·version 검증, 변조 업로드 거절, 고정 버전 다운로드 | 실제 MinIO |
| M4-STATE | scripts/test-integration.sh | 실행 계획·명령 lease·동시 producer claim·결과 멱등성·취소 경쟁·BATCH 해제·DB rollback | 실제 PostgreSQL; Pod/저장소 응답은 명시적 fixture |
| M4-RESULT | scripts/test-runtime-results.sh | 실제 S3 byte 검증→봉인된 DB Result→하위 입력, 위조 metadata·취소 중 결과 거절 | 실제 PostgreSQL + MinIO; Pod 신원은 fixture |
| M4-RUNNER | scripts/test-runner.sh | 실제 workload, 입력/출력 검증, commit 재전송, timeout·취소·claim 거절 | Python + HTTP fixture |
| M4-HTTP | scripts/test-integration.sh | 실제 내부 인증 체인·producer fence·본문 제한·원자적 하위 실행 계획 | 실제 PostgreSQL; K8/S3는 명시적 fixture |
| M4-WORKER | scripts/test-integration.sh | 응답 유실·새 worker·취소 종료 확인·늦은 Job·누락 결과·watch 만료·deadline | 실제 PostgreSQL; K8 응답은 fixture |
| M4-K8-GATEWAY | scripts/test-runtime-kubernetes.sh <명시적-context> | 실제 제한된 SA/TLS·AUTO/NODE scheduler·Pod TokenReview·watch·UID 삭제 | 소유 namespace/RBAC 필요; 대기 컨테이너이며 Runner 전체 경로와 구분 |
| M4-RUNNER-IMAGE | EDGEAI_RUNNER_IMAGE=<image> scripts/test-runner.sh | 같은 프로토콜 시험을 비루트·읽기 전용 컨테이너로 수행 | Linux Docker; CI runner job |
| M4-KIND | scripts/test-kind.sh | 실제 AUTO/NODE→Job→Runner→S3→Result→BATCH, API 재시작·artifact/producer fault·취소 | Linux amd64 Docker, 전용 폐기 kind; 성공 증거는 M4 evidence 확인 |
| M4-DEPLOY | scripts/demo-workflow.sh <명시적-context> | 실제 BATCH·파일 계산값/체크섬·실행 취소·affinity/CPU 부족·출력 누락·하위 SKIPPED | 실행 활성화된 API/K8/MinIO; 합성 workload |
| M4-RESULT-UI | 실제 Result의 배포 화면 조회 | 실제 API의 Result ID/파일 크기/SHA/version과 PC·모바일 표시 대조, overflow 없음 | 실제 배포·브라우저; 전체 DAG 성공과 별개 |
| M5-REMOTE-COMPONENT | scripts/test-remote.sh | 참조 HTTP/TLS·실제 프로세스·파일·취소/restart·신원 검증 | 로컬 Python/OpenSSL/JDK; 외부 업체 API가 아님 |
| M5-REMOTE-WORKER | scripts/test-runtime-results.sh | 공개 HTTP·자동 worker·고정 S3·provider binding·취소/재시도·단일 결과 | 실제 PostgreSQL/MinIO/참조 제공자; Kube 전환 부분은 fixture |
| M5-REMOTE-KIND | scripts/test-kind.sh | 독립 HTTPS Remote·실제 NODE↔REMOTE BATCH·고정 version·API 교체/취소·종료 확인 | 추가 게이트 구현, 통과 여부는 m5-remote-kind.md 확인 |
| M6-VD-TASK-STATE | scripts/test-integration.sh | poll/slot·재전송·세대/session·취소·종료·retry·미시작 증명·공개 VD Run | 실제 PostgreSQL, Pod/S3 receipt fixture |
| M6-VD-TASK-STORAGE | scripts/test-runtime-results.sh | 실제 supervisor/child·응답 유실·DAG·고정 S3·취소/형제 작업 보존 | 실제 HTTP/PG/MinIO, Pod 신원 fixture |
| M6-VD-KUBERNETES | python3 scripts/test-vd-kubernetes.py --context <명시적-context> | 실제 VD 수명·Task·API 재생성·교체·취소·재시도·S35개·자원 정리 | 격리 API/DB/MinIO, 현재 JAR; m6-vd-task-execution.md |
| M6-VD-KIND | scripts/test-kind.sh | 빌드된 실제 이미지로 VD 수명·Task·S3·API 재시작 | 새 게이트 CI 판정은 evidence 확인 |
| M6-VD-DEMO | scripts/demo-vd.sh <명시적-context> | 지정한 배포 API의 VD 수명·Task·교체·취소·retry·S3 | API/저장소 환경 설정 필요, API 재시작 제외 |
| M7-FINALIZER-RETRY | scripts/test-integration.sh + scripts/test-runtime-results.sh + scripts/test-stream.sh | 종료/회수 장벽·단독/반복 재시도·원본 허가 보존·위조 인계/취소 거절·새 Attempt Result14 | 실제 공개 MVC/PG/Spring/S3/TLS/Runner; Pod fixture; m7-public-stream-retry.md |
| M7-DEVICE-RECONNECT | scripts/test-stream.sh + scripts/test-runtime-results.sh | 같은 Device owner의 경로 재조회·종료/인계·미확인 샘플/센서 커서 보존·SIGKILL/취소/세션 교체·실제 그룹9→14/23→BATCH37 | 실제 TLS MQTT/HTTPS/SQLite 및 공개 MVC/Spring/PG/S3/Runner; Pod fixture; m7-device-reconnect.md 및 m7-public-stream-retry.md |
| M7-GROUP-RETRY | scripts/test-integration.sh + scripts/test-runtime-results.sh | 전체 종료/회수 장벽·동시 retry·취소/기한·새 Attempt2개·외부 상태9→14/23→BATCH37·Device journal 인계 | 실제 공개 MVC/PG/HTTPS/MQTT/S3/Runner; Pod 경계 fixture; m7-public-stream-retry.md |
| M7-GROUP-OFFLOAD | scripts/test-integration.sh + scripts/test-runtime-results.sh + scripts/test-ui.sh | 공개 NODE 전환·전체 종료/회수·멱등/취소/기한·peer 배치 보존·checkpoint 인계·같은 Device 자동 연결·결과37 | 실제 PG/HTTP/TLS MQTT/S3/Runner·PC/모바일; Pod/노드 경계 fixture, 실제 Kubernetes 전환은 후속; m7-stream-group-offload.md |
| M7-AUTOMATIC-STREAM | scripts/test-integration.sh + scripts/test-runtime-results.sh + scripts/test-stream.sh + scripts/test-ui.sh | 공개 opt-in·그룹 전체 checkpoint/대기/예산·동시 판단/취소·DB 위조 거절·실제 Runner 측정→새 Attempt 상태9→14/23/BATCH37 | PG206·서버8·Runner111/TLS MQTT90·UI40 로컬 PASS; 해당 서버 시험의 Pod/노드·지연 입력은 fixture, 실제 자원/Kubernetes 검증은 M7-KUBERNETES-AUTOMATIC; m7-stream-automatic-offload.md |
| M7-TASK-PLACEMENT | scripts/test-integration.sh + scripts/test-ui.sh + scripts/test-profiles-stack.sh | Run 기본값/작업별 AUTO·NODE·정규화·동시 생성·불변 DB·BATCH/STREAM 해제·복구·offload 후 위치·Swagger | PG212·UI42·실제 API/DB/Swagger10 PASS; Pod/S3 receipt/broker 경계 fixture, m7-task-initial-placement.md |
| M7-MIXED-TARGETS | scripts/test-integration.sh + scripts/test-runtime-results.sh + scripts/test-ui.sh + scripts/test-profiles-stack.sh | 작업별 VD/REMOTE·서로 다른 SERVICE·대기 최초 대상·VD poll/결과/취소 경합·실제 Remote 양방향 BATCH·Swagger | PG214·저장소40·UI42·실API/DB/Swagger10 PASS; Remote 시험의 Kubernetes 경계 fixture, m7-mixed-task-targets.md |
| M7-KUBERNETES-MIXED-VD | scripts/test-vd-kubernetes.py --context <명시적-context> --mixed | 서로 다른 VD→NODE→VD·실제 Pod/Node 생산자·API 교체·고정 S3·소유 자원 정리 | 현재 JAR 및 abf6bfd CI37151914101의 VD5/S38, GitOps720203b 실제 배포/기존 데이터 보존 PASS; m7-mixed-task-targets.md |
| M7-VD-STREAM-OFFLOAD | scripts/test-integration.sh + scripts/test-runtime-results.sh + scripts/test-ui.sh + scripts/test-profiles-stack.sh | VD STREAM 수동 NODE 이동·동료 VD 유지·전체 종료/회수 장벽·마지막 VD claim·고정 상태 인계·취소/다른 Run 보존·Swagger | PG223·저장소47·단위105·UI46·실API/Swagger10·Task9,371개 V33 보존 PASS; m7-vd-stream-group-offload.md |
| M7-VD-STREAM-OFFLOAD-KUBERNETES | test-stream-kubernetes.py --context <명시적-context> --cases vd-shared-offload vd-distinct-offload vd-shared-offload-cancel vd-shared-offload-pending-cancel | 같은/다른 VD→다른 Node·peer VD·S3 상태 인계·대기 중 API 교체/취소·완료 뒤 취소 | 첫3개230420Z-53281045 PASS/Node3Pods·VD7Pods/S36개; 후속2개232628Z-8d401390 PASS/Node1·VD5/S33. 격리 DB NO KEY UPDATE·시험 pool20, m7-vd-stream-group-offload.md |
| M7-VD-STREAM-CONTROL | scripts/test-integration.sh + scripts/test-ui.sh + scripts/test-contract.sh | 기본/작업별 VD STREAM·SERVICE·그룹 용량 거절·peer 잠금 없음·같은 Pod의 토큰 분리·그룹/최종 처리 재시도·취소 | PG219·STREAM/route53·단위105·계약5/MVC26·UI 기존42+신규2 PASS; Pod/S3/broker receipt는 fixture, m7-vd-stream-execution.md |
| M7-VD-STREAM-RUNTIME | runtimeArtifactIntegrationTest / StreamSourceCompletionIntegrationTest | 같은/다른 VD 실제 supervisor·자식 Runner·TLS/S3·고정 상태 인계·취소·공동 완료·BATCH28/37 | 로컬 실제5개/전체 저장소45개 PASS; Kubernetes 제출·Pod 신원은 fixture. b144c8b CI의 공유 VD 재시도 실패 원인은 미확정 |
| M7-VD-STREAM-KUBERNETES | test-stream-kubernetes.py --context <명시적-context> --cases vd-shared vd-distinct vd-mixed vd-shared-recover vd-distinct-recover vd-shared-cancel | 실제 VD14Pods/Node1Pod·TLS·API 교체·자식 SIGKILL 복구·취소·S3결과15개·소유 자원 정리 | 215558Z-8e800c29 PASS; 현재 JAR 사용. 아래 추가3개와 CI 기본20개로 연결; 새 이미지/배포는 후속 |
| M7-VD-STREAM-POD-FINALIZER | test-stream-kubernetes.py --context <명시적-context> --cases vd-shared-replace vd-distinct-pod-recover vd-finalizer | 공개 교체·Pod 유실 복원·이전 supervisor 종료 뒤 다음 세대·상태 인계·원본 완료 허가/체크포인트 보존·최종 처리만 재시도 | 222247Z-bfb805ce PASS3개/VD10Pods/S3결과9개·잔여 자원0; drain 실패 분류 수정 후 PG220도 PASS222557Z-1fe8caf0, 새 CI/배포는 별도 |
| M7-KUBERNETES-MIXED-REMOTE | scripts/test-vd-kubernetes.py --context <명시적-context> --mixed-remote | NODE→REMOTE→NODE·REMOTE→AUTO·Remote 취소·대기 대상 고정·실제 TLS 제공자 계산1회·API 교체·고정 S3입력/결과·실제 Pod 생산자 | 현재 JAR 실제3개/Node Pod3개/S35 PASS; 새 kind 게이트·이미지/배포는 후속, m7-mixed-task-targets.md |
| M7-KUBERNETES-PLACEMENT | scripts/test-stream-kubernetes.sh <명시적-context> | 다른 최초 노드·그룹 Job 유실/새 Attempt·동일 Device 재연결·BATCH 지정 노드·고정 S3·정리 | fe32ed8 CI37149032705의 실제11개/Pod39개/S324·GitOpsaabb815 배포/기존 데이터 보존 PASS, m7-task-initial-placement.md |
| M7-KUBERNETES-AUTOMATIC | scripts/test-stream-kubernetes.sh <명시적-context> | 실제 모델 메모리/cgroup 측정→그룹 자동 전환·수신/판단 표본 일치·다른 노드/peer 유지·API 교체·checkpoint/결과·peer 재부하/전환 한도·취소 | 현재638d77d JAR 전체9개/Pod31개/S318 및 게시된API 자동2개/Pod7개/S33 PASS; CI37142931811 기존7개·GitOpsf869da5 배포/데이터 보존 PASS; 새9개 CI 후속, m7-stream-automatic-offload.md |
| M7-KUBERNETES-OFFLOAD | scripts/test-stream-kubernetes.sh <명시적-context> | 실제 다른 Node·peer 배치 유지·전체 종료/회수·고정 checkpoint2개 인계·대기 중 API 교체/재전송·늦은 producer 차단·새 Attempt 전 취소 | 현재 JAR 전체7개/Pod24개/S315와 게시된d3797d6 API의 새2개 PASS; 983ef6d CI37139978354의7개/24Pods/S315와 실제GitOps2f11f3e 배포 PASS, m7-stream-group-offload.md |
| M7-PUBLIC-RETRY | scripts/test-integration.sh + scripts/test-ui.sh | retry 정책 검증·순서 정규화·중복/충돌·PC/모바일 정책 입력과 재전송 | 공개 MVC/실제 PG23개, UI HTTP fixture38개; m7-public-stream-retry.md |
| M7-KUBERNETES-RETRY | scripts/test-stream-kubernetes.sh <명시적-context> | 공개 retry·실제 Job 유실·이전 Pod/권한 회수·새 Attempt2개 상태9·동일 Device 자동 재연결·결과37 | 수정 JAR+CI-tested Runner 실제 클러스터4개 시나리오/Pod13개/S3파일9개 PASS; 새 API 이미지 및 최종 처리 장애와 구분, m7-public-stream-retry.md |
| M7-KUBERNETES-FINALIZER | scripts/test-stream-kubernetes.sh <명시적-context> | 허가 뒤 실제 Job 유실·단독 새 Pod/Attempt·원본 grant/checkpoint·peer Result 보존·계산 재실행 없음 | 현재 JAR+CI-tested Runner 전체5개 시나리오/Pod17개/S3파일12개 PASS; 새 API 이미지와 구분, m7-finalizer-kubernetes.md |
| M7-API-TLS | scripts/test-unit.sh + scripts/test-integration.sh + scripts/test-stream-kubernetes.sh <context> | 기본 비활성·설정 오류·실제 PEM 시작 실패·HTTP/HTTPS 인증·CSRF·신뢰/hostname·추가 HTTPS의 스트림/복구 | 단위105·PG190·실제 K8 5개/Pod17개/S3파일12개 PASS; 운영 활성화와 구분, m7-native-api-tls.md |
| M7-PERSISTENT-TLS | scripts/test-stream-bootstrap.py --context <context> + scripts/test-stream-platform-broker.py --context <context> | 실제 불변 신원 재실행/복구6개·인증/CA 거절·실제 새 broker Pod의 동일PVC/역할 유지 | 실제클러스터·d3797d6 CI37137184323 새 kind 게이트 PASS; m7-persistent-stream-platform.md |
| M7-DEPLOYED-DEMO | scripts/demo-multidevice.sh <context> + scripts/test-stream-minio-tls.py --context <context> | 실제 배포의 API/DB/TLS MQTT/MinIO·AUTO/NODE/cancel·체크포인트·Pod/producer·S3 고정버전 | 실제 배포3개/8Pods/6S3·기존10파일/두PVC 보존, d3797d6 CI37137184323 전체 kind PASS; m7-deployed-multidevice-demo.md |
| M7-DEVICE-DISCOVERY | scripts/test-integration.sh + scripts/test-runner.sh | Device 토큰·불변 세션 고정·다른 Run404·교체401/409·fanout 페이지·비밀 필드 거절·lease 불변 | 실제 PostgreSQL/MVC·실제 SDK/HTTP; m7-device-route-discovery.md |
| M7-DEVICE-DISCOVERY-DAG | scripts/test-runtime-results.sh | 실제 HTTPS Device 조회의 route/generation으로 송신·독립 Runner2개·BATCH 결과37·취소 | 실제 Spring/PG/TLS MQTT/S3/SDK; Pod 생성/신원은 fixture |
| M7-PUBLIC-RUN | scripts/test-integration.sh | 공개 Run·정규화/멱등·Device pin·그룹 동시 배정·BATCH 장벽·NODE·실패/취소·불변 DB·경로 조회 | 실제 PostgreSQL/MVC; Pod/S3 receipt fixture |
| M7-PUBLIC-SOURCE | scripts/test-runtime-results.sh | 공개 Run MVC→자동 worker→실제 TLS/S3/MQTT/DeviceSource·checkpoint·Runner 최종 결과·취소 | 실제 PG/MinIO/Mosquitto/SDK; Pod 생성/신원·peer 회수 시작은 fixture |
| M7-STREAM-DAG | scripts/test-runtime-results.sh | 두 Device→독립 STREAM Runner2개→고정 S3→BATCH Runner·기대값37·중간 취소/하위 미배정 | 실제 TLS HTTP/PG/S3/MQTT/모델/Runner; Pod 생성·신원·종료 관측은 fixture, m7-stream-dag.md |
| M7-KUBERNETES | scripts/test-stream-kubernetes.sh <명시적-context> | 실제 AUTO/NODE 다중 STREAM→BATCH·API Pod 교체·취소·고정 S3 결과6개·소유 자원 정리 | 실제 scheduler/TokenReview/TLS API·S3·broker/Runner; 현재 JAR 및 완성 API digest 모드 통과, m7-kubernetes-stream.md |
| M7-KIND | scripts/test-kind.sh | 위 STREAM 시나리오를 빌드된 API 이미지와 CI-tested Runner/MinIO digest로 수행 | c3b7122 CI37162109091의24개/Node43·VD33Pods/S354·VD 그룹 전환/복구/취소, VD5/S38·혼합 Remote3/S35 PASS; m7-vd-stream-group-offload.md |
| M7-PUBLIC-UI | scripts/test-ui.sh + scripts/test-profiles-stack.sh | PC/모바일 입력 재전송·오류/경로/페이지/출처, 실제 BATCH 빈 경로 조회와 Swagger41개 | STREAM 화면은 명시적 HTTP fixture, 실제 API 시험은 기본 비활성 |
| M5/M9-FAULT | scripts/test-fault.sh | 실패·취소·복구 | NOT_IMPLEMENTED |
| M8-LOAD | scripts/test-load.sh | 실제 API/PG의100→300→1,000 장치·고정 발송·지연/누락·DB정합성·재접속·자원/정리 | --measure-only는 측정 범위; 합의 성능 예산은 별도, docs/load-testing.md |
| M8-LOAD-ACCEPTANCE | scripts/test-load-acceptance.sh | 실제 API/PG의 측정 전용·예산 미정·초과·소규모 통과를 구분하고 종료 코드/정리 확인 | 10대/2초 회귀; 전체 규모·장비 성능 수용과 구분 |
| M8-AUTH-CACHE | scripts/test-unit.sh + scripts/test-load.sh --measure-only | 성공 비교64개/30초·고정 만료·hash/비밀번호/권한/잠금 변경·실HTTP401/403·동일 규모 지연/CPU 측정 | 단위111개·실제9,240요청/오류0·DB정합성 PASS; docs/evidence/m8-authentication-load.md |
| M10-HW | scripts/test-hardware.sh | KubeEdge/장비/2세부/성능 | NOT_IMPLEMENTED |

`SCAFFOLD_VERIFIED`는 M0 일부 시험에 한정한다. `LOCAL_VERIFIED`는 kind/UI/fault 등 필수
플랫폼 시험까지, `FULL_ACCEPTANCE`는 실장비 증거까지 충족해야 하며 현재 둘 다 해당하지 않는다.
미구현·외부 차단은 `BLOCKED/PARTIAL`로 기록한다. 테스트 이름이 존재한다고 구현된 것은 아니다.
