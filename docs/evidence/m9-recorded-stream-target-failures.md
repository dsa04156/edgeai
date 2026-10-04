# M9 복원 STREAM 전환 실패 이력 검증

2026-10-05, ADR0094. 최종 결합36개 `20261004T170123Z-963eee1a` PASS/exit0.
서버와 동일한 CANCELLING Run fixture에서 재검증했다. 선행36개
`20261004T165714Z-1b833e2e`도 PASS였으며 최종 private report는
`.tools/recovery-stream-failures-final.json`이다.

기존29개에 기록된 target 실패7개를 추가했다. 실제 Kubernetes source2→target2의 부모/자식
종료와 PostgreSQL 복원16개, TLS MinIO·Mosquitto를 사용한다. 전환·업무 상태는 명시적인
SQL fixture다. Runner 실패 API를 호출하거나 이 시험에서 실제 모델 오류를 발생시켰다고
주장하지 않는다. 선택 target에는 WORKLOAD_FAILED, claim 전 peer에는 JOB_FAILED를 기록한다.

새 검사가 확인하는 범위:

- `--offloads` 없는 기본 취소 명령으로 FAILED/TARGET_FAILED 이력 검사를 우회하지 못한다.
- 실패 사유 누락/OFFLOADED 사유, 실패한 Task와 RUNNING Attempt의 모순, 전환 실패에
  남은 retry queue는 전체 DB를 그대로 유지하며 그룹을 미해결로 남긴다.
- Operation만 변경된 뒤 옛 plan을 적용하면 거절한다. transaction 마지막 SQL 오류는 peer
  취소와 Run 조정을 모두 원복한다.
- 선택 target의 실패는 실제 COMMIT 후 응답 유실·재실행까지 검증한다. peer의 실패는
  선택했던 다른 member의 취소를 완료한다. 두 경우 모두 Run은 FAILED이고 새 retry/Attempt는
  없으며 변경 가능 테이블 외 전체 행 지문이 동일하다.
- 실패한 Attempt/runtime 전체 행과 원래 FAILED/TARGET_FAILED Operation 전체 행,
  source/member/checkpoint를 보존한다. 반복 실행은 변경0이다.
- peer Job UID 증명이 없으면 이미 실패한 그룹도 부분 완료하지 않는다.

최종36개에서 실제 부모/자식4쌍·복원DB16개, source/member/checkpoint 전체 이력과
고정 S3 version2개/bytes/SHA 보존을 확인했다. 실패2건에 새 retry0·Attempt0이며
소유 namespace/DB/API·MinIO/MQTT 프로세스/client·잠금 프로세스는 모두 정리됐다.
사용한 Runner image 소스는 `c1333159154ff9512f41a0f751601c7434adac54`다.

수정 전 `20261004T165242Z-60ad0b8c`는 새 검사에서 실제 FAIL이었다. 실패 사유를 지운
복원본인데 기본 그룹 취소 경로가 `databaseModified=true`, `tasksSkipped=1`,
`runsReconciled=1`, 미해결0으로 처리했다. 실패한 Operation도 분류하도록 변경하고
원래 실패 Attempt/runtime 및 취소 이력을 함께 검사했다. 실패 시험의 소유 자원 정리도
확인했다. private report는 `.tools/recovery-stream-failures-before.json`이다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`와 V1–V34,
Runner, 기존 BATCH/Remote 복구 코드는 이번 변경에서 불변이다. 이전의 유효한 회귀 근거를
재사용한다. CI kind의 기존 `--offloads` 호출은 새36개도 포함하며 새 소스의 원격 CI 수용은
별도 확인한다. 실행 중 CI37218040065는 앞선 ADR0092–0093/29개 소스다.

실패 자체가 기록되지 않은 실행, 시작 성공 권한 journal, finalization, VD/자동 전환의 복원
종단과 전역 writer/새 자격/종합 활성화는 별도 남은 범위다. M9 또는 전체 목표 완료가 아니다.
