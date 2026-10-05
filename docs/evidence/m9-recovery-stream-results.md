# STREAM 완료 장벽과 원래 확정 결과 복원 검증

2026-10-05. [ADR0109](../adr/0109-restored-stream-results.md),
[실행법](../recovery-stream-results.md).

최종 Kubernetes30개 `20261005T014427Z-a9a2630f`, VD34개
`20261005T014427Z-4b52b8e4`가 PASS/exit0다. 각각 복원DB6개와 실제 원래 완료 허가,
고정 checkpoint1개, 타38테이블·원래 Result ID/시각·producer/VD 이력 보존을 확인했다.
`.tools/stream-result-node-component.json`, `.tools/stream-result-vd-component.json`에
소유 namespace/DB/API/S3/MQTT 정리가 모두 true이며 `activated:false`다.

원래 Result23종·STREAM 완료 장벽13종의 모순을 거절했다. VD 경로는 추가로 원래
배정/세션/슬롯/설정/기한18종을 검사했다. 실제 pre-grant 백업, checkpoint의 백업 목록 누락,
현재 broker principal 재활성화, 고정 결과 유실·version 교체도 DB 변경 없이 거절했다.
DB 관측 직후 경쟁·테이블 잠금 제한·후속 Task 오류 시 원복, 실제 COMMIT 응답 유실과
저장소 교체 후 재검사, 조회 전용 API 쓰기403도 포함한다. 기존에 부모가 성공하고 자식
runtime이 계획된 백업, 자식 실패와 terminal Run의 나중 이력을 그대로 보존했다.

공개 API로 Device·Profile·Run을 생성하고 실제 TLS Runner claim/Pod-bound TokenReview,
완료 허가와 서명 업로드/Result commit을 사용한다. 원본 DB와 MinIO 데이터를 제거한 뒤
별도 S3 백업, 복원 DB6개, 실제 보존 Pod 종료와 원본 TLS MQTT 차단을 대조한다.
Device END·route 활성화·checkpoint DB metadata·VD readiness/allocation은 명시적
fixture다. SDK Journal이 실제 checkpoint bytes를 만들지만 출력은 시험 클라이언트가
제출하는 합성 데이터다. 실제 모델 계산이나 Device SDK부터의 종단 계산 수용을 뜻하지 않는다.

후속 그룹 SQL 독립 probe `20261005T014114Z-d3a0fdeb`는3개 PASS/소유 DB 정리다.
실제 PostgreSQL transaction으로 member2개를 함께 READY/QUEUED로 만들고 runtime0개,
재실행0변경을 확인했다. 일부 member가 WAITING이 아니거나 BATCH 부모가 실패/결과 미확정이면
그룹 전체를 대기시킨다. 모순된 cancellation reason은 모든 준비 변경과 Attempt 생성을
원복한다. 이 probe는 명시적 workflow fixture이며 다중 member의 완료 허가 CLI 종단과는
구분한다. 원시 보고서는 `.tools/stream-result-readiness-probe.json`이다.
위 최종 결합 시험에서는4개로 확장했다. trigger가 첫 member의 READY 상태를 관측한
다음 두 번째 member에서 실제 오류를 내는 것을 개인 PostgreSQL 진단으로 확인했다.
첫 member 변경과 이미 생성된 Attempt까지 모두 원복됐다. 단순히 첫 변경 전에 실패한
검사로 부분 변경 원복을 대신하지 않는다.

초기 실패와 원인:

- `20261005T012646Z-f4f564b9`: 잘못된 broker 신원의 ValueError가 exit1로 분류됐다.
  DB 변경 없이 거절했으며 명시적인 Blocked로 변환해 exit2 계약에 맞췄다.
- `20261005T013312Z-9a933b1a`: 기존 개인 manifest를 변조하는 시험이 신규 파일 전용
  `durable_json`을 호출해 FileExistsError로 중단됐다. 실제 복구 검사를 거치지 못한
  시험 코드 오류이며, 소유 파일을 변경하고 원래 bytes를 finally에서 복원하도록 수정했다.
- Kubernetes `20261005T013454Z-25fedfda`와 VD `20261005T013433Z-88038b13`:
  claim 이후 snapshot의 RUNNING Attempt를 실패 시험 후 DISPATCHING으로 되돌린
  fixture 오류다. JDBC claim 구현이 RUNNING을 기록함을 확인하고 원래 DB 값을 읽어
  복원하도록 수정했다. 제품 상태 전이·시간 제한은 바꾸지 않았다.
- Kubernetes `20261005T013708Z-468fdac9`20개/VD `20261005T013708Z-a86da6e1`24개 후
  이미 commit된 백업 검사에서 BLOCKED였다. 원본 API는 Result 이후 BATCH 자식 runtime을
  계획했으며 그 자식은 미시작 상태였다. 복구 코드가 선택한 STREAM 연결 그룹 대신 Run
  전체의 종료를 요구한 것이 원인이다. 실제 retirement intent의 미해결 자식과
  RuntimeLifecycleService/ADR0038·0092의 그룹 경계를 대조했다. 선택한 그룹의 모든
  runtime·명령·배정 종료를 요구하고, 별도 후속 실행 이력은 보존하도록 수정했다.

초기4개는 FAIL 기록을 유지한다. `.tools/stream-result-failures-audit.json` 및
`20261005T014050Z-79adca14`는 각 실패의 API/저장소/MQTT/DB/namespace 소유 정리를 확인했다.
후속2개 실패까지 포함한 감사 `20261005T014455Z-77d1d71d`도 소유 정리 PASS다.
Remote 회귀 `20261005T013735Z-6c4764f1`은 복구 업무 case0 이전 원본 시험 DB의
DROP DATABASE30초 제한으로 FAIL이다. 같은 감사에서 해당 DB의 사후 부재와 소유 프로세스
종료를 확인했다. 당시 wait 경계 증거가 없어 지연 원인은 확정하지 않는다.

최종 기존 경로 회귀도 PASS/소유 정리다: Kubernetes BATCH20개
`20261005T014718Z-70cf4f72`, VD BATCH24개 `20261005T015106Z-5be66d33`,
Remote15개 `20261005T015249Z-26341551`. Remote 재검증은 별도 원본·백업·복원DB2개에서
실제 결과2개/자식 대기1개·원복·경쟁·응답 유실을 확인했다. 최초 DROP 지연 원인 해결을
주장하지 않는다.

최종 감사 `20261005T015347Z-967e0db4`는 새64+기존59=123개와 각 소유 정리,
실제 두 번째 member 원복·그룹 경계 회귀, 변경 없는 JAR/V36, Python/shell 문법,
CI YAML/명령/artifact와 문서 링크를 확인했다. `.tools/stream-result-final-audit.json`에
보고서와 소스 해시를 기록했다. 새 코드의 원격 CI/배포는 아직 이 검증 범위에 없다.

선행d764480 CI37250820250의 완료5jobs/원시35개 감사
`20261005T013932Z-5f739937`는 PASS다. 단위122/PG232/Remote Python24·Java13,
native amd64/arm64 각각 Runner111/MQTT97 및 두 native 결과의 index 포함을 확인했다.
전체 CI·실제 registry 독립 관측·신규 배포 검증은 별도다. 현재 STREAM Result 변경은
그 CI에 포함되지 않았다.
후속 실제 registry index의 두 platform manifest 대조 `20261005T014516Z-29ff78cd`도
PASS다. index는 `851e46156fc9232723aa062daae9a2d4840af093fc9c8a7c8bcb646f9f5a33c2`이며
전체 CI나 새 API 배포를 증명하지 않는다.

새 Kubernetes/VD STREAM 시험을 kind CI와 각 JSON artifact에 연결했다.
시험 API JAR SHA256은 `ac681d4ca1bcf588f0fbd3b8a4fb17876097567c0ff1557def6f88029cd9a453`이며
이번 변경에서 Java/migration을 수정하지 않았다.

기록된 상속 finalizer와 혼합 다중 member의 전체 CLI 수용, 백업 이후 누락된 완료 허가·
알려지지 않은 실행 복구, 전역 writer/API 차단·종합 활성화·실제 모델/외부 계약과
M0–M10 전체 완료는 남는다.

후속 [상속 finalizer 결과 복원 시험](m9-recovery-stream-finalizer-results.md)에서 단일 STREAM
작업의 두 후속 재시도와 원래 grant/checkpoint를 Kubernetes35개/VD39개 전체 CLI로
검증했다. 혼합 다중 member와 종합 활성화 등 나머지 범위는 계속 남는다.
