# M9 복원 Remote 실패·취소·재시도 대기 검증

2026-10-04, ADR0079. 최종 실제 결합15개 `20261004T111817Z-b771e368` PASS/0,
`.tools/recovery-remote-failures-final.json`. 선행11개 `20261004T111214Z-644b92f9`,
14개 `20261004T111626Z-67c6ff55`도 PASS다.

실제 PostgreSQL16·Java API·참조 TLS Remote로9개 할당을 만든다. workload 실패, 아직 시작하지
않은 제공자 작업, 사용자 Run/Task 취소, 성공 이력을 포함한다. 부모/자식/손자 BATCH DAG와
Kubernetes 초기 target으로 지정한 자식도 공개 API로 생성한다. DB archive/restore2개를
만들고 원본 DB를 제거한 뒤 Remote 차단·복원 실행 정리·파일 회수를 수행한다. 원본 제공자도
종료한 상태에서 실패·취소 정리를 실행한다. 실제 외부 제공자 수용 시험은 아니다.

| 시험 | 실제 결과 |
|---|---|
| 상태 정리 | 실패Attempt5, 재시도예약2, 사용자취소2, 후손SKIPPED2, 완료Run5 |
| 정책 보존 | 원래 실패 원인·retryOn·최대 횟수·첫 시도 기준 deadline과 backoff 적용 |
| 취소 우선 | 제공자가 SUCCEEDED여도 기록된 사용자 취소는 CANCELLED, 확정 Result와 미확정 성공1개 보존 |
| 신원·경쟁 | 다른 복원 bundle·검증 뒤 실제 outbox 변경·복원 marker 변경 거절 |
| 잠금 | 실제 offload table writer의 잠금에서5초 제한, 부분 반영 없음 |
| 후속 실행 | 종료되지 않은 Kubernetes 후속 runtime의 DB fixture가 전체 정리를 차단 |
| 원자성 | 실제 retry INSERT trigger 오류로 이전 Attempt/runtime/Task 변경도 함께 rollback |
| 멱등 | 같은 입력은0변경, 시간·기한·명령·기존 결과 유지 |
| 예약 변조 | deadline 연장을 거절하며 transaction 전체 변경 없음 |
| 새 시도 | 별도 DB의 명시적 새 epoch fixture를 과거 Remote 실패가 덮어쓰지 않음 |
| 응답 유실 | 실제 COMMIT 뒤 응답 오류를 주입, intent 보존·새 실행0변경 확인 |
| 실패 후 취소 | 기록된 취소는 Task만 종료하고 이전 FAILED Attempt는 보존 |
| 기존 사유 | 종료한 비Remote 후속 실행의 DB fixture에서 RUN_CANCELLED를 UPSTREAM_FAILED로 바꾸지 않음 |
| 기한 만료 | 관련 시간을 함께 과거로 옮긴 명시적 fixture에서 기존 예약2개 만료·새 기한/Attempt 없음·재실행0변경 |
| API 격리 | 패키징 조회 API에서 실패/취소/성공/미확정 Run 상태 확인, 관리 POST403 |

변경한 테이블은 runtime_instance/task_attempt/task/task_retry/workflow_run의5개다.
다른38개 테이블과 별도 복원 DB를 보존했다. 기존 Result1/artifact1은 불변 trigger를 유지한
명시적 SQL fixture다. 이 시험에서 S3 파일 보존이나 실제 Kubernetes producer 종료를
검증했다고 주장하지 않는다. 성공 파일/Result의 실제 저장소 검증은 ADR0078 게이트를 따른다.
모든 소유 DB/API를 정리했고 원본 제공자도 종료했다. 자격·파일 본문·raw SQL/로그는 개인 경로다.

첫 추가 시험 `20261004T111417Z-bc515afd`는5개 성공 뒤 실패했다. 테스트에서 Remote로
지정한 자식에게 AUTO INITIAL 시도를 삽입해 기존 DB placement 제약이 거절한 것이 원인이다.
공개 API에서 해당 자식의 AUTO target을 먼저 지정하도록 fixture를 수정했고14개가 통과했다.
이후 비Remote 자식의 기존 취소 사유 보존을 추가해 최종15개를 확인했다.

전체 Remote 종료 검사 함수를 ADR0078과 공유하도록 추출한 뒤 기존 실제 Result 검증14개도
`20261004T110815Z-f311735c` PASS다. 기존 API JAR
`3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`와 V34는 변경하지 않았다.

CI storage job에 Compose 게이트를 추가했고 두 복구 결합 시험 추가에 따라 상한을30분으로
설정했다. 새 코드의 CI/배포는 후속이다. 진행 중인 offload·STREAM·장치 journal·다른 producer의
실제 회수 증거·재시도 dispatch·전체 복구 활성화 및 M9 수용은 남는다.
