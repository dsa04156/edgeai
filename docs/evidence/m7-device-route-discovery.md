# M7 Device 경로 조회 검증

2026-10-03. [ADR0043](../adr/0043-device-stream-route-discovery.md)의 Device 전용 메타데이터 API와
SDK를 로컬 실제 HTTP/DB/SDK/계산 경로까지 연결했다. 그룹 복구 자동화와 M7 전체 완료는 남는다.

## 구현 범위

현재 장치 세션 토큰과 Run에 고정된 불변 Device binding을 대조해 본인 route와 최신 generation만
조회한다. 세대 준비 전null, 준비/활성/닫힘 상태와 페이지를 지원한다. 토큰·MQTT 자격·서명 URL을
반환하지 않으며 읽기가 세대 생성이나 lease 갱신을 일으키지 않는다. 실제 송신은 기존 배정 API의
유효 권한을 다시 확인한다. 이전 세션이나 다른 장치의 Run으로 범위를 확장하지 않는다.

Python `BindingClient.device_routes`는 정확한 주체·JSON 타입·페이지·중복·정렬·generation을 검증한다.
실제 Source/DAG probe는 이 응답의 route/generation으로 DeviceSource를 열고 샘플·END를 송신한다.
관리 API에서 관측한 경로는 독립 대조에만 사용한다. Kubernetes driver도 같은 호출로 연결했으나
이 새 코드의 이미지·실제 Kubernetes 검증은 아직 후속 CI 대상이다.

## 확인한 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 SDK HTTP와 엄격한 응답·페이지·토큰 범위 | `20261003T113232Z-8292f51f` | 14개 PASS |
| Device 토큰·다른 Run404·세션 교체401/409·fanout 페이지·lease 불변과 기존 공개 Run | `20261003T113233Z-6b799981` | 실제 PostgreSQL/MVC16개 PASS |
| 실제 HTTPS 조회→DeviceSource→독립 스트림/배치 Runner·취소 | `20261003T114250Z-537c14ab` | 실제 PG/MinIO/TLS MQTT4개 PASS |
| OpenAPI 생성·MVC·패키징 계약 비교 | `20261003T114445Z-87003890` | PASS, 계약/MVC26개 |
| 전체 서버 단위 | `20261003T114530Z-ee72498c` | 101개 PASS |
| 전체 Runner | `20261003T114532Z-f82eb744` | 110개 PASS, 87.227초 |
| 전체 PostgreSQL | `20261003T114710Z-03d1059b` | 181개 PASS, skip0 |
| 발견한 route ID로 실제 샘플·END까지 송신하는 최종 probe | `20261003T114849Z-adfd664e` | 실제 Source/DAG4개 PASS |
| 실제 API·DB·PC/모바일·Swagger | `20261003T114921Z-2584d82a` | 10개 PASS, 내부13/관리41 API와 새 설명·409 표시 |

`114549Z-8f7ebd95`는 실행 이름에 all이 있으나 helper 기본 선택으로 실행 완료10개만 검증했다.
전체 PostgreSQL181개 근거는 이후 명시적 전체 선택의 `114710Z-03d1059b`이다.

실제 DAG는 root14/sink23/report37 및 고정 S3 bytes·SHA·version, 실행 중 sink 취소와 하위 미배정을
검증한다. Spring·PG·HTTPS·MQTT·MinIO·장치 SDK·Runner·계산 subprocess는 실제이며 이 로컬
시험의 Pod provisioning/identity/종료 관측은 명시적 fixture다. 물리 장치·실제 모델 성능 시험은 아니다.

## 선행 이미지와 후속 게이트

선행 e93d9e1은 CI37118314544 5jobs/17JSON, 실제 GitOps ee44614 배포와 완성 API 이미지의
Kubernetes AUTO/NODE DAG·API 교체·취소·S36개를 통과했다. 상세는
[Kubernetes 기록](m7-kubernetes-stream.md)을 따른다. 이 근거는 이번 새 조회 API 이미지 검증이 아니다.
새 STREAM kind 게이트 소스 c152d4d는 CI37120638129 5jobs/17JSON과 실제 kind STREAM·배포를 통과했다.
이번 Device 조회 API의 새 이미지 검증은 별도다.

이 조회 API는 장치의 자동 reconnect/journal handover 또는 전체 그룹의 fence·물리 종료·
새 Attempt/경로 세대·checkpoint 인계 orchestration을 구현하지 않는다. 공개 STREAM opt-in과
AUTO/NODE 범위는 유지하며 M5 잔여/M7–M10 및 전체 목표는 미완료다.
