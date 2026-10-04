# M9 복원 BATCH 전환의 취소와 기한 조정

2026-10-04, ADR0083. 기존 실제 Kubernetes/VD 종료·복원 작업 시험에 `--offloads`를 추가했다.

| 실행 | 판정 |
|---|---|
| `20261004T124404Z-bb93643e` | 선행44개 결합 시험 PASS/0 |
| `20261004T124649Z-b0e9247f` | source claim 누락·새 epoch 보존을 포함한 최종46개 PASS/0 |
| `20261004T124502Z-1302d13a` | 변경한 공통 SQL의 실제 Remote 회귀15개 PASS/0 |

최종 보고서 `.tools/recovery-batch-offloads-final.json`은 기존37개와 전환 복구9개를 포함한다.
실제 PostgreSQL16/API, 복원 DB4개, 부모/자식3쌍의 Kubernetes 종료·보존 Pod·quota를 사용한다.
기존 경쟁 시험의 네 번째 복원 DB에 남은 할당도 실제 종료 근거로 정리하고 전환 이력을 구성했다.
전환 Operation/source claim/대상 Node/과거 시각은 명시적 DB fixture다. 새로운 공개 offload
요청이나 target Job 기동을 수행한 시험으로 해석하지 않는다. 기존 Result2개도 SQL fixture다.

Kubernetes 원본 취소1개·VD 원본 취소1개와 이미 지난 DRAINING1개, 아직 유효한 DRAINING1개를
검증했다. 첫 처리에서는 Operation 취소2개·실패1개, Task 취소2개, 기존 재시도 만료1개,
BATCH 후손2개·Run6개를 조정했다. 업무 실패를 새로 보고하거나 재시도/Attempt를 만들지 않았다.
전환 원본 Attempt는 OFFLOADED, runtime failure_reason은 기존 값 그대로다. target/기한/입력,
Result/할당/runtime/명령 등39테이블은 보존했다. 미래 전환은 원래 deadline을 유지했다.

source의 기록된 claim이 없으면 supervisor 종료만으로 대신하지 않는다. 별도 STARTING target
fixture는 DB에 TERMINATED로 표시돼 있어도 실제 Pod 신원이 없으므로 미해결로 남겼다.
새 epoch가 있으면 이전 Operation으로 Task를 덮어쓰지 않는다. 이 두 보존 검사는 실제 target
기동·시작 기한 수용을 증명하지 않으며 해당 경로의 실제 producer 증거 회수는 남는다.

마지막 관측 뒤 Operation deadline만 바꾸는 실제 DB 쓰기는 transaction guard에서 거절했다.
마지막 Run UPDATE 오류는 앞선 Operation/Task/예약/후손 변경을 모두 원복했다. 실제 COMMIT
응답 유실은 intent만 남겼고 같은 입력의 새 출력 경로에서 변경0을 확인했다. 미래 Operation의
기록된 시각을 함께 이동한 fixture도 새 기한 없이 SOURCE_DRAIN_TIMEOUT으로 정리됐다.
이후 기본 workflow 명령과 옵션 명령 모두 변경0이며 OFFLOADED source/FAILED Task 이력을
정상적으로 읽는다. 시간 이동은 성능이나 wall-clock 경과의 증거가 아니다.

기존37개의 잘못된 신원·물리 종료 전 거절·21테이블 guard/실잠금·원복·Result/새 Attempt 보존,
inspection GET/POST403·Pod404 거절도 함께 통과했다. 소유 DB/API/namespace 정리는 모두 true다.
제품 JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`와 V1–V34는
유지했다. 공통 SQL의 기존 Remote15개도 통과했다.

kind 게이트는 `--vd-tasks --workflows --offloads`46개로 확장하며 원시 report 수는32개다.
선행 be41a8b CI37200100790에는 이 확장이 없고 새 CI/배포 판정은 별도다. claim 전 target,
Remote/STREAM/group/checkpoint/journal·결과 회수·전역 writer·종합 활성화 및 M5 잔여/M7–M10
전체 수용은 미완료다.
