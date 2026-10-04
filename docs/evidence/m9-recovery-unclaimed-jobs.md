# M9 claim 전 Job 종료 및 STARTING 전환 복구

2026-10-04, ADR0084. 실제 PostgreSQL16·패키징 API·Kubernetes의 결합56개가
`20261004T131023Z-96d5d08f`에서 PASS다. 기존46개와 아래10개를 같은 실행에서 검증했다.
복원DB5개, 실제 부모/자식4쌍과 never-bound Pod2개를 사용했다. 시험 소유 namespace,
DB, API를 모두 정리했다. Runner는 검증된 be41a8b digest이며 API JAR SHA256은
`3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`로 변하지 않았다.

1. 기본 옵션은 기록된 Job UID가 있어도 claim 없는 runtime을 미해결로 유지한다.
2. `--unclaimed-jobs`는 실제 실행 후 종료한 Pod와 같은 Job의 실제 never-bound Pod를
   모두 열거한다. 두 번째 Pod는 소유권/finalizer를 명시해 생성한 시험 fixture이며 실제
   Job controller의 replacement scheduling을 재현했다는 뜻은 아니다. suspend 상태로
   한 번도 Pod를 만들지 않은 별도 Job은 미해결로 보존한다.
3. 실제 DB에서 Job UID를 지우면 현재 Job을 새 claim으로 받아들이지 않는다.
4. 실제 관측 응답을 기반으로 backoff/active/완료 계수/미보존 종료 UID를 주입하면
   전체 DB 쓰기를 거절한다. 이 모순 응답은 명시적 observation fixture다.
5. 실제 Pod 관측의 controller apiVersion을 바꾼 fixture도 거절한다.
6. 실제 종료 반영 COMMIT 응답 유실 뒤 재실행은 변경0이다. 대상 runtime과 명령은
   종료하되 claim/node/Result를 만들지 않고, 빈 Job의 runtime·미완료 명령2개는 유지한다.
   원래43테이블 중37개는 전체 행 해시가 동일하다.
7. 원본 VD claim과 실제 supervisor 종료, 대상 Job의 새 종료 증거가 모두 있어야
   STARTING 기한 처리를 선택한다. 옵션이 없으면 대상은 계속 미해결이다.
8. 마지막 Run 쓰기에서 실제 SQL trigger 오류를 내면 대상 사유·Attempt·Task·전환
   전체가 원복된다. 실패 의도 기록을 보존하며 성공 보고서는 만들지 않는다.
9. 기록된 시작 기한이 지난 대상은 TARGET_START_TIMEOUT·FAILED로 확정된다.
   source runtime 전체/원본 OFFLOADED·고정 배치/기한은 보존하며37개 다른 테이블도
   동일하다. 대상 producer_pod_uid/node_uid/node_name은 계속 NULL이다.
10. 전환 옵션과 일반 workflow 복구 모두 재실행 시 이력과 timestamp를 바꾸지 않는다.

전환/업무 상태·source claim/할당 종료는 명시적인 DB history fixture다. 실제 SDK의
공개 offload 요청이나 모델 실행 수용을 대체하지 않는다. V1–V34 및 공통 workflow SQL은
변경하지 않았으며, 해당 SQL의 선행 Remote15개 검증 근거를 유지한다.
kind CI에 같은56개와 정확한 빌드 API JAR/Runner digest·Compose PostgreSQL17을 연결했다.
새 소스의 원격 CI/배포, 미관측 과거 실행·Remote/STREAM/group/checkpoint/journal·전체
writer 격리와 종합 서비스 재개, M5 잔여 및 M7–M10 전체 수용은 남는다.
