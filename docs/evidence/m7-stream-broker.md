# M7 실제 broker 권한 발급·회수 검증

2026-10-03 KST. ADR0024의 Java gateway를 실제 별도 Mosquitto2.0.18 Dynamic Security 프로세스에
연결했다. 이 기록은 DB worker·인증된 배정·Runner workload 전체 연결 완료가 아니다.

## 검증 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 TLS broker 기본6개 | 20261002T222841Z-38a798aa | PASS/0 |
| 최종 TLS broker7개·시간 제한/소켓 정리 포함 | 20261002T223028Z-b2683773 | PASS/0,7 tests/실패0/25.617초 |
| 실제 DB→broker→세대 전환 포함 최종8개 | 20261002T223803Z-1b8b443c | PASS/0,8 tests/실패0/33.011초 |
| 기존 단위/MVC82개 | 20261002T223257Z-f40362da | PASS/0 |
| 기존 전체 PostgreSQL148개 | 20261002T223523Z-aee55d73 | PASS/0 |
| OpenAPI4개 타입/패키징·MVC25개 | 20261002T223950Z-f663f7da | PASS/0 |

실제 Java MQTT client로 관리 명령을 보내고 별도 producer/consumer 연결로 payload가 도착하거나
거절되는지를 확인했다. 메모리 mock broker나 수동 ACL 파일로 gateway의 발급을 대체하지 않았다.

- 반복 grant/revoke의 멱등 확인과 이미 연결된 client의 회수 후 데이터 차단.
- 두 Device의 서로 다른 데이터를 같은 consumer에 전달, 다른 경로/wildcard/관리 topic 권한 거절.
- 한 경로 회수 후 다른 경로의 데이터 전달 유지.
- 발급 전 회수, 발급 후 회수, broker SIGKILL/재시작 뒤 양쪽 빈 권한 이력 보존·늦은 발급 거절.
- 동시에 발급3개/회수1개를 실행하고 모든 호출 후 재발급이 계속 거절되는지 확인.
- 다른 policy digest, 만료 lease, 실행 중/재생성된 adapter의 파생 키 변경, 공개된 key 파일 거절.
- 신뢰하지 않는 CA·다른 hostname·잘못된 관리자 비밀번호 거절.
- TLS 연결이 응답하지 않을 때8초 미만에 종료하고 실제 `/proc/self/fd` socket 수가 증가하지 않음.
- 실제 PostgreSQL의 DataRouteService로 세대 준비→broker grant→활성화 후 데이터를 전달한다.
  Device 새 session을 열면 이전 DB 권한이 차단되고, broker revoke 확인 전 다음 세대는 거절된다.
  회수 확인→같은 논리 route의2세대 생성 후 새 producer 데이터만 실제 수신하며 이전 producer의
  새 topic 발행은 차단된다. Task RUNNING만 명시적 fixture이며 Pod/Runner 계산·인증 배정은 미포함이다.

마지막 실행 뒤 시험 소유 broker 잔여0개를 `/proc`에서 별도 확인했다.
fixture는 임시0700 디렉터리/0600 비밀번호·키를 사용하고 비밀번호를 argv/로그로 출력하지 않는다.
주체 자격의 toString은 redacted이며 예외는 고정된 이유만 출력한다.

## 확인한 실패와 수정

첫 실제 실행 `20261002T222535Z-12388456`은6개 중2개가 실패했다. broker 원본 clients.c에서
동일 역할 재연결을 Internal error로 돌려주는 경로를 확인했다. 기존 연결은 변경하지 않고,
경합으로 중복 응답이 오면 역할/priority를 다시 읽어 원하는 상태인지 확인하도록 수정했다.
수정 후6개와 보강한7개가 통과했다.

`20261002T222701Z-5635d1d5`는 runtime dependency lock 미갱신으로 시험 실행 전에 실패했다.
compileTestJava의 write-locks만으로 runtime configuration을 해결하지 못했으므로 app/adapters의
전체 dependencies를 resolve하여 lock을 갱신했다. 두 lockfile 변경은 Paho1.2.5 한 항목뿐이다.
최종 시험은 write-locks 없이 기존 고정 lock을 사용했다. 초기 Java toolchain 환경/배열 닫힘/
java.security 이름 충돌 컴파일 오류도 실제 시험 전 수정했다.

## 재현과 범위

`bash scripts/test-stream-broker.sh`는 로컬 .env/실제 PostgreSQL, Python3/OpenSSL과 실제 mosquitto/mosquitto_ctrl/dynamic-security
plugin을 요구하며 없으면 성공으로 건너뛰지 않는다. 비표준 설치는 아래 경로 환경 변수로 지정한다.

- `EDGEAI_MOSQUITTO_BINARY`
- `EDGEAI_MOSQUITTO_CTRL_BINARY`
- `EDGEAI_MOSQUITTO_DYNAMIC_SECURITY_PLUGIN`

GitHub scaffold job에도 PostgreSQL 기동 후 전용 broker를 설치하고 같은 검증을 수행하도록 추가했다.
운영 클러스터의 기존 MQTT나 공유 설정을 변경하지 않았다. 관리 자격의 배포, 영속 broker 설정,
DB의 권한 상태와 broker를 연결하는 worker·실제 lease 준수·인증된 배정·Runtime 실행·공개 STREAM과
S3 checkpoint/새 Pod 복원은 남는다. 현재 API와 Swagger에는 새 공개 endpoint가 없다.

## 후속 확인

소스9d8f89b의 CI37074435731 scaffold job에서 실제 broker 시험
`20261002T224921Z-f0f86af3`의 PASS/0과 BUILD SUCCESSFUL 로그를 내려받아 확인했다.
동일 artifact의 결과 JSON9개 모두 PASS/0이며 runner/storage job도 성공했다.
후속으로5개 job 모두 success와4개 artifact의 결과 JSON17개 모두 PASS/0을 확인했다.
실제 kind `20261002T225916Z-070e66ce`는 BATCH/Remote/VD 실행·API 재시작·교체/취소·
S3 결과20+5개 검증·자원 정리와 최종 Pod Ready를 통과했다.
GitOps aab300d의 실제 배포도 `20261002T232338Z-64c92ed7` PASS/0이다. 소스9d8f89b의
정확한 API/Dashboard/MinIO imageID·Ready·PVC Bound·Argo revision/Synced를 확인했다.
기존 공유 Ingress 상태 때문에 aggregate health는 Progressing이다. 새 worker 소스 배포 증거와 구분한다.
후속 DB worker의 구현/로컬 검증 범위는 [별도 기록](m7-stream-worker.md)을 따른다.
