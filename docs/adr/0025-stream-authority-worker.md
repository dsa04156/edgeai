# ADR0025 — 영속 세대 상태에서 broker 권한을 조정하는 worker

상태: 구현·실제 PostgreSQL/TLS broker 통합14개 검증 완료. 새 CI·운영 배포 검증은 별도 후속이다.
[검증 범위와 근거](../evidence/m7-stream-worker.md)를 따른다.

ADR0023의 PREPARING/FENCED 상태 자체를 영속 명령으로 사용한다. 발급은 정확한
BrokerReceipt 확인 뒤 ACTIVE, 회수는 확인 뒤 CLOSED로 전환한다. 별도 메모리 큐의 유실이나
API 재시작으로 명령이 사라지지 않으며 동일 세대의 재전송은 ADR0024의 멱등 역할/회수 이력으로 처리한다.

## 조정과 네트워크 분리

단일 스캔 스케줄러는 현재 broker digest의 열린 세대를 UUID keyset으로 순환한다. 페이지 앞부분에
실패/활성 작업이 오래 남아도 뒤쪽 세대의 DeviceSession/Attempt·Run·lease 검사를 계속한다.
별도의 명령 cursor는 아직 제출하지 못한 후보 앞에서 멈추며 완료되지 않는 첫 요청의 재시도가
뒤 요청을 계속 밀어내지 않도록 한다. V20은 닫히지 않은 broker/id 조회의 partial index만 추가한다.
적용된 V1–V19는 변경하지 않는다.

네트워크 실행은 기본4개, 최대16개로 제한한다. permit을 획득한 요청만 executor에 제출하므로
실행 중·대기 중 작업 합계도 같은 한도다. 같은 프로세스는 세대별 중복 제출을 막고, 복제본끼리의
중복은 broker의 멱등 명령과 DB 상태 재검사로 처리한다. DB 트랜잭션/행 잠금 중 broker I/O는 없다.
발급 전후에 현재 주체·취소·lease를 다시 확인하고, 발급 도중 fence된 세대는 활성화하지 않고 회수한다.

UNAVAILABLE은 일부 명령 또는 전체 발급/회수 후 응답 유실일 수 있으므로 상태를 성공으로
추정하지 않는다. PREPARING/FENCED를 유지하고 다음 순환이나 새 worker에서 재전송한다.
발급의 영구 충돌·잘못된 응답·설정 오류는 FAILED로 fence하며 회수 성공 전에는 닫지 않는다.
반환 receipt는 현재 명령의 세대/정책/설정과 모두 일치해야 한다. 외부 예외 메시지/자격은 로그에 남기지 않는다.

## 설정과 한계

`EDGEAI_STREAM_ENABLED`는 기본 false이며 별도 StreamConfiguration이 broker·scheduler·worker를
구성한다. 실제 발급에는 TLS broker/CA·관리 비밀번호 파일·별도 HMAC 키·고정 broker digest가 필요하다.
해당 digest는 외부 설정의 신뢰된 식별자이며 임의 교체를 자동 감지하는 broker attestation이 아니다.
다른 digest의 열린 세대는 이 worker가 회수했다고 처리하지 않는다. 기존 설정으로 권한을 회수한 뒤
설정을 전환하거나 기존 broker를 담당하는 worker를 유지해야 한다.

네트워크 각 연결/요청에는 adapter의 시간 제한이 있지만 전체 grant에는 여러 요청이 있다.
이 worker는 브로커 ACL의 정확한 lease 시각 만료를 보장하지 않는다. API/broker 장애 중에도 실제
producer/consumer가 배정 lease를 지키는 연결은 별도로 필요하다. 서버를 중단하면 실행 중 I/O를
interrupt하고 최대5초 대기하며 미확정 명령은 DB 상태에서 재개한다.
공개 STREAM 실행·인증 배정·Runner workload·S3 checkpoint/새 Pod 복원·운영 broker 배포는
아직 이 범위에 포함하지 않는다. 동작 연결 전까지 공개 STREAM501을 유지한다.
