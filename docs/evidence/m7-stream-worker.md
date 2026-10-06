# M7 영속 권한 worker 검증

2026-10-03 KST. ADR0025/V20의 DB 상태→실제 TLS broker 발급/회수 경로를 연결했다.
이 기록은 공개 STREAM Run, 실제 Pod/Device 인증, Runner workload, S3 checkpoint 복원을
검증한 것으로 해석하지 않는다. 기존 `StreamBrokerIntegrationTest`에 실제 worker 시험을 추가했다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 DB/TLS broker·Spring scheduler·복구13개 | 20261002T225800Z-687911e6 | PASS/0,13 tests/실패0 |
| 최종 DB/TLS broker·두 worker 경합 포함14개 | 20261002T230042Z-e4ac4863 | PASS/0,14 tests/실패0/skip0,64.536초 |
| 기존 단위/MVC82개 | 20261002T230231Z-b93d413f | PASS/0,실패/skip0 |
| 기존 전체 PostgreSQL148개 | 20261002T230449Z-01449b89 | PASS/0,실패/skip0 |
| OpenAPI4개 타입/패키징·MVC25개 | 20261002T230604Z-eb6f8e54 | PASS/0 |

## 확인한 동작

- 실제 StreamConfiguration을 별도 Spring context에서 활성화했다. 수동 worker 호출 없이
  PREPARING→ACTIVE, MQTT payload 수신, Device 재접속→CLOSED, 다음 세대 발급 및 실제5초
  lease 만료→CLOSED·topic 권한 차단을 검증했다.
- 실제 broker가 grant/revoke를 처리한 다음 wrapper가 UNAVAILABLE을 반환하도록 응답 유실을
  주입했다. 첫 worker 종료 후에도 DB가 PREPARING/FENCED를 유지하며 새 worker가 원래 세대의
  명령을 멱등 재전송해 ACTIVE/CLOSED로 진행했다. 중간 실제 broker SIGKILL/재시작도 포함한다.
  이것은 worker 객체 교체 시험이며 API Pod SIGKILL/다중 Pod 배포 수용 시험은 아니다.
- 실제 grant가 적용된 뒤 반환을 latch로 지연했다. DB transaction 없이 I/O를 수행하는 것을
  확인했고, 지연 중 다른 DB 연결의 Device 세션 변경은2초 내 완료됐다. 다른 페이지의 두 경로는
  ACTIVE가 되고 이전 주체는 계속 FENCED됐다. 지연 응답 해제 뒤 활성화 없이 자동 회수됐다.
- 동시 실행 한도2를 실제 관측했고 이를 초과하지 않았다. 공개 Run 취소 서비스 호출 뒤
  해당 경로의 자동 회수도 확인했다.
- 가장 앞선 요청이 계속 UNAVAILABLE이어도 뒤 요청은 발급됐다. 페이지 크기2·동시 실행1의
  조건이며 다른 broker digest의 세대는 변경하지 않았다.
- 잘못된 세대의 발급 receipt는 대상 세대를 활성화하지 않고 FAILED로 fence/회수했다.
  잘못된 회수 receipt는 FENCED를 닫지 않았으며 정상 worker 재시도로 복구했다.
- 독립 worker 두 개가 동일 세대를 실제 broker에 동시에 발급하고, 두 응답을 지연한 동안
  Device 재접속을 수행했다. 늦은 응답은 ACTIVE를 만들지 않았고 CLOSED 뒤 새 세대만 데이터를
  전달했다. 두 worker가 새 Run 취소도 회수했다. 분산 네트워크 장애/실제 API 복제본 시험은 후속이다.

원래 broker8개 시험도 함께 재실행했다. 최종 JVM 종료 뒤 시험 소유 broker 프로세스 잔여0을
`/proc`에서 확인했다. V20은 실제 DB에 적용했으며 V1–V19는 HEAD 원본 SHA-256과 일치했다.
fixture의 세대 요청 UUID는 순서 검사를 위한 suffix와 시험별 무작위 prefix를 함께 사용해
영속 이력을 지우지 않고 같은 DB에서 재실행할 수 있다. broker digest도 fixture별로 구분한다.

## 남은 경계

worker는 기본 비활성이다. `.env.example`의 EDGEAI_STREAM 설정으로 별도 broker를 명시하며
공개 STREAM501은 유지한다. 실제 배정 전에 Device/Pod 인증, lease를 지키는 client,
운영 broker의 영속 설정/관리 자격·신뢰된 digest, Runner/SERVICE의 데이터 처리와
S3 checkpoint/새 Pod 복원, 공개 API/Swagger/UI, 실제 Kubernetes 다중 장치 흐름이 남는다.
새 worker 코드의 CI·배포는 이 로컬 시험만으로 통과했다고 판정하지 않는다.

## 후속 CI·배포 확인

소스 `cd61529`의 [CI37077442217](https://github.com/dsa04156/edgeai/actions/runs/37077442217)은
5 jobs 모두 success이며 내려받은 결과JSON17개가 PASS/0이다. stream broker14개는
`20261002T232538Z-dda0e317`, 실제 kind는 `20261002T233557Z-03e311f4`다.
실제 BATCH/Remote/VD 실행·복구·고정 S3 결과20+5개와 시험 클러스터 삭제 로그를 확인했다.
GitOps `2410f10`의 실제 배포 `20261002T235701Z-9ed76739`도 PASS/0이다.
API/Dashboard/MinIO imageID가 이 소스의 검증 이미지와 일치하고 Ready, PVC Bound,
Argo Synced 및 VD 실행 활성화를 확인했다. Argo 전체 health는 기존 Ingress 때문에 Progressing이다.
stream worker는 배포에서 기본 비활성으로 유지하며 운영 broker의 실제 활성 수용을 뜻하지 않는다.
인증 배정의 후속 로컬 구현·검증은 [별도 기록](m7-stream-bindings.md)을 따른다.

재현: 실제 PostgreSQL과 Mosquitto/OpenSSL을 준비한 뒤 `bash scripts/test/test-stream-broker.sh`.
기존 CI scaffold job의 같은 명령에 자동 포함된다. 비표준 broker 경로는
[broker 검증 문서](m7-stream-broker.md)의 환경 변수를 따른다.
