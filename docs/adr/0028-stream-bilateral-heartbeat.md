# ADR0028 — 양쪽 주체의 생존 확인으로만 스트림 lease 갱신

상태: 로컬 구성 요소 구현·검증 완료. 공개 STREAM 실행 활성화와 별개이며 구성 요소 검증은 [기록](../evidence/m7-stream-heartbeat.md)을 따른다.

인증된 Device/Runner의 배정 조회는 계속 읽기 전용이다. 별도 `/streams/heartbeat` 요청만
현재 ACTIVE 세대의 producer 또는 consumer 관측 시각을 갱신한다. 기존 Device 세션 인증,
Runner Attempt/Pod 인증과 VD→Device→Run→Route 잠금 안의 현재 주체 검사를 그대로 요구한다.
Runner runtime/VD lease·drain 검사는 heartbeat 저장 전에 수행하며 응답 유효기간도 그 기한을 넘지 않는다.

V21은 세대별 고정 window(5–120초), producer/consumer 각각의 마지막 순번과 서버 관측 시각을
저장한다. DB window는 마이크로초 정수로 기록해 기존 PostgreSQL timestamp 정밀도를 보존한다. 새 세대는 원래 prepare의 lease 길이를 window로 고정하고 관측 시각은 생성 시각,
순번은0이다. 기존 이력은 원래 요청 길이를 복원할 수 없으므로 보수적인5초 window로 backfill한다.
이미 발급한 lease를 줄이거나 만료된 세대를 재활성화하지 않는다. MQTT 자격·payload는 저장하지 않는다.

각 주체는 마지막 순번+1만 제출한다. 같은 마지막 순번의 재전송은 새로운 생존 관측으로 취급하지
않고 현재 배정만 돌려준다. 더 오래된 순번과 건너뛴 순번은409다. sequence0은 현재 순번/배정을
읽는 재개 요청이며 상태나 lease를 바꾸지 않는다. 재시작한 SDK는0으로 현재 순번을 확인한 뒤
다음 순번을 사용한다. 마지막 응답 유실 이후에도 같은 순번을 재전송할 수 있다.

새 lease 후보는 `min(producer 마지막 관측, consumer 마지막 관측) + window`다. 현재 기한보다
늦을 때만 연장하므로 한쪽의 반복 요청은 다른 쪽의 마지막 관측+window를 넘길 수 없다.
만료·fence·취소·세션/Attempt 교체 후의 heartbeat는 거절하고 기존 broker worker가 권한을 회수한다.
순번·관측 시각·window·이력의 DB 제약과 원자적 저장을 추가한다. 직접 SQL writer도 같은 잠금
순서를 지켜야 하며 이 테이블은 애플리케이션의 인증을 대체하지 않는다.

응답은 `{sequence, assignment}`로 마지막 승인한 순번과 ADR0026의 현재 배정 snapshot을 준다.
기존 배정 schema는 바꾸지 않는다. SDK는 새 snapshot에서도 요청 경과 시간을 차감한다.
이미 만료된 Link는 늦은 heartbeat 응답으로 다시 살리지 않고, 활성 Link만 동일 주체·경로·세대·
broker/규격의 새 snapshot으로 갱신한다. 초기 조회가 기존 lease를 연장한 것으로 해석하지 않는다.

이 heartbeat는 관리 경로의 주체 생존 확인이다. 실제 처리 진행·checkpoint의 외부 내구성,
장치/모델 정확도나 무손실 전달의 증거가 아니다. 실제 Runner 계산 프로세스 watchdog,
S3 checkpoint/새 Pod 복원, 운영 broker 및 다중 장치 공개 실행 수용은 계속 남는다.

V1–V20은 변경하지 않는다. V21의 세대 INSERT trigger가 같은 트랜잭션에서 초기 heartbeat를
만들므로 배포 중 구버전 앱이 생성한 세대에도 상태가 생긴다. 기존 내부 `renew`는 신뢰된 제어
서비스의 별도 API이며 클라이언트에게 노출하지 않는다. 인증된 외부 갱신 경로는 위 heartbeat만
사용한다. SDK의 refresh는 고정 monotonic clock도 바꾸지 않으며 전체 배정을 원자적으로 교체한다.
