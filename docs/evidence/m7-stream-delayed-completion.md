# STREAM 완료 응답 지연 검증

[ADR0112](../adr/0112-stream-grant-after-route-revocation.md)의 Runner 수정이다.

- `20261005T050342Z-33ff077e`: 시험용 mosquitto_passwd 경로 누락으로 시작 실패.
  업무 동작의 결과가 아니다. 경로를 지정해 후속 실행했다.
- `20261005T050405Z-f34802a6`: 수정 전 재현 FAIL. 실제 Runner·HTTPS·TLS MQTT에서
  terminal checkpoint 이후 완료 응답을 보류하고 route를409로 종료했다. Runner가 세 번째
  완료 재조회 전에 INVALID_RESPONSE로 종료하여 정상 결과를 확정하지 못했다.
- `20261005T050547Z-98ff9718`: 수정 뒤 확대23개 중11개 FAIL. 대부분 terminal 허가에
  도달하기 전 INVALID_RESPONSE였으며 상세 내부 예외는 없었다. 같은 시간 별도 Runner
  기본 시험이 실행 중이었다. 병행 실행이 원인이라는 판정은 하지 않는다.
- `20261005T050616Z-985d5c07`: Runner 기본111개 PASS.
- `20261005T050946Z-1d20acd9`: 실제 Runner를 테스트 전용 진단 wrapper로 호출한
  대상2개 PASS. 지연된 완료 응답 뒤 상태14의 결과1개와 기존 finalizer 실패 처리를 확인했다.
  wrapper는 오류 타입·고정 코드·내부 코드 위치만 기록하며 production 로직을 그대로 호출한다.
- `20261005T051035Z-7c808cfc`: 진단 wrapper의 직렬23개 중22개 PASS/1개 FAIL.
  새5개(응답 지연 뒤 결과1개, 취소, 현재 신원 거절, WAITING 기한 만료, 다른 checkpoint
  거절)는 모두 통과했다. 기존 finalizer 재시작 시험은 첫 완료 허가 이전 SessionError로
  실패했다. `20261005T051246Z-f583b891`에서 해당 기존 사례를3회 관측했고 모두 통과했다.
  간헐 실패 원인을 해결했다는 판정은 아니다.
- `20261005T051345Z-ce7c33ba`: 일반 Runner 진입점의 전체 HTTPS/MQTT102개는
  실패1개/오류10개로 FAIL이다. 새5개는 모두 통과했다. 기존 Runner 복원은 최초
  상태9 checkpoint 이전 실패였고, Session/Source 오류는 주로 초기 binding 조회·
  MQTT 준비·journal 생성 중 권한 만료/거절이었다. Session 기한 시험은 준비 단계에서
  기한이 만료됐다. 이 Session/Source 경로의 production 파일과 기한은 수정하지 않았다.
  시험 시간·fsync·권한 검사를 완화하지 않고 실패를 보존한다.
- `20261005T051847Z-e10ca1dc`: 별도 PG16의 initdb가 디스크 동기화 단계에서60초
  제한을 넘었다. Spring 시험은 시작하지 못했다. 소유 data 삭제와 해당 경로를 사용하는
  잔여 프로세스0개를 확인했다. 직후 host I/O pressure의60초 평균은 some23.84/full21.22,
  여유 공간69GiB였다. 이는 관측값이며 모든 간헐 실패의 원인을 확정하지 않는다.
- `20261005T052004Z-fe880040`: native image provenance8개 PASS. 새5개가 포함된
  MQTT102개/Runner111개가 두 아키텍처 모두 통과해야만 index를 발행한다.
  과거97개와 한 개 누락101개·실패/생략·신원/플랫폼 불일치를 거절한다.
- `20261005T052054Z-d89f7a34`: 기존 PG의 별도 시험 DB·실제 Spring/TLS S3/MQTT/
  Runner15개는14개 PASS/1개 FAIL이다. 그룹 재시도에서 두 번째 배정의 복원 대기 중
  root Runner가 INVALID_RESPONSE로 종료했다. 원인은 아직 미확정이다. VD7개와
  나머지 실제 source 경로는 통과했다. DB 관측307개/WAL COMMIT128표본의 최대 대기는
  2.519519초였고, 소유 시험 DB와 MinIO는 정리했다.

신규 원격 CI/배포를 확인해야 하며 전체 회귀의 미해결 실패는 남는다.
이 시험의 서버 권한 응답은 fixture이며 실제 PostgreSQL·Kubernetes의 전체 수용과 구분한다.
