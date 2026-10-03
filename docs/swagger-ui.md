# Swagger UI와 CI/CD 연결 상태

Swagger UI 5.32.15 WebJar를 JAR에 포함하고 `/swagger-ui.html` → `/swagger-ui/index.html`로
제공한다. 문서 원본 `/openapi.yaml`은 Gradle processResources가 기존 계약 파일을 복사한다.
별도 annotation 기반 계약을 생성하지 않는다. 계약 검증 스크립트가 두 파일의 byte 일치를 확인한다.

Basic 인증과 CSRF 보호는 유지한다. OpenAPI의 POST security는 Basic + X-CSRF-TOKEN이며,
Swagger requestInterceptor가 쓰기 직전 실제 `/api/v1/csrf`를 호출해 세션 쿠키와 토큰을 연결한다.
원본 요청을 복사해 토큰을 추가하고 showMutatedRequest=false로 동적 토큰을 curl 표시에 넣지 않는다.
승인된 문서 서버는 상대 URL `/`로 현재 API origin을 사용한다. 다른 origin으로 보내는 요청은 거절한다.
Swagger 브라우저 인증 영속 저장과 온라인 validator를 끄고 모든 자산을 로컬 JAR에서 제공한다.

참고: [Swagger UI configuration](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/).

GitHub Actions CI는 연결되어 있다. push/PR 이벤트에서 scaffold와 storage job이 동작하며,
Swagger 실브라우저 시험도 기존 Profile 통합 브라우저 경로에 포함된다.
최초 Swagger 추가 시점에는 CD가 없었다. 이후 GitHub Actions/GHCR/ArgoCD 구성을 추가했으며,
현재 배포 연결·검증 결과는 [PROGRESS](../PROGRESS.md)와 [CI/CD 문서](cicd.md)를 따른다.


## 검증 (2026-10-02)

- `swagger-contract` / `20261002T013643Z-9a51c080`: PASS/0. 인증·자산·생성 타입·계약 byte 일치.
- `swagger-browser` / `20261002T013819Z-e005f614`: PASS/0. 실제 PostgreSQL/API에서
  desktop/mobile 각각 Swagger 화면 렌더, 자동 CSRF 발급과 POST201, 버전 GET200.
  외부 origin 요청 없음과 local/sessionStorage 비저장도 확인했다. 기존 Profile UI도 함께 통과했다.
- Dashboard lint/typecheck 및 Gradle test/bootJar/lock 갱신 성공.
- 최초 문서 fetch가 method를 생략하는 경우를 처리하지 못했던 오류는 GET 기본값으로 수정했고
  실브라우저 재검증을 통과했다. 최초 실패 기록 `20261002T013705Z-50688b1b`도 보존한다.
- Swagger desktop/mobile 스크린샷을 열어 실제 화면을 확인했다.

코드 fd729ca의 [GitHub Actions 36952012074](https://github.com/dsa04156/edgeai/actions/runs/36952012074)
scaffold/storage 모두 success다. 내려받은 결과 JSON 8개 모두 PASS/0을 확인했다.
위 검증 당시 테스트용 API/UI/DB 프로세스는 종료했고 CD 배포는 수행하지 않았다.

## API별 역할 설명

각 operation에 한국어 역할·사용 순서·입력 제약·응답/오류 설명을 추가했다.
플랫폼·상태 확인·인증·Profile 태그로 구분하며, 목록의 문자열 정렬·버전 불변성·201/200/409의
차이·CSRF 사용법·spec 실행 검증 범위를 명시한다. readiness와 구분되는 liveness도 문서화했다.
생성 타입과 패키징 계약을 갱신했고 실제 PC/모바일 Swagger에서 한국어 설명과 등록을 확인한다.
후속 완료 기록은 문서만 변경하므로 이 코드의 검증을 재사용한다.

M2에서 Device 8개·Node 2개를 추가해 현재 구현된 17개 operation을 설명한다.
장치 등록→노드 연결→세션→관측의 사용 순서와 revision/epoch/sequence 충돌·해제·신선도를 명시한다.
M3에서 Workflow 4개·Run 4개·Task 2개를 추가해 총 27개 operation을 제공한다.
DAG 검증·불변 버전·Idempotency-Key·초기 상태·취소 전파·오류를 한국어로 설명한다.
M4에서 `GET /tasks/{taskId}/results`를 추가해 총28개 operation을 제공한다.
검증 전의 빈 결과, 없는 Task의404, 저장소 장애503을 구분하고 고정 버전·체크섬·크기·형식만 조회한다.
서명 URL/자격 증명/파일 본문은 응답하지 않는다. 초기 QUEUED나 Job 종료를 실행 성공으로 표시하지 않는다.
배포 주소: [Swagger UI](http://edgeai.192.168.0.56.sslip.io/swagger-ui.html),
[대체 사설망 주소](http://edgeai.10.254.192.217.nip.io/swagger-ui.html).
배포 계정은 `.tools/kubernetes/edgeai-runtime.env`의 `EDGEAI_API_USER`/`EDGEAI_API_PASSWORD`를 사용한다.
코드 39eb6fe의 CI 36958143060과 실제 Kubernetes HTTP 시험
20261002T031016Z-1de769da / 20261002T031127Z-a1448527이 PASS/0이다.


## M6 VD 등록 API

VirtualDevice 생성/목록/상세/수정/해제5개 operation을 추가하여 공개 Swagger는35개 operation이다.
각 역할·필수 원본 조건·revision 충돌·논리 해제·등록과 런타임 Ready의 차이를 한국어로 설명한다.
Device 해제의 DEVICE_IN_USE(409)도 계약에 반영했다. VD 상세는 현재 연결 전체와 최근100개 이력을
구분한다. OpenAPI 원본에서 Dashboard 타입과 JAR 문서를 함께 생성한다.

## M6 공개 실행 관리

ADR0018의 시작·교체·종료·실행 상태4개를 추가하여 공개 Swagger는39개 operation이다.
기존 Operation 조회는 TASK_OFFLOAD/VD 명령 합집합을 반환한다. 각 설명에 revision·요청 키,
202/200 재전송, 준비/물리 종료의 차이, 이력 제한과 기본 비활성·Task 연결 잔여 범위를 명시한다.

ADR0020의 VD Task 실행 연결에서는 Run의 VD 정책·필수 vdId·Ready/동일 SERVICE 조건과
비활성503·미준비/부적합409를 설명한다. Run/Attempt의 vdId와 Result의 vdRuntimeId를 공개하며,
공유 VD 자원 기반 자동 전환은 거절한다. operation 수는39개다. 내부 poll 계약0.3은 배정 재전송,
실제 프로세스 종료 acknowledgement, 미시작 확인, DRAIN 중 STOP까지 보고할 의무를 명시한다.

## M7 내부 스트림·체크포인트 API

`/swagger-ui/index.html?contract=streams`는 별도 `/stream-openapi.yaml` 원본을 렌더한다.
Device/Runner 배정·양쪽 heartbeat4개, 체크포인트 저장/조회/인계/최종 복구5개,
실행 배정·Task/Device 공동 완료3개, 총12개 operation의 한국어 역할·입력·오류를 제공한다.
내부 경로에는 관리 CSRF를 자동으로
추가하지 않으며 Device token 또는 Runner claim/Pod 신원을 사용한다.
체크포인트는 S3 PUT → 고정 version의 commit → 정확한 receipt 확인 순서를 설명한다.
FINALIZE 배정과 finalized 조회는 현재 producer의 영속 완료 허가를 요구하며 MQTT 권한을 재발급하지 않는다.
허가된 고정 S3 version을 읽고 허가를 재확인하는 복구 순서와 취소/만료409를 설명한다.
내부 checkpoint 인계와 공개 STREAM 실행·Kubernetes 전체 수용의 미완료 범위를 구분한다.
원본/패키징 byte 대조와 PC·모바일 렌더 검사는 기존 CI 계약/브라우저 경로에 포함한다.
