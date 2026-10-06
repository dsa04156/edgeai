# M7 양쪽 생존 확인·스트림 기한 갱신 검증

2026-10-03 KST. [ADR0028](../adr/0028-stream-bilateral-heartbeat.md)의 Device/Runner heartbeat를
실제 Spring HTTP·PostgreSQL·Python SDK·TLS Mosquitto·SQLite에 연결했다. Kubernetes Pod 신원
확인은 이 시험에서 명시적 RuntimeGateway fixture이며 운영 Runner/Pod 수용과 구분한다.
공개 STREAM 실행은 아직501이다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 V21 적용·기존196개 보수적 backfill·누락0 | 20261003T010944Z-16c0942c | PASS/0 |
| Swagger4 API·실제 비활성 POST·PC/모바일 CLI | 20261003T011217Z-d94e88f8 | PASS/0 |
| 실제 인증/DB/TLS broker·Python 계산/갱신·기존 worker | 20261003T010054Z-7106bd67 | PASS/0,22개·실패/skip0 |
| 실제 PostgreSQL 전체·새 heartbeat/동시성/제약5개 | 20261003T010310Z-bf5a3d4d | PASS/0,153개·실패/skip0 |
| OpenAPI5개·타입/패키징·MVC | 20261003T010615Z-0e071536 | PASS/0 |
| 실행 JAR 빌드 | 20261003T010650Z-d61824e4 | PASS/0 |
| 단위/MVC | 20261003T010401Z-f3868cd1 | PASS/0 |
| SDK 실제 HTTP·엄격한 응답·인증·기한10개 | 20261003T005832Z-8d5f769f | PASS/0 |
| 실제 TLS MQTT·live refresh·만료·기존 전달/복구14개 | 20261003T005832Z-c0c3a37b | PASS/0,17.432초 |
| Runner·VD·journal·SDK 전체60개 | 20261003T005931Z-72af01a9 | PASS/0,69.941초 |

## 실제 확인한 동작

- Spring이 실제 발급한 Device/Runner 배정으로 Python SDK가 TLS MQTT에 연결했다. 실제8초
  원래 기한을 넘길 때까지 양쪽 heartbeat로 같은 Link를 갱신한 뒤4+5를 계산하고 SQLite 상태9와
  처리 확인 뒤 source outbox 정리를 확인했다. MQTT 비밀번호는 journal에 없었다.
- 실제5초 route에 Device만 heartbeat를 보내면 처음 기한이 그대로다. 만료 뒤 실제 스케줄러가
  broker 권한을 회수하고 generation을 CLOSED로 만들며 양쪽 heartbeat 모두409로 거절됐다.
- PG 시험은 제어 시계 offset을 사용해 양쪽 관측 중 오래된 시각+고정 window만 기한으로 쓰는
  것을 검증했다. 순번0과 마지막 순번의 재전송은 관측을 바꾸지 않는다. 동시8개 동일 요청은
  관측1개가 되고 새 서비스 객체에서도 같은 영속 순번을 읽는다. 오래된/건너뛴 순번은409다.
- 다른 장치/세션/Attempt, 취소·교체·만료된 세대는 관측 전에 거절했다. 잘못된 Runtime Pod를
  가진 직접 서비스 호출도 heartbeat를 저장하지 못한다. 기존 VD lease/drain 검사는153개
  회귀에 포함되며 이 결과를 실제 VD Pod 스트리밍 수용으로 확대하지 않는다.
- DB는 window/세대 변경, 양쪽 동시 변경, 순번 건너뛰기, 관측 시각 역행, 만료·fence 뒤 갱신과
  삭제/TRUNCATE를 거절한다. V21이 기존 generation을 보수적인5초 window로 채우고 새로운
  generation은 INSERT trigger에서 원래 기한 길이를 마이크로초 정수로 기록한다.
- SDK는 실제 HTTP 경로·두 인증 자격·resume/retry와 응답 순번 일치를 검사한다. 실제 TLS
  socket에서 같은 연결의 기한을 갱신해 데이터를 처리했다. 이 시험의 monotonic 시계만
  fixture다. 다른 세대/주체/규격/자격/clock을 거절하고 VD drain처럼 짧아진 기한을 적용한다.
  이미 만료된 연결의 refresh는 socket을 닫고 journal 쓰기도 거절한다.

V1–V20은 HEAD와 byte 비교로 변경 없음을 확인했다. 로컬 실제 적용한 V21은 이후 수정하지 않는다.
V21 SHA-256: `50ab5af4b00dc84ed0f9bb49fdd378be592290279aa1d93629621a5d5a8b3ac9`.

## 실패·교정과 경계

`20261003T005551Z-660da92c`는 로컬 Mosquitto/control 실행 경로가 빠져 fixture 시작에 실패했다.
`mosquitto_ctrl` FileNotFoundError를 확인하고 기존 로컬 바이너리 경로로 재실행했다.
스크립트에 실행 파일·dynamic-security plugin 사전 확인을 추가했다.

`20261003T005813Z-89f4bce5`는22개 중1개 실패했다. 인증 실패401의 빈 본문을 새 시험 helper가
JSON으로 읽은 것이 원인이었다. 기존 Device helper처럼 빈 본문을 처리하도록 시험만 수정했고
최종22개 전체를 다시 통과했다. 인증 거절이나 운영 응답을 완화하지 않았다.

선행 실제 Spring→Python probe는 `20261003T003709Z-77b22cc7` PASS였다. 최초 probe의
`20261003T003221Z-dbb01455`, `20261003T003431Z-b0bd5a03`, `20261003T003555Z-c3c42d27` 실패는
Journal.pending이 route마다 첫 미처리 frame 하나를 반환하는데 두 개를 동시에 기다린 시험
가정 때문이었다. 순서대로 처리하게 수정했다. 이번22개에는 기한 갱신을 추가한 후속 probe가 포함된다.

heartbeat는 관리 경로의 생존 확인이며 계산 진행·외부 checkpoint 내구성의 증거가 아니다.
실제 Runner 계산 프로세스 watchdog/스트림 SERVICE 인터페이스, 운영 broker, S3 checkpoint와
새 Pod 복원, 공개 API/화면·실제 Kubernetes 다중 장치 DAG 및 M8–M10은 남아 있다.
기본 스트림 worker/binding 활성화 설정은 변경하지 않았다.

재현은 실제 PostgreSQL, Mosquitto/control/dynamic-security와 고정 Paho 환경을 준비한 뒤
`EDGEAI_STREAM_PYTHON`을 해당 Python으로 지정하고 `bash scripts/test/test-stream-broker.sh`를 실행한다.
새 Spring→Python probe 때문에 CI scaffold도 해시 고정된 `runner/requirements-stream.txt`를 설치한다.
기존 `test-integration.sh`, `test-unit.sh`, `test-contract.sh`, `test-runner.sh`, `test-stream.sh`를 사용한다.

## Swagger와 선행 SDK의 CI·배포

실행 JAR의 스트림 문서는 배정2개와 heartbeat2개를 표시한다. 한국어 설명에서 순번0·재전송·
409·양쪽5–120초 갱신 규칙을 확인했고, 실제 비활성 heartbeat POST는403이었다. 이 요청은
관리 CSRF를 조회하지 않았다.1280×960/390×844 화면과 펼친 상세를 직접 확인했다. 가로 넘침과
page error·브라우저 저장소 자격은 없었으며 의도한403과 favicon404는 HTTP 오류로 구분한다.
CLI가 요구한 Chromium 버전이 없어 최초 시작에 실패한 뒤 설치된 Chrome으로 실행했다.
`output/playwright/heartbeat-docs-{desktop,mobile}[-detail].png`에 화면이 있다. 시험 API·브라우저를
종료하고 임시 자격 설정 파일을 삭제했다.

인증 배정337abb3 CI37080508316은5 jobs·JSON17개와 실제 kind 수용을 통과했다.
배포 확인003910Z-958426b8은 제한 시간 내 준비 조건을 충족하지 못해FAIL이었다. 후속 직접
조회에서 Synced/Ready를 확인하고 재검증004753Z-3cf42e14가 source337의 정확한 이미지·PVC와
Argo revision76651cd를 확인했다. 이를 source766 이미지 배포로 취급하지 않는다.

후속 SDK76651cd CI37082953978은5 jobs·JSON17개 PASS/0이며 내려받은 실제kind
004905Z-820260c5 로그에서 BATCH/Remote/VD·복구, S3 결과20+5개와 생성한 클러스터 삭제를
확인했다. GitOps9171827와 source766의 정확한 imageID/Ready·PVCBound·ArgoSynced는
011108Z-5d82c84b PASS/0이다. 전체 Argo health는Progressing으로 남는다.
이 선행 CI·배포는 이번 V21/heartbeat 변경의 신규 CI·배포 완료 근거가 아니다.

## 후속 heartbeat CI·배포 확인

소스77b687a의 CI37085573042는5 jobs 모두 success다. 내려받은 결과JSON17개가 PASS/0이며,
실제kind012948Z-9977e79e 로그에서 BATCH/Remote/VD·복구·S3 결과20+5개와 생성한 클러스터
`edgeai-ci-20d7ff7b87b8` 삭제를 직접 확인했다. GitOps19d7cec의 실제 배포는
20261003T015434Z-4112045e에서 source77b687a의 정확한 API/dashboard/MinIO imageID,
Ready·PVCBound·ArgoSynced·VD 활성화를 확인했다. 전체 Argo health는Progressing이다.
이후 지속 계산 subprocess 연결은 [별도 검증](m7-stream-workload.md)을 따른다.
