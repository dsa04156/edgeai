# M7 실제 Kubernetes 최종 처리 재시도

2026-10-03. ADR0045/V26의 최종 상태 인계와 ADR0047의 공개 retry를 실제 Kubernetes에서 검증했다.
실행 `20261003T141734Z-481e04ab`은 PASS/0이며 원시 보고서는 해당 폴더의 `kubernetes.json`이다.

## 장애 주입과 판정

공개 Run API에 maxAttempts2·RUNTIME_LOST 정책을 전달한다. 실제 두 Device→root→sink 스트림과
S3→BATCH report DAG를 실행하고 상태14/23까지 계산한 뒤 END를 보낸다. 시험용 sink 최종 파일
명령은 진입 표시 후 파일 장벽에서 기다린다. 플랫폼에 완료 허가나 실패를 직접 주입하지 않는다.

실제 root Result와 Device 완료를 공개 API/SDK로 확인한 후 sink의 실제 grant/checkpoint를 읽는다.
최종 명령에 진입했고 아직 sink Result가 없을 때, 이 Run의 sink Job만 UID 조건·foreground로
삭제한다. Kubernetes controller의 실제 유실 관측과 기존 재시도 worker가 복구를 수행한다.

새 sink Pod의 최종 명령이 대기 상태에 진입한 뒤 다음을 확인하고 장벽을 해제한다.

- 이전 Pod UID가 사라지고 runtime은 STOPPED/TERMINATED다. 새 Pod/Attempt ID와 epoch가 증가했다.
- stream_finalization_recovery의 predecessor/granted Attempt는 원래 sink Attempt다.
- 원래 checkpoint ID와 granted_at, 전체 checkpoint 이력16개가 보존됐다. 새 Attempt의 grant는 없다.
- 경로 generation은 기존1의3개이며 모두 CLOSED다. 새 계산 세대는 만들어지지 않았다.
- 새 Pod의 `/work/stream` 계산 디렉터리가 없고 최종 파일 명령은 진입했다.
- 기존 root Result 객체 전체가 동일하다. root/report는1회, sink만2회이며 최종 결과의 producer는
  실제 관측한 새 sink Pod다. Device owner2개는 각각1회 연결로 완료 상태를 유지한다.
- 최종14/23/BATCH37의 고정 S3 version·실제 bytes·SHA가 일치한다.

같은 실행에서 정상 AUTO/NODE, 계산 중 그룹 재시도, API Pod 교체와 취소도 회귀 검증했다.
총5개 시나리오·실제 Runner Pod17개·고정 S3 결과12개 PASS다. API 교체14.566초 동안 기존
Runner2개의 UID가 유지됐다. 모든 시험 소유 자원을 제거했고 기존 배포/DB/저장소는 유지했다.

## 산출물과 검증 경계

- API: 현재 공개 retry 구현의 JAR SHA256
  `de7010101b4f411d05436dd1a6a023bfea4552ebfb413821f2c0a20b3db03618`.
- Runner: ca56e2f CI에서 시험·발행한
  `sha256:addba1032bc7dc6783f2c58ab81965b70a06648c279b0cfbc914c26f9d45b4a2`.
- MinIO: 기존 검증 digest
  `sha256:c1ff1276340c2df772cc1777662581a64f63279c3f6ebc581e0ca6ed9873f4fb`.
- 실제 scheduler·Pod TokenReview·TLS API/DB/S3/broker/Runner를 사용한다. 최종 명령의 파일 장벽만
  장애 시점을 고정하는 합성 workload다. 처리 성능이나 실장비/실제 AI 모델 수용을 의미하지 않는다.

새 스크립트는 기존 kind CI에서도 같은5개 시나리오를 실행한다. 새 API 이미지 자체의 CI·배포
검증은 후속 게이트이며 이 로컬 JAR 수용과 구분한다. 운영 STREAM 배포·다중 장치 데모,
실행 중 위치 전환, MQTT 간헐 실패 원인과 M5 잔여/M8–M10은 남는다.
