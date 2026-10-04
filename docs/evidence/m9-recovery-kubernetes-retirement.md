# M9 Kubernetes 종료 관측과 복원 DB 실행 정리

2026-10-04. ADR0080의 구성 요소 검증이며 플랫폼 전체 복구 완료가 아니다.

| 실행 | 결과 |
|---|---|
| `20261004T113750Z-657f6bd0` | 시험 준비 FAIL: 하나의 Postgres 진단 경로로 복원 보고서를 재사용. 별도 경로로 수정하고 남은 시험 DB1개도 정확한 runtime namespace/OID를 확인해 정리 |
| `20261004T113909Z-2ea84ad6` | 실제 PG16/Kubernetes12개 PASS/0 |
| `20261004T114126Z-5ef20866` | 최종16개 PASS/0, 복원 DB3개·컨테이너2/자식2·백업 후 never-bound Pod1개 |

API가 합성 Profile/Device/VD/Workflow/Run/Attempt를 만들고, runtime claim·VD binding
이력은 명시적 SQL fixture로 구성했다. 실제 Runner 이미지의 Python 부모/자식을 실행해
생존을 확인한 뒤 백업/복원하고 원본 DB를 제거했다. 운영 서비스는 차단하지 않았다.

실제 quota의 서버 dry-run 거절, retained Pod의 containerID/시각/exitCode 및 자식 회수
메시지를 대조했다. Task runtime1·VD runtime1·기존 명령4·VD binding1을 원자적으로
정리하고38개 다른 테이블과 두 번째 복원 DB를 보존했다. 같은 입력 재실행은 timestamp까지
변경0이며 명령 ID/기존 시도 횟수도 유지한다. 백업 후 생성한 Pod는 DB에 등록하지 않았다.

틀린 namespace/복원 DB 보고서·변조된 종료 증거·재개된 Job·다른 producer UID를 거절했다.
실제 outbox 경쟁을 SQL 직전에도 탐지하고 marker 변경·실제 binding 잠금·마지막 binding
UPDATE 실패에서 부분 쓰기가 없음을 확인했다. 실제 COMMIT 뒤 응답 유실은 intent만 남기고
재실행에서 변경0을 확인했다. producer UID 미기록인 세 번째 DB는 해당 runtime/대기 명령을
그대로 미해결로 남겼다. 종료 Pod 기록을 제거한 뒤에는404를 증거로 쓰지 않고 거절했다.

복원 inspection API의 GET 허용/POST403과 소유 DB/API/namespace 정리를 확인했다.
최종 요약은 `.tools/recovery-kubernetes-retire-final.json`, 원시는 위 evidence run에 있다.
기존 API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`이다.
V1–V34와 제품 API/Runner는 변경하지 않았다.

새 CI gate와 보고서 수집을 추가했다. 해당 게이트의 원격 실행·배포는 후속 확인 대상이다.
선행 cd1424a의 CI37196161670은5jobs/원시29개 PASS(`114232Z-47e37693`)지만 이 변경은
포함하지 않는다. 외부 writer 통제·VD Task/offload/STREAM/journal 결과·종합 활성화와
M5 잔여/M7–M10 전체 수용은 남는다.

선행 GitOps760908b의 실제 imageID/Ready/PVC/Argo Synced는 `114725Z-53fcdb3b`,
그 뒤 원래10파일/두PVC·TLS 보존은 `114806Z-497eac42` PASS다.
