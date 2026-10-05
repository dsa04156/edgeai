# STREAM 최초 경로 조회 재시도 검증

[ADR0113](../adr/0113-stream-initial-discovery-retry.md)의 근거다.

- `20261005T053544Z-92076f98`: 기존 그룹 재시도 단독 시험 1개 PASS.
  실제 Runner를 테스트 전용 진단 wrapper로 호출했다. 오류 타입·고정 코드·내부 위치만
  기록하며, 경로 조회·checkpoint·계산·Result는 기존 실제 구현을 호출한다.
- `20261005T053643Z-a1b31a21`: 실제 Spring/PG/S3/MQTT 15개 중 6개 FAIL.
  VD 두 사례에서 최초 Session 경로 조회의 `AssignmentUnavailable`을 확인했다.
  별도 실패에는 JDBC 연결 대기, 성공한 Run의 VD 정리 종료 코드, 연결 준비 실패가 있다.
  관측한 COMMIT/WAL 대기는 12.523793초까지였다. 소유 DB/MinIO는 정리했다.
  이것만으로 모든 실패의 원인을 디스크 지연이나 최초 경로 조회로 확정하지 않는다.
- `20261005T054440Z-4874cb84`: 수정 전 실제 HTTPS 503 재현 FAIL.
  두 번째 경로 조회의 첫 일시 실패에서 Session 생성이 즉시 중단됐다.
- `20261005T054513Z-e7bd0318`: 수정 후 새 5개 경계 PASS. 이후 동일한 권한 제한
  안에서 재시도 대기를 50ms 고정에서 최대 1초 지수 증가로 조정했다. 최종 코드의
  회귀 검증은 아래에 별도로 기록한다.
- `20261005T055354Z-14405a56`: 최종 지수 대기 코드의 실제 HTTPS/TLS MQTT 전체
  107개 PASS/0 skip, 207.174초. 새 5개와 기존 상태 복원·완료 허가·취소·거절·
  watchdog·broker 재시작·Device journal 복원까지 실행했다. 앞선 Spring/PG 실패의
  모든 원인이 해결됐다는 근거로 확대하지 않는다.
- `20261005T055758Z-47de3f0d`: 기본 Runner 111개 PASS/0 skip.
- `20261005T060011Z-88dd5ef2`: native 이미지 발행 조건 8개 PASS.
  새 MQTT 107개 중 하나가 누락된 106개, 과거 102개/97개, 실패·skip·플랫폼·신원
  불일치를 거절한다. 이 로컬 시험은 새 ARM/AMD64 컨테이너 실행을 대신하지 않는다.
- `20261005T060012Z-29c1ea1d`: 수정 후 실제 Spring/PG/S3/MQTT 15개 중
  10개 PASS/5개 FAIL. 소유 DB/MinIO 정리를 확인했다. 남은 실패는 VD 두 사례의
  JDBC 연결 대기, DeviceSource 최초 조회의 AssignmentUnavailable, Device driver의
  사전 경로 조회 일시 실패 및 연결 재준비/경로 만료 경계다. 이 실행의 DB 관측에서는
  COMMIT 대기가 17.873855초까지 보였다. Session 재시도만으로 모든 실패가 해결됐다고
  판정하지 않는다. DeviceSource/driver 경로와 DB 대기는 후속 진단·수정 대상이다.

최초 응답은 명시적 서버 fixture이며 이 검증이 전체 Kubernetes·외부 장비 수용을
증명하지는 않는다. 전체 회귀와 실제 배포 완료는 별도 확인이 필요하다.
