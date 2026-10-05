# Device 드라이버의 초기 조회·미저장 샘플 재시도

2026-10-05. [ADR0046](../adr/0046-device-run-reconnect.md)의 기존 호출자 계약을
VD 통합시험 드라이버에 적용했다. `ready` 확인 이후에도 실제 `emit`에서 재연결 또는
용량 부족을 만날 수 있다. 이미 성공한 다른 장치의 샘플은 대기 목록에서 제거하고,
저장되지 않은 DATA/END와 adapter 상태만 원래 대기 기한 안에서 다시 보낸다.
대기 중에는 모든 기존 Device owner의 `step()`을 계속 호출한다.

VD 사전 경로 조회는 `AssignmentUnavailable`만 재시도한다. 취소·고정 세션 교체·
인증 거절·잘못된 응답은 계속 실패한다. 완료 시험의 단일 세대 `DeviceSource` 초기화는
journal이 만들어지기 전의 조회 실패만 호출자에서 재시도하며, 최초 90초 기한을 유지한다.
기존 peer를 진행시키고 매번 현재 서버 권한을 다시 읽는다. SDK 내부에 새 동기 대기 루프를
넣거나 lease를 연장하지 않았다. 기존 journal이나 완료 의도를 재생성하지 않는다.

## 실제 검증

- 수정 전 `20261005T060012Z-29c1ea1d`: Spring 15개 중 5개 FAIL. DeviceSource
  초기 조회와 VD driver 사전 조회의 `AssignmentUnavailable`, FIRST 단계의
  `SourceReconnecting`, JDBC 연결 대기를 기록했다.
  [앞선 실패 근거](m7-stream-initial-discovery.md)를 보존한다.
- `20261005T062134Z-51faef2d`: 실제 HTTPS·TLS MQTT·SQLite를 사용하는 새 9개 PASS.
- `20261005T062606Z-4fb0d151`: 최종 10개 PASS/0 skip, 74.189초.
  초기 조회 503 복구·원래 기한·peer heartbeat·취소·401/409 종료와, 한 장치가 이미
  저장한 뒤 다른 장치가 재연결/용량 부족을 만났을 때 DATA/END·adapter 커서 보존을
  확인했다. SQLite checkpoint UPDATE 이후 COMMIT 직전의 권한 만료도 주입하여
  실제 rollback 후 순번과 revision이 한 번만 증가함을 확인했다. SDK 함수는 mock하지
  않았으며 서버 응답/lease 전이는 명시적 시험 fixture다.
- `20261005T062215Z-326c6a54`: 실제 Spring/PG/S3/MQTT
  `StreamSourceCompletionIntegrationTest` 15개 PASS/0 skip. 독립 Runner DAG,
  shared/distinct VD, 재시도·공개 offload·취소·상속 finalizer를 검증했다.
  완료 checkpoint 참조 22개와 publication guard를 확인하고 소유 DB/MinIO를 정리했다.
  비공개 보고서: `.tools/stream-completion-tests-e964d39d96b8423db09a170132c9a17f/report.json`.
  DB 관측 244개에서 I/O 대기는 최대 1.364183초였다. 앞선 17.873855초 지연의
  근본 원인이 해결됐다는 판정은 아니다.
- Python 문법, CI YAML과 새 storage 검사의 의존성 설치 → driver 검사 → Spring 검사
  순서를 확인했다. 새 10개는 `scripts/test-stream-drivers.py`를 CI에서 직접 실행한다.

## 원격 검증과 남은 범위

선행 커밋 `3afc0f045fb26b49d8a64a62c7e06e496337a1f0`의
[CI 37270925730](https://github.com/dsa04156/edgeai/actions/runs/37270925730)에서
amd64/arm64 각각 Runner 111개·MQTT 107개 원시 PASS와 native provenance를 확인했다.
실제 GHCR index도 다시 읽어 두 플랫폼 digest와 대조했다.

- Index: `sha256:8520966900d6e0c34777a22685a318c2e1751a5115a4fc370ad0257ed6579d3e`
- amd64: `sha256:8a31216f64383358d281c49711a1660d82c91f7c62465eff83db42822777c62b`
- arm64: `sha256:43dc2512346ede7578670f89b94c558d2a3d8019a3e1bea6be4bdb962b6e8b10`

이 자료는 새 driver 수정의 원격 CI/배포 증거가 아니다. 선행 CI의 scaffold/storage는
종료 성공했고 내려받은 원시 결과 31개(19+12개)도 모두 PASS다. images는 아직 진행 중이며
전체 CI 완료 전에 새 push로 취소하지 않는다. 전체 backend 회귀·일시 DB 대기의
원인·다른 DAG/배포 acceptance driver의 같은 호출 경계는 후속이다. M9의 누락 완료 허가/
checkpoint/실행 DB 반영·전역 writer/API 차단·종합 활성화와 실제 모델/외부 계약을 포함한
M0–M10 전체 목표도 미완료다.
