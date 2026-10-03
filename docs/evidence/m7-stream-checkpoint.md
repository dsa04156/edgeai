# M7 외부 체크포인트 SDK·전송 확정 범위 검증

2026-10-03 KST. [ADR0031](../adr/0031-stream-portable-checkpoint.md)의 범위다.
이 SDK 검증 시점에는 공개 STREAM·새 Attempt/generation 전환·인증된 checkpoint API/DB 확정이
미구현이었다. 후속 [인증 서버 검증](m7-stream-checkpoint-api.md)은 별도 증거를 따른다.

| 시험 | 실행 ID | 결과 |
|---|---|---|
| 신규 체크포인트8개·기존 journal13개 | 20261003T025821Z-16ea0bee | PASS/0,21개·3.176초 |
| 전체 Runner/VD/SDK 및 체크포인트8개 | 20261003T030130Z-9aa203e7 | PASS/0,78개·74.660초 |
| 체크포인트9개, 추가 실제 SIGKILL 회귀 포함 | 20261003T030529Z-ff548982 | PASS/0,9개·1.782초 |
| 실제 HTTPS/MQTT/모델 전체 | 20261003T030422Z-a0f90f35 | PASS/0,38개·59.286초 |
| 실제 MinIO/S3 고정 버전·내용 검증 및 복원 | 20261003T030129Z-ab59650d | PASS/0,5개·실패/skip0 |
| 실제 Spring·PostgreSQL·TLS broker·Session 회귀 | 20261003T030423Z-b349d4bd | PASS/0,22개·실패/skip0 |

## 직접 확인한 동작

- EXTERNAL 모드에서 계산을 로컬 확정해도 실제 TLS broker를 통한 출력과 처리 ACK는
  외부 확인 전까지 진행하지 않는다. producer는 입력을 보존하고 sink에는 결과가 없다.
- 일치하는 후보 serial/SHA를 확인한 뒤에만 출력9와 입력 처리 위치가 전달된다. 같은 확인은
  멱등, 다른 SHA/미래/과거 확인은 거절한다. 늦은 중복 확인이 다음 후보를 삭제하지 않는다.
- 입력 수신·출력 ACK는 계산 revision과 별개로 serial을 증가시킨다. 중복과 용량/권한
  롤백은 증가시키지 않는다. pending 후보는 새 입력·프로세스 재시작에도 같은 bytes다.
- frontier 변경 도중 실제 프로세스 SIGKILL 및 commit 직전 lease 거절 모두 전체 frontier를
  되돌리고 후보를 보존한다. 새 소유자가 같은 후보를 확인한 뒤 다시 발행할 수 있다.
- 원래 볼륨을 삭제해도 입력 처리 위치·계산9·미확인 출력/END·아직 처리하지 않은 입력을
  새 볼륨에 복원한다. 실제 Session/지속 합산 모델은 이전 입력을 다시 계산하지 않고
  2+3을 받아14까지 이어 간다. 복원 후 새 출력도 새 확인 전에는 발행하지 않는다.
- 실제 MinIO 버킷 versioning에서 export한 snapshot을 업로드하고 실제 SHA/bytes를 검증했다.
  같은 key에 같은 길이의 손상된 새 version과 위조 metadata를 넣어도 새 version은 거절되며,
  원래 고정 version을 다운로드한 독립 Python 프로세스가9→14 계산을 복원했다.
  원본 SQLite 볼륨과 export 파일은 download 전에 삭제했다. 모든 시험 object version과
  전용 버킷, 소유 MinIO 프로세스·임시 데이터는 종료/정리했다.
- schema와 실제 export 예제를 오프라인 JSON Schema로 검증하고 잘못된 counter를 거절했다.
  최초 검증 helper는 상대 ref의 canonical URL 매핑 누락으로 실패했다. 로컬 resolver에
  filename/$id/해석된 URL을 등록해 수정했으며 제품 schema를 완화하지 않았다.

확정 응답은 MQTT/Session 시험에서 명시적 fixture다. S3 시험은 기존 S3ArtifactStore의 실제
검증을 사용하지만 checkpoint의 현재 producer 재검사·영속 이력은 아직 제어 서비스에 연결하지
않았다. Snapshot의 producer를 파일만 보고 바꾸지 않으며 다른 binding restore는 거절한다.
현재 REST/Swagger/DDL 변경은 없다. 기존 V1–V21 migration을 변경하지 않는다.
신규 CI·정확한 이미지 배포 결과는 후속 확인 대상으로 남긴다.

## SDK CI·실제 배포 확인

source `a4e87c7`의 [CI37093274029](https://github.com/dsa04156/edgeai/actions/runs/37093274029)는
5 jobs 모두 success다. 다운로드한 runner/storage/platform/images의 result.json17개 모두
PASS/0을 직접 확인했다. 실제 Runner 컨테이너79개(91.452초), HTTPS/MQTT38개(56.621초)와
실제 kind의 BATCH·재시작·retry/offload·TLS Remote·VD Task 경로를 통과했다.
kind 시험은 고정 S3 artifact20+5개를 검사했고 전용 `edgeai-ci-0be338306d37`을 삭제했다.

GitOps `73b6f11`과 `20261003T040416Z-7679f70f`에서 API/dashboard/MinIO3개 imageID가
정확히 source a4e87c7 digest와 일치하고 Ready·PVC Bound·Argo Synced·VD 활성화를 확인했다.
공유 Ingress status 제한으로 Argo aggregate health는 Progressing이다.
이 결과는 portable SDK 커밋의 검증이며 후속 ADR0032 서버 변경의 CI 결과는 아니다.
