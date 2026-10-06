# 누락 STREAM 완료 이력의 DB 복원 검증

2026-10-05. [ADR0114](../adr/0114-restored-stream-completion-history.md)와
[실행 절차](../operations/recovery/recovery-stream-completions.md)의 검증 근거다.

## 실제 검증 경로

공개 API에서 Device → Kubernetes 작업 → VD 작업과 두 결과를 기다리는 BATCH 자식을
만든다. 실제 TLS claim/Pod-bound TokenReview, checkpoint API 5개, 공동 완료 허가와
Result 2개를 생성한다. 경로 배정·VD readiness·Device END는 명시적 fixture이고 데이터는
합성 값이다. 실제 모델 추론이나 자동 배정 전체의 수용으로 확대하지 않는다.

claim 전 DB 백업에는 원래 실행과 경로만 있고 checkpoint와 완료 허가는 없다. 완료 문서,
모든 선행 checkpoint receipt 및 실제 payload를 다른 TLS S3에 고정 version으로 복제한
뒤 원본 DB와 저장소 데이터를 제거한다. 실제 Pod 2개 및 broker principal 3개를 종료하고
독립 복원 DB 7개에서 명령을 실행한다.

`20261005T071615Z-bc6e1d21`은 **39개 PASS/exit0**다.
원시 보고서는 `.tools/stream-completion-v40-history.json`이다.

- 원래 완료 문서·모든 receipt·시작 허가·현재 종료 증거가 읽기 전용 복원 계획을 만든다.
- 다른 transaction ID, 활성 경로 생성, receipt 내용 교체, 일반 INSERT 경로로의 우회를
  실제 SQL에서 거절한다. 예상한 오류 메시지도 대조해 구문 오류를 성공으로 세지 않는다.
- 두 번째 checkpoint에서 SQL 오류를 주입하면 앞선 producer 신원·generation·checkpoint도
  모두 rollback한다. 실제 Task 동시 변경은 유지하고 오래된 계획의 반영을 거절한다.
- 실제 복구 CLI가 원래 checkpoint ID/시각 5개, 공동 grant 3개와 원래 완료 문서를 보존한다.
  두 generation은 CLOSED, runtime은 STOPPED/TERMINATED다. 다른 39개 테이블과
  heartbeat 이력은 바뀌지 않는다. 원래 완료 문서의 UTC offset도 보존한다.
- 같은 입력의 재실행은 변경 0이다. 또 다른 claim 전 복원 DB에서 실제 COMMIT 후 응답을
  유실시켜도 checkpoint/허가가 한 번만 남고 실제 CLI 재실행은 변경 0이다.
- 복원된 허가를 기존 NODE/VD Result CLI가 소비한다. 두 결과 전에는 후속 Attempt가 없고
  모두 복원한 뒤 READY/QUEUED가 한 번 생성된다. 새 runtime은 0개다.
- 기존 혼합 그룹의 양쪽 복원 순서, peer 관측 모순 14종, 고정 파일 누락·실제 broker 재허용,
  후속 작업 오류 rollback·COMMIT 응답 유실·이후 실패 이력 보존도 함께 통과한다.

원본 제거, DB 7개·namespace 제거 및 API/S3/MQTT 종료를 보고서에서 확인했다.
Result 단계가 보존하는 다른 테이블 수는 40개로, 완료 이력 삽입 단계의 39개와 구분한다.

## 재현한 실패와 수정

- `065422Z-ce50b27f`: 11개 뒤 UTC instant 검사에서 실패했다. 원래 SQL 문서는 시간대
  offset을 유지하므로 검증용 복사본만 UTC로 바꾸고 원래 문서와 저장 시각은 유지했다.
- `065652Z-5250295c`: 15개 뒤 시험 문자열 치환에서 실패했다. 세 번째 거절은 주입 SQL의
  따옴표 오류였으므로 의도한 제약 검증으로 세지 않는다. 최종 시험은 인코딩과 실제 오류를
  함께 확인한다.
- `stream-completion-recovery-guards.json`: 기본 ServiceAccount가 없는 새 시험 namespace의
  Pod 생성에서 0개 후 실패했다. 소유 namespace에 계정을 직접 준비하도록 수정했다.
- `070909Z-56aaa9fa`: 실제 복구 명령은 성공했지만 18개 뒤 타 테이블 보존 검사에서 실패했다.
  V21 trigger가 복원 generation에 heartbeat 초기 행을 생성했다. 마지막 lease에서 원래
  heartbeat를 추정하면 안 되므로 이미 적용한 V39는 유지하고 V40으로 해당 초기화를 제외했다.

위 실패의 소유 자원 정리도 확인했다. 일반 경로 생성의 heartbeat 초기화는 유지한다.
V40 패키징 `071506Z-2926e462`는 PASS다.

`072406Z-9a812199`의 V38→V40 업그레이드는 기존 45개 테이블과 완료 publication 97개를
유지했다. 원본 V34 DB를 읽어 만든 소유 시험 복사본만 V38, V40 순서로 올렸고 Flyway
이력 외 모든 행 digest가 같았다. 원본 불변·복사 DB 제거·API 종료와 기본 복구 scope 비활성을
확인했다. 보고서는 `.tools/stream-recovery-upgrade-1f748fd42aa74717881e15acf162fa4d/report.json`이다.

`072525Z-53af2ecc`에서 일반 PostgreSQL 통합 232개는 통과했지만 STREAM 15개 중
`publicNodeOffloadRestoresActualGroupCheckpointsAndReconnectsSameDeviceOwners`가
시험의 DB 조회 중 연결 풀 대기 5초 초과로 실패했다. 당시 pool은 active 5/idle 0/waiting 7,
관측 420개에서 I/O 대기 query 최대 4.883081초·transaction 최대 12.281935초와 행 잠금
대기를 확인했다. 단일 전체 명령은 FAIL이며 새 SQL 제약 오류는 관측하지 않았다.
소유 DB/S3를 정리했고 원시 XML 및 대기 표본은
`.tools/stream-completion-tests-b5b3bd0db078416e97dc13b977f573a8/`에 보존했다.

같은 코드의 독립 재검증 `073126Z-b8cf730f`는 STREAM 15개 전부 PASS/skip 0이다.
완료 checkpoint 참조 22개와 guard도 확인했고 소유 DB/S3를 정리했다.
보고서는 `.tools/stream-completion-tests-35be269304df444b80a5817c512d8575/report.json`이다.
이 성공은 앞선 DB 지연의 근본 원인 해결이나 단일 전체 회귀 통과를 뜻하지 않는다.

V40의 실제 PostgreSQL archive·격리 복원 13개는 `073407Z-093a44eb`에서 PASS다.
45개 테이블·백업 이후 쓰기 경계·일반 API 기동 거절·읽기 전용 점검·손상/권한/기존 DB
보호와 소유 정리를 확인했다. `.tools/stream-completion-v40-backup.json`의
`migrationCount:41`은 V1–V40과 schema 생성 행 1개를 합친 값이다. archive의 실제 Flyway
행을 읽어 대조했으며, 최초 감사의 40행 기대값은 오류였으므로 이를 정정했다.

최종 감사 `073916Z-4bd96b9f`는 원시 JSON/XML, JAR 안의 V39/V40 bytes와 적용 파일 hash,
원본 포함 소유 DB 8개 및 namespace의 실제 부재를 대조했다.
`.tools/stream-completion-v40-final-audit.json`은 앞선 전체 회귀 실패와 DB 지연 원인 미해결을
명시하고, 232개·독립 15개 성공을 단일 전체 PASS로 합산하지 않는다.

## 코드 및 원격 CI 범위

검증 JAR SHA256은 `9203c8650a183516ab6f00befdaf8ae8e1e33f2328f9f44a9b9684576fbea79e`다.
V39 SHA256 `5fe12022767982892749fff11a26e9d1a2caea7f2251f1176460e7911c6ab72a`,
V40 SHA256 `2482a7b80f2f60c46a47c95d0d899c19bf996b49f07b206b5e9a8659ade49c4a`와
패키지 내부 bytes가 일치한다. V37/V38의 적용된 파일도 변경하지 않았다.
기존 kind 혼합 그룹 게이트가 이번 39개를 실행한다.

선행 `3afc0f0`의 [CI37270925730](https://github.com/dsa04156/edgeai/actions/runs/37270925730)은
images job의 실제 Kubernetes 검증에서 실패했고 GitOps는 생략됐다. 이미지 artifact의
원시 images 검사는 PASS, kind 검사는 FAIL이다. 실패 위치는 `vd-distinct-second`의
장치 Run 종료 검사이며 정확한 선행 Run 실패 원인은 미확정이다.
`.tools/ci-37270925730-partial/image-failure-audit.json`에 artifact SHA와 판정을 남겼다.
이는 새 복구 코드 또는 선행 미push driver 수정의 원격 검증 결과가 아니다.

누락된 실행 자체·선행 generation 권한 복원, 혼합 그룹과 상속 finalizer의 결합,
전역 writer/API 차단·종합 활성화, identity/RBAC, 실제 모델·외부 계약 및 전체 M0–M10
수용은 남는다. 이번 39개 성공으로 전체 M9 완료를 판정하지 않는다.
