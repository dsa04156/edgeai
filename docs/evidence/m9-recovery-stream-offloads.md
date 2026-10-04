# M9 복원 STREAM 전환의 취소·기한 검증

2026-10-05, ADR0093. `20261004T163446Z-71726657` 최종 결합29개 PASS/exit0.
개인 보고서는 `.tools/recovery-stream-offloads-final.json`이다. 선행29개 `163031Z-d843c43f` 뒤
전체 source 이력·member/checkpoint·고정 Operation 계획 비교를 보강했다. 기존 ADR0092의18개와
새 전환11개를 같은 실제 Kubernetes/PG16/TLS MinIO·Mosquitto 환경에서 검증했다.

공개 Device fanout→STREAM Task2→BATCH child에서 출발했다. source2개의 실제 부모/자식
종료를 확인한 뒤 successor2개를 실행했다. 선택한 작업은 다른 실제 NODE, peer는 원래 AUTO
배치를 사용했다. 하나의 successor는 정확한 Job UID만 기록된 claim 전 이력이다.
최종 namespace 차단과 실제 종료 증거는 컨테이너 부모/자식4쌍을 포함한다.

업무 상태·claim·전환/member·checkpoint receipt는 명시적 SQL fixture이며 DB 제약과 불변
trigger를 활성화했다. 실제 Runner offload 요청/handshake를 수행했다고 주장하지 않는다.
기존5개와 전환7개, 이력을 만드는 중간1개까지 PostgreSQL 복원13개를 사용했다. 원본 DB와
원본 MinIO를 종료하고 replica의 고정 checkpoint2개를 확인했다.

새11개가 확인한 내용:

- source 종료 후 실제 successor 생성, 고정 member/checkpoint2개와 source→target 관계.
- DRAINING/STARTING 기한 전은 변경0이며 원래 기한과 배치를 보존했다. 새 Attempt는 없다.
- Job UID가 기록되지 않은 target 하나면 전체 그룹을 미해결로 남겼다. 복구 명령은 그 Job을
  채택하거나 claim을 만들지 않았다. 별도 fixture로 기록을 복구한 뒤 최신 Attempt가 추가되면
  이전 전환으로 덮어쓰지 않았다.
- 시작 기한은 target 생성 시각 + 원래 timeout과 맞아야 했다. Operation만 바뀌어도 기존
  plan을 거절했다. 같은 그룹의 겹친 활성 Operation은 부분 처리하지 않았다.
- 실제 SQL 마지막 오류는 Operation·선택한 target 실패·peer와 BATCH child 변경을 모두
  rollback했다.
- 두 기한 만료는 선택 Task를 FAILED, peer와 child를 SKIPPED로 만들었다. 원본 OFFLOADED
  Attempt는 유지했고 STARTING의 선택 target에만 TARGET_START_TIMEOUT을 기록했다.
  새로운 장애 retry나 Attempt를 만들지 않았고 반복 실행은0변경이었다.
- 종료 대기·시작 대기 취소2건을 확정했다. 시작 대기의 실제 COMMIT 응답 유실도 재실행으로
  확인했고 원래 source/member/checkpoint·목적지·기한을 보존했다.

Operation 실패2/취소2, pending2·미해결1을 확인했다. 실제 고정 S3 version2개·bytes/SHA,
기존18개의40테이블 보존과 namespace/DB/API/MinIO/broker/MQTT client/잠금 프로세스 정리는
모두 PASS다. 모든 전환 복원본에서 원래 source Attempt/runtime·member·checkpoint의 전체
행을 대조했다. 만료/취소 전후 Operation은 state/failure_reason/updated_at 외에 어떤 필드도
변경되지 않았으며 request digest·배치·원래 기한·source/target 연결·생성 시각을 보존했다.

최초 `162625Z-ae856e8f`는 시험 fixture가 기존 DISPATCHING Attempt를 종료하지 않고 새
QUEUED Attempt를 추가해 `task_one_active_attempt` 제약으로 실패했다. 원래 Attempt를
실패로 기록한 뒤 새 시도를 만드는 순서로 수정했고 제약은 완화하지 않았다. 소유 자원
정리를 확인한 후 위29개를 다시 통과했다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`, V1–V34와
Runner·기존 BATCH/Remote 복구 코드는 이번 변경에서 불변이다. ADR0092의69개·Remote15/15
근거를 재사용한다. CI kind의 동일 gate에 `--offloads`를 추가하고 복원 DB/실제 자원 증가에
맞춰 해당 단계 제한을15분으로 설정했다. CI 전체 제한과 다른 게이트는 유지한다.

VD/자동 전환의 실제 복원 종단, 기록된 target 실패·시작 성공 권한, finalization, 전역 writer
회수·새 자격·종합 활성화와 전체 M5 잔여/M7–M10 수용은 남는다. activated와 전역 종료
플래그는 false이며, 새 CI/배포 결과는 이 로컬29개와 구분한다.
