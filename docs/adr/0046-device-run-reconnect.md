# ADR 0046: 동일 Device Session의 자동 경로 재연결

2026-10-03. `stream_device_run.DeviceRunSource`는 명시적으로 선택한 Run과 논리 route 집합을
유지하며 장치의 기존 LOCAL 송신 볼륨을 새 소비 경로로 인계한다. 기존 단일 세대
`DeviceSource` 계약을 유지하고 이 owner에서 조회·종료·재배정 순서를 관리한다.

## 소유권과 복구

호출자는 현재 Device Session의 BindingClient, Run UUID, 논리 route UUID 1–16개,0700 디렉터리를
전달한다. 첫 생성만 `create=True`이며 복구는 기존 journal이 반드시 있어야 한다. 대기 중에도
0600 `run-owner.lock`의 프로세스 잠금을 유지한다. 내부 DeviceSource는 별도의 기존 journal
잠금을 사용한다. 강제 종료 시 커널 잠금이 해제되며 새 owner가 같은 journal을 다시 연다.
세션·route·한도 변경, 세대 역행, 볼륨 손실은 자동 생성이나 초기화로 처리하지 않는다.

`step()`은 한 번에 조회 한 페이지를 처리한다. 현재 세션에 고정된 route를 모두 찾고 모든
대상 세대가 ACTIVE일 때 각 배정을 새로 인증한다. 배정의 Run·방향·Device 주체뿐 아니라
조회에서 선택한 논리 route/세대 번호도 journal 변경 전에 대조한다. 조회와 배정 사이 회수는
다시 조회하며, 페이지 metadata 자체로 lease를 만들거나 연장하지 않는다.

배정/heartbeat의409 또는 로컬 lease 만료에서는 이전 Link와 journal을 먼저 닫는다.
같은 owner가 backoff 후 새 세대를 조회하고 authenticated assignment 아래 LOCAL journal을
인계한다. 생성 순번·처리 ACK·미확인 DATA/END·센서 adapter 상태가 보존된다. 일시적인
HTTP503/연결 실패는 재시도하지만 잘못된 응답·TLS·신원 오류를 무한 재연결로 숨기지 않는다.
Device route 조회의409는 고정 세션 교체이므로 종료한다.401/403/404도 종료하며 다른 세션으로
자격을 바꾸지 않는다. Run 취소/실패, 호출자 취소와 전체 timeout 역시 종료한다.

대기 중 `emit`/`checkpoint`는 `SourceReconnecting`을 반환한다. 이는 Backpressure의 하위
타입이며 새 샘플이나 adapter 상태가 저장되지 않았음을 뜻한다. 호출자는 샘플을 보관하고
`step()`을 계속 호출한 뒤 재시도한다. 성공한 emit의 센서 커서만 재개 지점으로 사용한다.

## 종료 의도와 완료 허가

같은 generation의 영속 종료 의도가 있으면 CLOSED 경로도 기존 completion API로 다시
조회할 수 있다. Run의 SUCCEEDED만으로 완료를 판정하지 않으며 정확한 서버 FINALIZE를
받아야 completed가 된다. 이 경로는 MQTT 배정을 새로 발급받거나 broker를 다시 열지 않는다.
새 generation으로 인계할 경우 기존 종료 순번과 journal의 ACK/END를 다시 대조하고 새
generation의 의도를 기록한다. journal 인계와 completion.json 교체 사이에 프로세스가 죽어도
다음 인증된 인계가 동일 커서를 확인한 뒤 의도를 갱신할 수 있다.

## 검증 경계

실제 HTTPS/TLS MQTT·SQLite·프로세스 SIGKILL 시험은 API 상태 전이를 명시적 fixture로 제공한다.
별도 Spring/PG/S3/TLS MQTT 통합시험에서는 두 DeviceRunSource 객체를 유지한 채 서버의 그룹
재시도와 두 독립 Runner의 새 Attempt를 연결한다. 시험 코드가 Device를 다시 열거나
generation ID를 전달하지 않는다. 실제 서버·SDK 경로와 fixture인 Pod 신원/종료 관측·Run
재시도 정책 생성을 구분한다. 공개 retry와 실제 Kubernetes 장애 수용은 후속 게이트다.
새 DB migration이나 HTTP 계약 변경은 없다. V1–V26은 변경하지 않는다.
[검증 근거](../evidence/m7-device-reconnect.md).

[ADR0068](0068-broker-first-device-reconnect.md)은 브로커 회수가 heartbeat보다 빠른 순서도
처리한다. 브로커 거절 후 닫힌 transport에서 기존 세대의 HTTP 권한을 재확인하며, 여전히
유효한 배정의 브로커 오류나 TLS·신원 오류는 종료한다.
