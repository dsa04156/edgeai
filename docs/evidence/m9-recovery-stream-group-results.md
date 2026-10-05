# 혼합 STREAM 그룹의 결과 복원 검증

2026-10-05. [ADR0109](../adr/0109-restored-stream-results.md)의 연결 그룹 검증을
Kubernetes 작업과 VD 작업이 함께 있는 실제 복원 CLI 경로에서 확인한다.

## 검증 경로

공개 API로 Device → Kubernetes 작업 → VD 작업을 연결한다. 두 작업의 BATCH 출력은
후속 작업의 서로 다른 필수 입력이다. 실행 배정·VD readiness와 경로 활성화·Device END는
명시적 fixture이며, 실제 계산 대신 합성 값6을 사용한다.

각 Pod의 실제 TokenReview와 TLS claim으로 시작 기록2개를 만든다. SDK Journal에서
Kubernetes 작업은 DATA/END를 생성하고 VD 작업이 같은 프레임을 소비한다. 실제 API의
서명 업로드·고정 S3 version 검증·checkpoint commit/replay를 거친 receipt로만 Journal을
확정한다. 중간 기록을 포함한 checkpoint5개 중 최신2개가 완료 장벽에 사용된다.
변경 없는 Journal은 `capture()`가 None을 반환하므로 기존 확정 receipt를 유지한다.

첫 작업의 완료 보고는 WAITING이며 아직 grant가 없다. 두 번째 보고 이후 두 작업과
Device END가 같은 시각의 grant를 얻는다. 각 작업은 실제 업로드·Result commit/replay로
원래 결과를 보존한다. 첫 Result만 확정된 시점에는 후속 작업이 WAITING이어야 한다.

완료 전·부분 commit·전체 commit 등 원본 DB 백업과 독립 S3 백업을 만든 뒤 원본 DB와
S3 데이터를 제거한다. 복원 DB6개, 실제 부모/자식 프로세스를 종료한 보존 Pod2개,
두 MQTT 경로를 송수신한 principal3개의 연결 회수·재접속 거절을 확인한다.

전체 CLI에서 Kubernetes→VD와 VD→Kubernetes 순서를 각각 시험한다. 첫 결과만 복원하면
후속 Attempt는0개이며 같은 결과를 다시 확인해도 변하지 않는다. 두 번째 결과까지
복원해야 READY/QUEUED가 한 번 생성된다. 새 runtime은0개다. 원래 두 Result의 ID/시각과
checkpoint·grant·producer·VD 배정 등 다른39개 테이블은 유지한다.

추가 검증은 다음과 같다.

- 선택하지 않은 peer의 grant·시각·종료 상태·checkpoint version/summary·Task producer
  epoch 모순은 관측값 주입으로 거절한다. 실제 DB 제약을 우회해 저장하지 않는다.
- 독립 백업 manifest에서 peer checkpoint를 제외하면 어느 runtime 종류의 CLI도 거절한다.
- prepare 뒤 실제 peer Task 행을 바꾸면 그 변경을 유지하고 결과 복원은 거절한다.
- 실제 VD peer broker principal을 다시 허용하면 과거 fence 보고서가 있어도 양쪽을 거절한다.
- 후속 작업 READY 처리에서 실제 SQL 오류를 일으키면 두 번째 Result·artifact·발행 큐까지
  원복하고 이미 복원된 첫 결과를 유지한다.
- 두 번째 DB COMMIT 응답을 유실시킨 뒤 재실행해도 Result·후속 Attempt가 중복되지 않는다.
- 원본에서 부분/전체 commit된 백업의 기존 이력과 이후 후속 작업 실패를 덮어쓰지 않는다.

## 실행 결과

`20261005T030830Z-41af1e74`는19개 PASS/exit0다.
`.tools/mixed-stream-results-api.json`에 원본 제거, 복원DB6, 실제 종료Pod2, broker principal3,
두 복원 순서, 타39테이블 보존과 소유 namespace/DB/API/S3/MQTT 정리를 기록했다.
이 실행의 관측 모순은12종이다. grant 시각이1마이크로초만 다른 경우의 원자성 거절2종을
추가한 최종 `20261005T031714Z-75dce089`도19개 PASS/exit0다. 관측 모순14종과 실제
checkpoint receipt5개, 위 정리/보존 조건을 모두 확인했다. 최종 원시 보고서는
`.tools/mixed-stream-results-final-recheck.json`이다.

최종 감사 `20261005T032104Z-1a7740c8`는 최종 소스11개 SHA·JAR/V36 불변·원시 결과·
시작/결과 기록 각2개·checkpoint version5개·실제 종료Pod2개와 CI/문서 연결을 대조했다.
실패 실행까지 포함한 DB16개·namespace4개의 실제 부재도 다시 확인했다.
`.tools/mixed-stream-results-final-audit.json`에 결과를 남겼다. 공유 실행/복원 코드는
변경하지 않았으므로 선행182개 검증 근거를 재사용한다. 이번에182개를 다시 실행한 것은 아니다.

초기 `20261005T030300Z-1f598d2b`는0개 후 FAIL이다. 시험 코드가 이미 확정돼 변경 없는
checkpoint의 `capture()` 반환값을 새 snapshot으로 잘못 취급해 AttributeError가 발생했다.
최종 fixture는 실제 checkpoint API의 receipt를 저장하고 변경이 없으면 이를 유지한다.
초기 실행의 소유 namespace/DB/API/S3/MQTT 정리도 확인했다. 앞서 관측한 Job의 Pod 생성
대기는 이후 해소됐으며 이 AttributeError의 원인으로 간주하지 않는다.

`20261005T031337Z-77ac9420`은 그룹 fixture 실행 전 최초 실제 claim에서 HTTP503으로
FAIL이다. 오류 응답은0bytes이고 진단 시 같은 Pod UID/Running/삭제 중 아님을 확인했다.
원본 인증 필터의 빈503 경로까지 범위를 좁혔지만 DB/Kubernetes 등의 정확한 원인은
확정하지 못했다. 실패 자원 정리와 이후 독립 재검증의 PASS를 보존하며 원인 해결로
간주하지 않는다. 자동 재시도나 제한 시간 완화는 추가하지 않았다.

## CI 및 남은 범위

`test-kind.py`에 명시적 context·검증한 이미지/MinIO·Compose DB의19개 게이트와
`.tools/kind-recovery-stream-group-results.json` artifact를 추가했다. 새 혼합 그룹 코드의
원격 CI와 배포는 로컬 결과와 별도로 확인한다.

선행 `931922a`의 CI37256444752에서 완료한5jobs·원시35개 감사
`20261005T031032Z-e2a8b391`는 PASS다. 단위122·PG232·Remote24/13·각 native111/97이며
실제 registry의 두 platform manifest와 index도 `20261005T031032Z-930384ac`에서 대조했다.
이는 새 혼합 그룹 코드나 전체 images job/배포의 완료를 뜻하지 않는다.

자동 스케줄러·Device 실제 END API부터의 종단, 혼합 그룹과 상속 finalizer를 함께 사용하는
조합, 백업에 없는 완료 허가/실행 복원, 전역 writer/API 차단과 종합 활성화, 실제 모델·외부
계약 및 M0–M10 전체 수용은 남는다. 위 성공으로 전체 M9 완료를 판정하지 않는다.
