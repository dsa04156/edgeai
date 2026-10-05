# V38 복구 시험의 전체 테이블 보존 검증

V37에서 `stream_completion_publication`이 추가되어 현재 스키마는 45개 테이블이다.
기존 Remote·Kubernetes·STREAM 복구 시험 5곳의 정확한 테이블 수 검사가 44개를
기대했다. 검사를 45개와 새 테이블의 존재 확인으로 갱신했다. 모든 테이블의 실제
내용 해시를 비교하는 기존 검사는 유지하고, 보존 개수 보고도 실제 비교 대상에 맞췄다.
Remote 실패 보고에는 비밀이 없는 마지막 코드 위치 5개를 추가했다.

- `20261005T054231Z-e0999040`: 수정 전 실제 PostgreSQL/TLS Remote/복원 DB에서
  `assert len(before) == 44` 실패를 재현했다. 0개 사례 후 실패했으며 소유 자원은 정리했다.
- `20261005T054313Z-04b85c04`: 수정 후 Remote retirement 17개 PASS.
  실제 rollback·경쟁 잠금·COMMIT 응답 유실·독립 파일 복원·격리 DB 쓰기 차단과
  45개 테이블 중 변경 대상 3개 외 42개 보존을 확인했다.
- `20261005T054540Z-bc77b604`: Remote inventory 10개 PASS.
  정상/누락/충돌/백업 후 실행을 구분하고 복원 DB 2개와 45개 테이블을 보존했다.
- `20261005T054649Z-dc0ab2c6`: 실제 Kubernetes inventory 6개 PASS.
  백업 이후 생성된 소유 Job/Pod 발견, 외부 자원 보존과 전체 DB 불변을 확인했다.
- `20261005T054816Z-b8958dac`: 실제 Kubernetes/VD retirement 23개 PASS.
  원래 parent/child 종료 증거, 배정 이력, 원자적 원복, 경쟁, 응답 유실과 타 39개 테이블
  보존을 확인했다. 시험 namespace·DB·API 정리를 확인했다.
- `20261005T055033Z-88a518ce`: 실제 Device/STREAM 복구 47개 PASS.
  원본 삭제·TLS 복제 저장소·장치 journal·원래 broker 차단을 연결하고, STREAM 종료
  14개 경계에서 타 44개 테이블 보존을 확인했다. DB/API/저장소/broker/client/잠금
  프로세스 정리를 확인했다.

5개 묶음 103개를 직렬로 실행했다. 사용한 V38 JAR의 SHA256은
`1f45fef7769ba705a5c533c2117f00f117fbf40b78ccd51dee258211f4270048`이다.
후속 원격 CI/배포 확인은 별도이며, 전체 M0–M10 수용 완료를 의미하지 않는다.

## 선행 CI 결과

[37267774293](https://github.com/dsa04156/edgeai/actions/runs/37267774293)는
`fa1c6c368cccb89fa37817d0a07eb5b64a5402bd`에서 최종 실패했다.
scaffold의 Remote inventory와 storage의 Remote retirement가 각각 0개 사례 뒤
AssertionError로 실패했다. 원시 보고서는 scaffold 18 PASS/1 FAIL, storage 6 PASS/1 FAIL이다.
다운로드한 native 원시 로그는 ARM64와 AMD64 각각 Runner 111개/MQTT 102개 PASS이며
누락·skip이 없고 index 기록의 두 manifest와 일치한다. GHCR index
`sha256:34edcacd3df232527bec7bb9505ca8127b0cf36dc6e59673cb71f3dba0373790`을 다시
조회해 같은 두 플랫폼 manifest도 확인했다. images 및 GitOps 작업은 생략돼
새 배포가 이루어졌다는 근거가 없다.

이 결과는 후속 최초 조회 재시도 수정이나 MQTT 107개의 새 native 검증 근거가 아니다.
복구 실행 자체의 권한·SQL·지원 버전·Flyway 파일을 변경하지 않았다.
