# M7 Runner 최종 상태 복구 검증

2026-10-03 KST. [ADR0040](../adr/0040-stream-finalizer-recovery.md).
구성 요소 검증이며 전체 M7 완료나 공개 STREAM 활성화를 뜻하지 않는다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 서버 완료 허가·경로 종료·취소/producer 만료 | 20261003T082217Z-b32536f7 | PASS/0, 실제 PG10개 |
| checkpoint HTTP client·정확한 finalized receipt | 20261003T082217Z-28d0e6a8 | PASS/0, 14개 |
| 전체 Runner/SDK | 20261003T082818Z-31ac4009 | PASS/0, 108개·91.295초 |
| 전체 HTTPS/TLS MQTT·모델·Runner·장치 | 20261003T082816Z-c94e4e70 | PASS/0, 70개·132.657초 |
| 실제 Spring/S3/TLS·Runner 복구·취소 | 20261003T084614Z-4b93f53b | PASS/0, 2개 |
| 실제 PG/S3 전체 Result·Remote·VD·stream | 20261003T084717Z-d33a29a6 | PASS/0, 32개·실패/skip0 |
| 전체 서버 단위/MVC·실제 claim 변환기 회귀 | 20261003T084815Z-a994b444 | PASS/0, 98개·실패/skip0 |
| OpenAPI5종·원본/JAR 일치·MVC/Swagger | 20261003T084903Z-3a598cb8 | PASS/0, MVC26개·실패/skip0 |
| 최종 전체 PostgreSQL 회귀 | 20261003T085225Z-0b0c69da | PASS/0, 164개·실패/skip0 |
| 실제 API/UI·PC/모바일·Swagger12개 | 20261003T085324Z-e19028d2 | PASS/0, 10개·52.9초 |

Swagger의 완료 후 복구 역할·현재 producer 조건·409 설명을 PC/모바일에서 펼쳐 확인했다.
원본과 제공 계약의 일치, CSRF 자동 연결, 외부 요청/브라우저 오류 없음, 가로 넘침 없음도 검사했다.
같은 실행 디렉터리의 `swagger-desktop.png`, `swagger-mobile.png`를 직접 열어12개 operation의
한국어 설명과 모바일 줄바꿈을 확인했다. 적용된 V1–V24의 원본 bytes는 모두 그대로다.

## 실제로 확인한 복구

Python Runner 시험은 두 입력의 합14와 마지막 END/ACK를 외부 checkpoint로 봉인한다.
HTTPS 서버가 허가를 기록한 뒤 응답을 보내기 전에 Runner를 SIGKILL한다. 원래 작업 볼륨을
삭제하고 peer Link와 실제 broker를 정지한 다음 새 빈 디렉터리에서 Runner를 재시작한다.
최종 상태 복구·파일 생성·단 한 번의 Result commit을 확인한다. MQTT 배정 요청, stream journal,
지속 모델 재실행이 없다. 잘못된 S3 bytes, 논리 포트 교환, S3 읽기 중 취소는 상태 파일이나
최종 artifact를 만들지 못한다. 이 시험의 제어 서버와 저장소 응답은 명시적 HTTPS fixture다.

`StreamSourceCompletionIntegrationTest`는 실제 Spring 서버·격리 PG·고유 versioned MinIO와
TLS Mosquitto를 사용한다. 별도 TLS proxy가 실제 API와 S3로 요청을 전달하며 응답을 만들지
않는다. 원래 signed Host/path/query를 유지하고 CA/hostname 검증을 켠다. 운영 TLS 배포는 아니다.

두 DeviceSource가4,2와5,3 및 END를 보내고 실제 Session 모델이14를 계산한다. 실제 S3
checkpoint를 확정한 후 장치들은 Task 보고까지 WAITING이다. 공동 허가를 받은 뒤 Session을
닫고 경로를 fence한다. 실제 권한 worker의 broker 회수와 closedAt을 기다린 후 독립 Runner가
새 빈 디렉터리에서 실제 `/claim`·FINALIZE·고정 S3 다운로드·허가 재조회·최종 파일 subprocess·
S3 업로드·Result commit을 수행한다. 서버 Run SUCCEEDED와 결과14를 확인한다.
장치 journal 재시작도 MQTT 없이 완료 허가를 다시 조회한다. 취소 경로에는 허가가 없다.

Pod 생성/신원과 이미 실행 중인 runtime은 fixture다. peer가 경로 회수를 시작하는 사건도
시험이 제공한다. 공개 Run 생성·그룹 기동·새 Pod/Attempt 전환이나 전체 Kubernetes 스트림
수용시험으로 간주하지 않는다. 현재 Attempt/epoch/Pod/runtime의 unexpired producer만 복구한다.

## 실패에서 확인한 수정

- `084054Z-6b9a841d`, `084223Z-4af8e8d5`, `084358Z-cfe4f23b`: 실제 Runner의 제어 요청 거절을
  안전한 오류 코드로 좁혀 STREAM claim의 HTTP400 INVALID_RUNNER_REQUEST를 확인했다.
- `084515Z-7d18dcc7`: 기존 claim 시험을 컨트롤러와 같은 bounded canonical JSON 변환기로
  바꿔 `unsupported JSON value`를 재현했다. 응답의 StreamExecutionSpec record가 원인이었다.
  명시적인 JSON map으로 변환한 후 동일 실제 서버 시험과 전체98개 단위시험이 통과했다.
- 초기 Runner 단독12개는 강제 종료한 HTTPS client의 예상 SSLEOFError를 시험 서버가 출력했다.
  연결 종료 예외를 fixture에서 처리한 뒤 전체70개를 다시 실행해 traceback/ResourceWarning 없이 통과했다.

## 선행 코드의 CI·배포

DeviceSource source4d2ad11의 CI37108841332는5 jobs success, 다운로드한 결과JSON17개 모두
PASS/0이다. Runner 컨테이너107개081151Z-33d36996, TLS MQTT66개081350Z-0ba1a27d와
kind082409Z-6917e34b의 실제 BATCH/Retry/Offload/TLS Remote/VD 및 고정 S3 결과20+5를 확인했다.
시험 소유 cluster edgeai-ci-73d3cef779f0의 삭제도 확인했다.
GitOps d4b8044·배포084849Z-3dba6364에서 정확한 API/dashboard/MinIO imageID·Ready·PVCBound·
ArgoSynced·VD활성화PASS다. 공유 Ingress로 aggregate health는Progressing이다.
이는 후속 ADR0040 복구 변경의 CI/배포 완료 근거가 아니다.

공개 STREAM501과 M5 잔여/M7–M10 미완료를 유지한다.
