# ADR0020 — VD poll 배정·자식 Runner·종료 확인

상태: 구현·로컬 수용 검증 완료, 새 이미지 CI·배포 검증 중. ADR0019의 실행 연결이며
검증 범위는 `docs/evidence/m6-vd-task-execution.md`를 따른다.

VD 행 → Run 행 잠금을 유지하며 poll receipt, 배정과 종료 보고를 같은 트랜잭션에서 처리한다.
같은 요청 순번의 재전송에는 최초 배정만 재구성한다. 신규 배정은 Ready·RUNNING 세대와
같은 SERVICE·namespace에서 빈 slot에만 허용한다. 요청 전체의 작업/epoch/session/세대/Pod
신원을 검사하고 실패하면 heartbeat 갱신도 롤백한다.

배정 응답이 유실된 직후 취소되면 supervisor가 실행하지 않은 작업의 취소 UUID를 받을 수 있다.
supervisor는 응답 처리 뒤에만 순번을 올리고, 시작한 작업은 active 또는 미확인 completed에
보존한다. 따라서 다음 순번에서 양쪽에 없고 아직 claim하지 않은 배정만 NOT_STARTED로
종료할 수 있다. 최초 배정 순번의 재전송만으로 미시작을 추정하지 않는다. V17은 이 사유와
엄격히 증가한 확인 순번을 보존하며 exitCode를 만들지 않는다. 적용한 V17의 제약 이름 오류는
새 V18로 보정했다. V1–V18 적용본은 변경하지 않는다.
빈 DRAIN에서도 supervisor는 poll을 계속하고 서버의 STOP을 받아 종료한다. 초기 배정 응답이
유실된 경우 다음 순번의 미시작 보고를 보내기 전에 스스로 종료하지 않도록 한다. 기존 lease와
drain deadline은 이 확인 대기의 상한으로 유지한다.

VD child Runner는 기존 작업 HMAC과 VD Pod-bound token을 함께 제공한다. 실제 gateway 신원과
배정의 supervisor·generation·session·Pod·Node를 대조한다. 작업의 claim/upload/commit/telemetry는
Task/Run 활성 상태와 VD lease·drain deadline을 다시 확인한다. S3 검증은 트랜잭션 밖이며 commit
시 재검사한다. 결과에는 실제 VD 배정의 vdRuntimeId를 저장한다.

Result 확정과 취소 요청은 slot을 반환하지 않는다. 인증된 프로세스 종료 보고, 위 미시작 증명,
또는 실제 supervisor Pod 종료가 확인돼야 반환한다. exitCode=0인데 Result가 없으면
RESULT_MISSING 실패다. VD 교체는 원본 세대의 실제 종료와 배정 정리를 마친 뒤 새 세대를 만든다.
한 작업 취소는 공유 Pod를 삭제하지 않는다. 배정 대기·실행·VD lease 만료를 worker가 확인하고,
retry는 기존 종료 확인 후 같은 VD 대상에 새 Attempt로 대기한다.

VD의 CPU/GPU/메모리는 공유 컨테이너 자원이다. telemetry 수집은 가능하지만 작업별 독점
자원 가정의 자동 offload 평가에는 VD를 포함하지 않는다.
