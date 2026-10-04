# Kubernetes 확정 Result 기록·영속 발행 검증

2026-10-05. [ADR0103](../adr/0103-kubernetes-result-journal.md)의 구현과 검증이다.
복원 DB에서 이 기록을 소비하는 경로와 종합 재가동은 아직 완료하지 않았다.

| 검증 | 실제 근거 | 결과 |
|---|---|---|
| 단위·PG·runtime/S3·storage 전체 회귀 및 bootJar | `20261004T213243Z-a67f2e6e` | 단위122/PG232/runtime61/storage11, 총426개 PASS·skip0. 당시 신규 결과 기록8개 포함 |
| 마이그레이션 및 발행 경계 최종 회귀 | `20261004T214331Z-133f2f9f` | 신규 suite9개 PASS·skip0. 기존 결과 backfill과 실제 RuntimeWorker 정리 후 pending 발행 보존 포함 |
| OpenAPI 및 계약 | `20261004T213756Z-12d85056` | 5계약 생성·선택 Java 계약 시험 PASS |
| 실제 Kubernetes AUTO·offload·cancel | `20261004T213636Z-e95f14b8` | 3개/Pod10·시작 기록10·고정 결과6·Result 기록6·API Pod3·발행 pending0·소유 정리 PASS |
| V35 DB 백업·복원 | `20261004T213927Z-20f7da84` | 13개/44테이블·Flyway행36·기동 격리/불변 제약·소유 DB/API 정리 PASS |
| V35 DB/S3 참조 | `20261004T213657Z-26111665` | 9개/별도 TLS MinIO·원본 제거·고정 결과/checkpoint·누락/변조 거절·소유 정리 PASS |
| 기존 혼합 복구 CLI 회귀 | `20261004T213926Z-c02633cd` | 91개/실제 API TokenReview 시작 기록·Java해시17·Remote 결과·44테이블·격리·소유 정리 PASS |

전체 회귀 뒤 테스트에 마이그레이션1개와 기존9개 내 물리 정리 경계를 추가했다. 최종9개는
별도로 재검증했으며 전체 회귀를427개 한 번에 실행했다고 주장하지 않는다. 새 CI의
`runtimeArtifactIntegrationTest`에는 기존53개와 신규9개를 합친62개가 포함된다.
최종 suite는 각 시험의 소유 namespace에 남은 이전 fixture 명령을 격리한다.

검증 JAR SHA-256은
`6d8e60f54575ae6cc91efd1eeb0c6a9fc9d125bd1a6fd91d8421aee8cd2fc175`다.
전체 회귀에서 패키징하고 실제 Kubernetes/복원 API가 이 파일을 사용했음을 각각 대조했다.
이후 production Java·migration 변경은 없고 테스트/계약/근거 문서만 보완했다.

최종9개는 실제 PG/MVC/S3 경로의 정상201·동일 요청200, 실제 S3 저장 후 응답 유실503,
실제 폐쇄된 loopback TCP 접속 실패, producer 인증 거절 뒤 새 worker/lease 만료 복구,
동시4발행의 단일 version, 내용/중복 JSON/미지정 필드/mediaType 충돌 거절을 포함한다.
취소가 검증과 DB commit 사이에 오면 Result와 큐·기록이 없고, DB rollback도 세 가지를
남기지 않는다. 이미 확정한 Result는 S3 발행 실패나 Pod 정리로 실패 상태가 되지 않는다.
Kubernetes 신원/종료는 이 Java 결합 시험에서는 명시적 fixture다.

마이그레이션 시험은 소유 PostgreSQL의 transactional DDL로 발행 테이블/trigger를 제거한
상태에서 실제 V35 SQL을 적용했다. 기존 확정 Result를 재발행 큐에 넣으면서 Result ID/시간,
내용 및 물리 CREATE/DELETE 명령을 바꾸지 않고 Remote/VD를 제외함을 확인한 뒤 rollback했다.

실제 Kubernetes3개는 별도 API/DB/TLS MinIO/MQTT와 CI 검증된 Runner
`8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`
(소스 `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`)로 실행했다.
실제 Runner가 만든6개 결과의 bytes/SHA/계산값과 PG의 모든 Result 신원·고정 출력 버전·
committedAt을 S3 기록과 대조했다. 각 기록은1version이며 발행 큐가 전부 완료됐다.
기존 STREAM checkpoint·API 교체·전환·취소도 유지됐다. 종료 후 시험 label과 Run ID로
Kubernetes Pod/Job/Service/ConfigMap/Secret 잔여0을 별도로 재조회했다.
업무 계산은 SYNTHETIC이며 실제 모델/NPU 추론 수용과 구분한다.

기존91개는 새 V35 스키마에서 ADR0102 시작 허가 복구를 다시 검증한다. 원래 전환1개,
claim0/Result0·43개 타 테이블 보존, 14종 변형·실제 동일 바이트의 다른 S3 version 거절,
DB 경쟁/rollback/COMMIT 응답 유실·재실행0 및 격리를 유지한다. 이 시험을 새 Kubernetes
Result 소비의 검증으로 확대하지 않는다. 전체 소유 namespace/DB/API/Remote/MinIO 정리를
확인했다. 일부 기존 PASS 문장의 보존 테이블 수는 이전 스키마 값이며 최종 JSON의 검증된
V35 수치를 따른다. 현재 소스의 문장은 새 수치에 맞췄다.

로컬 원시 근거는 `docs/evidence/runs/<위 ID>/result.json`과 `output.log`, 최종 XML이다.
전체 회귀 XML은 `.tools/result-journal-full-213243/`에 보존했다. 개인 복구 보고서는
`.tools/result-journal-{kubernetes,recovery,references,backup}.json`이며 자격/원본 DB는 배포하지 않는다.
초기8개 `20261004T213008Z-47ac8931`, 마이그레이션9개 `20261004T213656Z-e17555a9`,
물리 정리 확장9개 `20261004T214219Z-2efaba00`도 통과했으나 위 최종 근거를 우선한다.

선행8b964d6 CI37234177387은 마지막 관측에서5jobs 성공, images의 실제 Kubernetes
검증 진행 중이다. 완료 작업의 원시35개 PASS/PG232를 내려받아 확인했다. 해당 소스에는
ADR0103이 없으며 새 코드의 원격 CI·이미지·배포를 확인한 것이 아니다. 선행 실행을 취소하지
않도록 새 push는 종료 뒤 진행한다. 전체 M5 잔여/M7–M10 목표를 유지한다.
