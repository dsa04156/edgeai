# VD 자식 확정 결과의 독립 보존 검증

[ADR0106](../adr/0106-vd-task-result-journal.md)은 확정 Result와 원래 VD 배정을 S3에 보존한다.
VD 시작 기록 소비·격리 복원 DB의 결과 반영·새 실행 권한/전체 활성화는 별도다.

`20261004T231739Z-b6c02814`는 실제 PostgreSQL·MVC·MinIO 대상20개가 실패/오류/생략0으로
통과했다. 새 `VDTaskResultJournalIntegrationTest`9개, 기존 Kubernetes 결과9개,
실제 Python supervisor/child HTTP2개다. 소유 DB/MinIO를 정리했다.
Pod/Node 신원·물리 종료는 이 Java 수트에서 명시적인 fixture다.

- 공유 Pod의 두 자식은 서로 다른 Result/배정·슬롯과 각각 한 S3 version을 보존한다.
- 실제 저장 뒤503 응답을 주입해 원래 Result ID·commit 시각·version 재사용을 확인했다.
- 닫힌 TCP 포트에 실제 S3 연결을 시도하면 DB 결과는 유지되고 큐는 미완료로 남는다.
  supervisor/배정 종료와 인증 상실 뒤 새 publisher/worker가 재발행하며 회수된 lease의 이전 owner는 완료하지 못한다.
- 출력 검증 중 취소는 Result/발행 큐를 만들지 않는다. DB rollback은 둘을 함께 제거하며
  transaction 안에서 S3 결과 발행을 시도하면 거절한다.
- 배정·세션·supervisor·설정/manifest digest·startKey 충돌과 중복/추가/trailing JSON,
  잘못된 media type은 기록을 덮어쓰지 않는다. 확정된 Task는 실패로 변경하지 않는다.
- 실제 동시 발행4개는 한 version으로 합쳐진다.
- 기존 확정 결과를 V36으로 backfill하고 재적용해 한 큐 행·원래 Result ID/시각과
  다른 완료 큐 행·물리 명령을 보존한다. 과거 시작 기록이 없어도 이를 소급 생성하지 않는다.
- 실제 Python VD는 assignment/claim/commit 성공 응답을 각각 한 번 잃은 뒤에도 같은
  Attempt와 시작/결과 version으로 완료한다. 공유 Pod의 선택적 취소도 다른 자식의 결과를 보존한다.

V36 SHA256: `ed078e8517e85c403dac9df8a8cd5ea777cb3497cf0bf408495e6c44bdb6fb2e`.
OpenAPI5개 생성 및 MVC/Swagger 계약은 `20261004T231914Z-d357d12a`에서 PASS다.

전체 명령 `20261004T231954Z-ea0c46d7`에서 실제 XML의 PostgreSQL232개·runtime79개·저장소11개는
실패/오류/생략0이다. 단위122개 중 원격 다운로드 시간 제한 시험1개가 실패하여 전체 명령은 FAIL이다.
250ms HTTP 제한의 다운로드/클라이언트 생성·종료 합계가1초 미만이어야 하는데1.249508777초였다.
이때 각 단계별 시간은 없어 원인을 확정할 수 없다. 이후 같은 합격 기준을 유지한 채 생성·다운로드·
종료 시간을 구분하는 실패 진단만 보완했다. 단위122개 `20261004T232535Z-740799bc` 재실행은
실패/오류/생략0/PASS다. 재실행 통과를 최초 실패 원인의 해결로 간주하지 않는다.
각 명령에서 소유 DB/MinIO 정리를 확인했다. 한 번의 전체444개 PASS로 요약하지 않는다.

실제 Kubernetes 첫7개 선택 `20261004T232632Z-ebeda973`은 공유 VD의 자식 장애 복구 후
두 번째 데이터 처리/END 단계에서 sink가 RUNNER_FAILED로 종료되어 FAIL이다. 그 전에 의도적으로
종료한 첫 sink의 WORKLOAD_FAILED와 구분한다. 기존 supervisor가 자식 stdout/stderr를 버리므로
원래 Runner의 세부 코드는 보존되지 않았다. fixture 자원·관측 VD 전체·실패 Run의
Pod/Job/Secret 잔여0을 별도 조회했으며, 이 실패의 원인은 미확정이다.
같은 경로만 분리한 `20261004T233011Z-625bbdc5`는 PASS다. 실제 VD 시작5개·확정 결과3개가
각각 한 version이고, 출력3개의 고정 S3 version/bytes/SHA·계산 값과 발행 대기0·소유 정리를
검증했다. API JAR은 `ac681d4ca1bcf588f0fbd3b8a4fb17876097567c0ff1557def6f88029cd9a453`다.
분리 재실행 통과를 최초 실패의 원인 수정으로 해석하지 않는다.

같은 JAR의 전체7개 재실행 `20261004T233237Z-411e4f13`은 PASS다. 공유 실행·자식 장애 후
그룹 retry·공유/별도 VD의 Node 전환·취소·최종 저장 재시도·supervisor 교체를 포함한다.

- 실제 VD Pod17개와 Node Pod2개를 관측했다.
- VD 시작 기록27개/Node2개, VD 확정 결과16개/Node2개를 원래 배정·세션·실제 Pod/node와
  DB의 Result 신원/시각·고정 출력에 대조했다. 각각 한 version이며 발행 대기는0이다.
- 실제 출력18개가 고정 version/bytes/SHA·합성 계산 값과 일치한다.
- 소유 자원과 Job barrier를 제거했다. 기존 배포 교체나 실제 AI 모델 수용은 이 시험의 범위가 아니다.
- Runner는 검증된 index `8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`,
  source `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`을 사용했다.
- 위 JAR의 app 파일218개와 domain/adapters JAR2개의 실제 byte 일치를 확인했다.

V36의 백업/복원 회귀도 같은 JAR로 통과했다.

- DB 백업13개 `20261004T233839Z-eb4b1056`: 실제 PostgreSQL16, 전체44테이블/Flyway37행,
  원본 보존·격리 복원·조회 API·쓰기 거절과 소유 DB/API 정리를 확인했다.
- 저장소 참조9개 `20261004T233924Z-6092f907`: 원본 제거 뒤 실제 복원 DB와 TLS 백업의
  모든 고정 결과/과거 checkpoint 대조, 누락·변조·새 버전 대체·미지원 schema 거절과 소유 정리를 확인했다.
- 기존 Kubernetes Result 복원20개 `20261004T234127Z-1fa39195`: 실제 TokenReview/Runner API·
  별도 S3 백업·보존 Pod 종료·복원 DB5개에서 원래 Result/producer/자식 상태를 조정했다.
  경쟁·잠금·rollback·COMMIT 응답 유실·후검증 실패·격리 유지·소유 namespace/DB 제거를 확인했다.
  이는 V36에서 기존 Kubernetes 복원이 보존된다는 근거이며 VD 결과 복원 시험은 아니다.

최종 패키지/근거 감사 `20261004T234720Z-a16cb745`는 실제 XML 수트 수·원시 PASS/FAIL,
동일 JAR/218개 app 파일/프로젝트 라이브러리2개, 적용 후 변경 없는 V36과 실제 Kube·백업/복원
보고서를 대조했다. 단일 전체 명령 PASS와 최초 실패 원인 해결은 명시적으로 false다.

새 코드의 원격 CI/배포는 아직 확인하지 않았다. 기존 간헐 DB/STREAM/SQLite 시험 실패가
이 변경으로 해결됐다고 주장하지 않는다. VD 기록의 복원 DB 소비·STREAM 권한·전역 writer/API
차단·종합 활성화와 전체 M0–M10 목표는 남는다.
