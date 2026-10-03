# M7 공개 STREAM 재시도 연결

2026-10-03. [ADR0047](../adr/0047-public-stream-retry.md).

## 확인한 범위

- 공개 Run JSON의 기존 retry 정책으로 계산 중 그룹 복구와 완료 허가 뒤 단독 최종 처리를 시작한다.
- 직접 Run/정책을 저장하던 복구 시험을 공개 MVC 요청으로 교체했다.
- 정책/입력 순서 정규화, 같은 요청 재전송, 정책 변경 충돌, 잘못된 정책과 offload 거절을 검증했다.
- Swagger 설명·생성 타입·화면 안내와 PC/모바일 정책 입력·재전송을 갱신했다.
- 기본 STREAM 비활성과 AUTO/NODE 범위, V1–V26 불변을 유지한다.

## 로컬 실행 근거

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 공개 Run/그룹/최종 처리 PostgreSQL | 20261003T135632Z-05356775 | 23개, failure/error/skip0 |
| 공개 Run→실제 Spring/PG/S3/TLS MQTT/독립 Runner | 20261003T135715Z-622f60ce | 6개, failure/error/skip0 |
| OpenAPI5개·생성 타입·MVC | 20261003T135839Z-c00554f3 | 26개, failure/error/skip0 |
| UI lint/types/build·PC/모바일 | 20261003T135838Z-a9b6da58 | 38개 PASS |
| 전체 PostgreSQL 회귀 | 20261003T140254Z-95a88139 | 188개, failure/error/skip0 |
| 실제 Kubernetes 공개 재시도·기존 실행 회귀 | 20261003T140253Z-edc86b03 | 4개 시나리오·Pod13개·고정 S3 파일9개 PASS |
| 단위/MVC 전체 | 20261003T140552Z-3f4f67e6 | 101개, failure/error/skip0 |
| 전체 실제 저장소·DB 회귀 | 20261003T140653Z-4c9d59ba | 36개, failure/error/skip0 |
| 실제 API/DB·PC/모바일·Swagger | 20261003T140836Z-ab6f12ac | 10개 PASS, 임시 API/UI 종료 |

실제 서버6개에는 동일 Device owner의 자동 재연결과 root14/sink23/BATCH37, 완료 허가 뒤
새 Attempt의 최종 결과14가 포함된다. 이 시험의 Pod 생성·신원·종료 관측은 fixture다.
화면의 STREAM 응답도 HTTP fixture이며 실제 Kubernetes 시험과 구분한다.
기존 Flyway26개 파일을 HEAD와 바이트 대조해 모두 동일함을 확인했다.

## 실제 Kubernetes 장애 복구

위 Kubernetes 실행은 현재 수정분의 JAR
`de7010101b4f411d05436dd1a6a023bfea4552ebfb413821f2c0a20b3db03618`과
ca56e2f CI Runner job에서 실제 시험·발행한 digest
`sha256:addba1032bc7dc6783f2c58ab81965b70a06648c279b0cfbc914c26f9d45b4a2`를 사용했다.
새 API 이미지 자체의 검증은 후속 CI 게이트와 구분한다.

- AUTO/NODE 정상 실행, AUTO 그룹 재시도, 취소의4개 공개 API 시나리오를 수행했다.
- root/sink의 확인된 외부 상태9 이후 이 실행이 소유한 sink Job을 UID 조건·foreground로 삭제했다.
  실제 controller가 유실을 관측하고 기존 공개 retry 정책으로 전체 그룹을 재시도했다.
- 이전 Pod2개가 사라지고 runtime2개가 STOPPED/TERMINATED, 이전 경로 세대3개가 CLOSED였다.
  다른 UID의 Pod2개, 동일 Task의 Attempt2/epoch 증가와 경로 generation2를 확인했다.
- 복원 장벽의 DB 조회는 **새 Attempt ID**로 제한해 오래된 checkpoint만으로 통과할 수 없게 했다.
  새 두 실행의 외부 상태9와 기존 Device owner2개의 센서 커서 보존·각2회 연결을 확인했다.
- 다음 데이터로 root14/sink23·BATCH37이 생성됐고 각 Result가 실제 관측한 새 producer Pod와
  일치했다. 전체9개 S3 고정 version의 실제 bytes·SHA·기대 계산값을 대조했다.
- 별도 AUTO 시나리오의 실제 API Pod 교체11.596초 동안 두 Runner Pod UID가 유지됐다.
  취소 시 하위 BATCH 미배정/결과 없음과 모든 시험 소유 자원 제거를 확인했다.

원시 metadata 보고서는 해당 실행 폴더의 `kubernetes.json`이다. 자격 증명·서명 URL은 포함하지 않는다.
새 코드의 CI/이미지/배포 검증은 아직 완료로 판정하지 않는다.

## 남은 범위

완료 허가 뒤 실제 Kubernetes 장애, 운영 STREAM 배포·다중 장치 데모, STREAM 실행 중 위치
전환과 M5 상태형/외부 계약 수용, M8–M10은 남는다. 선행 SDK 전체 MQTT의 간헐 timeout/lease
만료는 원인 미확정이다. 선행 ca56e2f CI Runner의 MQTT 실행은 PASS였지만 재발 원인 해결을
의미하지 않는다. 이 문서는 M7 또는 전체 플랫폼 완료 판정이 아니다.
