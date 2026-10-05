# ADR 0093: 복원 STREAM 전환의 취소·원래 기한 조정

상태: 채택. 2026-10-05.

후속 [ADR0108](0108-restored-stream-starts.md)이 아래 STARTING 기한 만료 추론을 대체한다.
시작 기록이 없으면 실패를 만들지 않고 미해결로 남기며, 모든 member의 원래 허가가
독립 기록으로 증명되면 전환 성공만 복원한다. 아래는 최초 구현과 당시 검증 기록이다.

ADR0092의 그룹 복구에 `--offloads`를 추가한다. 실행 중이던 전환을 복구할 때 원래
Operation과 task_offload_member의 전체 그룹·source/target Attempt·checkpoint·배치·기한을
검사한다. 실제 Kubernetes 종료와 원본 broker 차단은 전후에 다시 관측하며34개 테이블
guard/잠금과 복원 DB 신원 검사를 함께 사용한다.

그룹 전체와 정확히 일치하는 활성 Operation 하나만 처리한다. 누락/겹친 member, 부분 target,
더 최신 Attempt, 미확인 producer, 결과/완료 grant 충돌은 미해결이다. source는 OFFLOADED
이력을 유지하고 고정 checkpoint의 Task/Attempt/epoch/runtime/producer를 대조한다. peer의
NODE/AUTO/VD 배치와 선택한 작업의 목적지는 원래 계획을 따른다. 자동 결정은 Run의 원래
정책과 일치해야 한다. VD가 포함된 자동 전환은 기존 서버 계약대로 지원하지 않는다.

DRAINING은 source만, STARTING은 모든 successor가 기록돼야 한다. STARTING의 공유
기한은 같은 시각에 생성된 successor Attempt의 created_at + 원래 start timeout과 일치해야
한다. 기한 전에는 상태와 예산을 그대로 유지한다. 실제 실행이 있거나 파일이 있다는 이유로
전환 성공을 추정하지 않는다. 새 Attempt, runtime, generation, grant는 생성하지 않는다.

기한 만료는 기존 OffloadService 규칙을 따른다. 선택한 작업만 FAILED로 확정하고 같은
STREAM 그룹의 peer와 후속 작업은 UPSTREAM_FAILED로 건너뛴다. STARTING 만료는 선택한
target Attempt/runtime에 TARGET_START_TIMEOUT을 기록한다. DRAINING은 SOURCE_DRAIN_TIMEOUT을
Operation에 기록하며 원래 OFFLOADED Attempt는 보존한다. 별도 장애 retry로 바꾸지 않는다.
이미 기록된 사용자 취소 이유와 terminal 결과는 유지한다. 전파 중 미확인 실행·활성 전환·
열린 STREAM 경로를 만나면 transaction 전체를 중단한다. 미해결 그룹이 있는 Run은 완료로
변경하지 않는다.

CANCELLING은 그룹 전체의 기록된 취소를 확인하고 실제 producer/경로 종료 뒤 Operation과
Task를 취소로 확정한다. 기존 실패/전환 Attempt, checkpoint, member plan과 기한은 보존한다.
COMMIT 응답 유실은 완료로 추정하지 않고 격리·intent를 유지한 채 같은 UUID로 재관측한다.

원본 실행2개를 실제 종료한 뒤 successor2개를 만들고, 공개 Device fanout의 명시적 DB
binding/전환 이력을 여러 PostgreSQL 복원본으로 검증한다. 한 target은 claim 전 Job 증거를
사용한다. 합성 workload이며 실제 외부 모델 수용을 주장하지 않는다. 기존18개 회귀를 포함해
고정 S3 version과 소유 자원 정리까지 확인한다. 새 CI의 Compose PG17·packaged 이미지
검증과 새 실행 권한·종합 복구 활성화는 별도 남은 범위다.

실제 Kubernetes/PG16/TLS S3·MQTT의29개 결합 시험이 `163446Z-71726657`에서 통과했다.
실제 검증의 전환 대상은 NODE/AUTO이며 VD/자동 정책은 원래 이력의 검사 계약을 유지한다.
VD/자동 전환의 별도 복원 종단 시험, 기록된 target 실패·시작 성공 권한·finalization과 종합
활성화 수용은 후속이다. [검증 근거](../evidence/m9-recovery-stream-offloads.md)를 따른다.
