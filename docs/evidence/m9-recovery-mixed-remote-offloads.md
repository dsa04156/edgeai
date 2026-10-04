# M9 Kubernetes·참조 Remote 혼합 전환 복구

2026-10-04, ADR0085. 실제 PostgreSQL16/API·Kubernetes·참조 TLS Remote의 결합64개가
`20261004T132134Z-7df3eacf`에서 PASS다. 기존56개에 혼합8개를 추가했다. 복원DB6개,
실제 Kubernetes 부모/자식5쌍·never-bound Pod2개와 Remote 할당4개를 사용했다.
Remote 실제 실행 스레드2개의 동작을 관측하고 제공자 fence 뒤 종료를 확인했다.
소유 namespace/DB/API/Remote 프로세스를 모두 정리했다.

1. Remote fence의 실제 완료만으로 DB의 미완료 runtime/명령을 건너뛰지 않는다.
   별도 retirement가 정확한 관측/종료 상태를 반영해야 workflow 복구가 진행된다.
2. 명시적 연결 파일이 없으면 혼합 전환4개 모두 Remote 증거 필요로 미해결이다.
3. 실제 TLS 인증서 pin/제공자 ID가 다르면 쓰기 전에 거절하고 모든 테이블을 보존한다.
4. 실제 제공자 프로세스가 내려가면 cached 보고서로 진행하지 않는다. 같은 제공자의
   재시작 뒤 영속 fence와 할당 이력이 동일함을 확인한다.
5. Kubernetes→Remote와 Remote→Node 기한 검사, VD→Remote 취소를 함께 선택한다.
   실제 SUCCEEDED인 다른 VD→Remote target은 시간 초과로 덮어쓰지 않고 별도 결과
   조정에 남긴다. 고정 binding과 종료 Remote runtime4개의 증거를 확인한다.
6. 마지막 실제 관측 후 remote_allocation의 관측 시각만 바꾸는 실제 SQL writer가
   들어와도 전체 workflow transaction이 원복된다.22테이블 guard가 이를 감지한다.
7. 마지막 Run 갱신에 실제 SQL trigger 오류를 내면 양방향 target 실패/Task/전환과
   취소가 함께 원복되고 성공 보고서가 생성되지 않는다.
8. 실제 COMMIT 응답 유실 뒤 새 실행은 변경0이다. 시작 시간 초과2개·전환 취소1개,
   미해결 성공1개, source runtime 전체/원래 OFFLOADED/배치/기한, provider 할당/출력
   metadata와37개 다른 테이블을 보존한다. Node target의 claim/node 필드는 NULL이다.

Remote 결과 복구14개 `20261004T132150Z-a211a1f9`와 실패 복구15개
`20261004T132149Z-1ac3b571`도 PASS다. 공통 Remote 조회에 namespace를 추가한 변경을
재검증했고 실제 S3/Java digest·원복/경쟁/응답 유실·재시도/기한·소유 정리 근거를 확인했다.
API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`,
V1–V34·공통 workflow SQL은 변경하지 않았다.

할당은 실제 TLS 제공자에 예약/실행했지만 전환/claim/과거 시각은 명시적인 DB history
fixture다. 공개 offload 요청·외부 업체 제공자·모델/장비 수용을 대체하지 않는다.
보존한 성공 파일의 S3 등록과 Task/Result 확정은 이 검사에서 수행하지 않는다.
kind64개 게이트를 연결했으며 새 소스 CI/배포·Remote 성공/실패 결과와 전환의 종합 조정,
STREAM/group/checkpoint/journal·전체 writer 격리·활성화와 M5 잔여/M7–M10은 남는다.
