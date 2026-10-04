# M9 복원 VD 내부 작업의 실행 종료와 할당 보존

2026-10-04, ADR0081. 기존 Kubernetes 복구 명령의 VD Task 확장이다.

| 실행 | 판정 |
|---|---|
| `20261004T115733Z-64d93a90` | 확장한 코드의 기존 Kubernetes16개 회귀 PASS/0 |
| `20261004T115911Z-6a538928` | VD Task5종·실제 PG/Kubernetes22개 PASS/0 |
| `20261004T120058Z-fc84f05c` | 전체 행 해시를 단일 snapshot으로 모은 뒤22개 PASS/0 |
| `20261004T120355Z-1f71afa5` | 성공 결과 후에도 열린 할당을 포함한 최종23개 PASS/0 |

최종 `.tools/recovery-vd-retire-open-result.json`은 합성 업무 데이터·실제 PostgreSQL16,
복원 DB4개, 실제 Kubernetes의 부모 컨테이너3개와 자식3개를 검증한다. VD Pod에는 부모/자식
두 쌍을 실행한다. 원본 DB를 제거한 뒤 quota·보존한 Pod 종료 증거로 복구한다.
백업 후 생성한 never-bound Pod1개도 확인한다. 원래 서비스의 namespace는 변경하지 않았다.

API가 생성한 Profile/Device/VD/Workflow를 기반으로 Task runtime/claim/allocation 및
확정 Result2개는 명시적 SQL fixture로 구성했다. 실제 SDK claim이나 S3 파일 복구 시험이
아니다. 여섯 Task 이력은 실행 중, 배정만 됨, 과거 PROCESS_EXIT, 과거 NOT_STARTED,
성공 결과를 확정했지만 열린 할당, 미배정 상태다.

기존 Task Job1·VD supervisor1·명령4·binding1과 함께 VD Task runtime3개 및 열린
allocation3개를 원자적으로 정리했다. 새 closure는 POD_GONE이며 완료 sequence/exit code는
NULL이다. 과거 closure2개의 값/시각, 확정 Result/artifact2개, Task/Attempt/Run과 다른37개
테이블을 보존한다. 미배정 작업은 미해결로 남고 새 Job/명령/시도/재시도는 만들지 않는다.

실제 allocation만 추가되는 경쟁은14테이블 guard에서 차단하고, 외부에서 추가한 할당은
지우지 않았다. Task producer의 node 신원 충돌, 닫힌 allocation의 runtime 재활성화 모순,
실제 allocation 테이블 잠금, 마지막 closure UPDATE 오류를 검증했다. 마지막 오류는 그 전에
변경한 supervisor·binding·자식 runtime·명령까지 전부 원복한다.

실제 COMMIT 응답 유실 후 개인 intent 보존·동일 입력 변경0 재실행, 기존 UID/marker/종료
보고서 변조 거절, Pod404의 증거 대체 거절, inspection GET/POST403도 통과했다.
소유 DB4개/API/namespace 정리를 확인했다. 비교는43테이블 전체 행을 한 SQL snapshot에서
해시하며 검증 대상이나 필드는 줄이지 않았다.

제품 API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`,
V1–V34는 유지한다. 기존 kind 게이트에 `--vd-tasks`를 추가해 같은 CI 보고서로 수집한다.
원격 gate·배포 판정은 후속이며, 선행 be41a8b CI37200100790은 이 확장을 포함하지 않는다.
작업 결과/실패/취소·재시도·offload·STREAM/journal·전역 writer·종합 활성화는 남는다.
