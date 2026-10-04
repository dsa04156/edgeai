# 복원된 STREAM 최종 저장 재시도

ADR0097의 `--finalizers`를 실제 PostgreSQL 복원본, TLS MQTT/S3와 Kubernetes의
보존한 producer 종료 증거에 연결했다. API는 source60c8be3의 패키징 JAR이고,
Runner는 해당 소스의 검증된 native index8a2f8067이다. 업무 상태와 runtime binding은
명시적 fixture이며 실제 모델 추론이나 전체 서비스 재가동 수용을 뜻하지 않는다.

`20261004T184620Z-1eb595e3`의 기본26개는 PASS다. 기존18개와 새8개를 포함한다.
실제 복원 DB7개, 독립 parent/child 컨테이너2개, 고정 checkpoint/S3 version2개를 사용했다.
소유 API/DB/브로커/스토리지/연결/namespace 정리가 모두 확인됐다.

- 명시적 옵션 없이 일반 STREAM 그룹 재시도로 우회하지 못한다.
- 봉인된 두 작업의 서로 다른 원래 기한과 backoff를 유지하고 새 grant/Attempt를 만들지 않는다.
- 기한/backoff 변경, 그룹 재시작 사유, 최신 Attempt 불일치는 쓰기 전에 거절한다.
- 계획 이후와 최종 관측 이후의 실제 DB 변경은 guard로 거절한다.
- 트랜잭션 말미 오류는 기한 만료·peer/후속 작업 변경을 모두 원복한다.
- 만료한 finalizer 하나만 실패 처리하고 미완료 peer와 BATCH 후속 작업을 건너뛴다.
- 기록된 취소의 실제 COMMIT 응답 유실 뒤 같은 복구를 다시 관측할 수 있다.
- 원래 실패한 Attempt, 완료 권한, checkpoint, 고정 S3 bytes/SHA와 이력을 보존한다.

최종 결합 시험 `20261004T185953Z-272d4e46`은 기존 전환·기록된 target 실패까지 포함한
44개 PASS다. 실제 복원 DB18개, parent/child4쌍과 고정 checkpoint/S3 version2개를
검증했다. 새 finalizer8개는 만료1개, 새 Attempt0개와 원래 이력 보존을 확인했다.
만료 처리 자체의 실제 COMMIT 응답 유실 뒤 DB 재관측과 멱등 재실행도 통과했다.
소유 API/DB/브로커/스토리지/연결/namespace 정리가 모두 확인됐다.
CI의 kind 경로에 `--offloads --finalizers`를 연결했다. 이 변경의 원격 CI/배포는 아직 미검증이다.

첫 결합 시도 `20261004T183922Z-a3a941ca`는 S3 복제120초 제한에서 실패해 업무 복구
검사에 도달하지 못했다. 소유 자원은 모두 정리됐다. 같은 MinIO 바이너리의 독립2객체
복제 시험 `20261004T184420Z-a979e30b`와 위26개 재실행은 통과했다. 두 번째 결합 시도
`20261004T185026Z-d1e22d8b`도 같은 복제 대기에서 실패했다. 이후 별도 소유 서버에서
scanner 의존성을 재현해 ADR0098의 명시적 resync를 적용했다. 제한 시간·검증 조건을
유지한 최종44개가 통과했다. 앞선 실패 각각의 내부 스캔 경로까지 단정하지 않는다.
[복제 진단과 수정 근거](m9-storage-explicit-resync.md)를 따른다.

이 근거는 원래 grant가 있는 작업의 기록된 재시도 기한 정리다. 상속된 finalizer의 실제
새 Pod 실행, 미기록 출력 복구, 실제 외부 시작 권한 및 종합 재활성화는 별도 수용 대상이다.
