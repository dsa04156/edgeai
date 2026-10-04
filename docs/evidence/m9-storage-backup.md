# M9 — MinIO 고정 버전 백업 검증

2026-10-04 KST. [ADR0060](../adr/0060-version-preserving-storage-backup.md)의 object-version
구성 요소다. 격리된 실제 TLS MinIO 두 개와 합성 파일을 사용하며 기존 개발 bucket을 바꾸지 않았다.

최종 `20261004T013635Z-8b24378d`의 `scripts/test-storage-backup.sh`가11개 사례 exit0/PASS다.
체크포인트 key의 이전/현재 두 버전, 빈 파일, 한글 경로의256KiB 파일을 사용했다.
총4개 고정 version·262,176bytes의 실제 ID/길이/SHA-256을 확인했다.

| 경계 | 검증 |
|---|---|
| 실제 복제 | 서로 다른 deployment ID의 TLS source→replica, 이전 버전을 포함한 정확한 ID/bytes/SHA |
| 기존 자료 | 기존 backup 폴더의 파일 목록/manifest 불변, 기존 target bucket 거절 후 원래 backup 검증 |
| 서버 신원 | 같은 source/target 거절, manifest와 다른 installation에서의 검증 거절 |
| 기존 설정 | source의 다른 replication rule을 보존하고 backup 요청 거절 |
| 미연결 대상 | 실제 rule 생성 실패가 남긴 remote target을 TLS/SigV4 admin 조회로 발견하고 변경 없이 거절 |
| 실패 정리 | 실제 rule 생성 후 verifier 오류를 주입, TimeoutError·자신의 rule 제거·manifest 미발행 확인 |
| 이후 변경 | 원본의 영구 version 삭제와 새 쓰기가 완료된 backup의4개 버전을 바꾸지 않음 |
| 원본 유실 | source 종료 후 replica 프로세스를 새 포트에서 재시작하고 동일 deployment ID·4개 version 검증 |
| 손상/누락 | 다른 기대 SHA 거절·원래 내용 불변, backup version 삭제 후 원본 없는 상태에서 검증 실패 |

CA 검증을 끄지 않고 client와 두 서버에 시험용 CA 신뢰를 명시했다. 최종 server binary SHA-256은
`a18c259d800694d3d48b5d4d25091b053359be11d8e8c834b8481e445ad52c48`이다. 공식 mc binary는
ADR0060의 고정 SHA를 매 실행 확인한다. 모든 소유 프로세스 종료를 확인했으며
`storage-backup-report.json`을 위 evidence 폴더에 복사했다. 원본 파일·개인 키·계정·mc 로그는
비공개 `.tools/storage-backup-test-*`에만 보관한다.

초기 탐색 `20261004T011527Z-dd203dcb`는3개 version/bytes 복제를 확인했으나 마지막 rule을
ID로 제거하는 mc 명령에서 실패했다. 고정 client가 빈 rule 목록을 PUT하는 원인을 확인하고,
정확히 소유 sole rule인 경우에만 삭제하도록 했다. 수정 탐색 `011723Z-4ce7be7c`는 원본 종료
이후까지 PASS다. 첫 제품 시험 `012149Z-c91cb7b5`와 `012222Z-9f70fda7`는 각각 mc export의
빈 Rules 정상 응답과 versioning의 중첩 JSON을 잘못 해석해 실패했다. 실제 반환 구조를 확인해
고쳤다. 후속8개 `012309Z-ba9fe6ee`, 오류 정리 포함9개 `012628Z-00069317`, 기존 폴더 보존
포함10개 `012817Z-05db9187`을 통과했다. 미연결 대상 시험의 `013431Z-eecc234d`는 priority0을
거절할 것이라는 잘못된 fixture 가정 때문에 실패했다. 실제로 거절되는 priority-1로 remote
target만 남는 상태를 만들고, 이를 보존하는 위 최종11개 시험을 통과했다.

CI storage job에 같은 테스트를 추가했다. 그 job이 만든 MinIO 이미지에서 binary를 추출하며
source/replica TLS 시험에 사용한다. 로컬 Docker 권한이 없어 해당 추출 경로와 새 CI 실행은
후속 확인한다. 테스트 성공을 배포 환경의 DR 설치 완료로 해석하지 않는다.

다음 게이트는 새 DB로 복원한 `result_artifact`·`stream_checkpoint` 전체 참조를 replica의
정확한 버전과 대조하는 것이다. S3 목록만의 보존으로 DB와의 교차 정합성을 주장하지 않는다.
IAM/CA/Secret·broker/device journal·실행 중 외부 상태·운영 활성화·백업 저장 위치/보존 정책은
[M9 수용 범위](../m9-requirements.md)에 남아 있다.
