# ADR0060 — 고정 S3 버전을 보존하는 별도 MinIO 백업

2026-10-04. [M9](../m9-requirements.md)의 DB와 파일 복구 경계를 이어 구현한다.
`result_artifact`와 `stream_checkpoint`는 bucket/key뿐 아니라 정확한 object version과
bytes/SHA-256을 참조한다. 다른 저장소에 같은 이름으로 PUT하면 그 참조를 복원할 수 없다.

MinIO server-side bucket replication으로 버전 ID·파일·metadata를 전달한다.
공식 설계는 source/target의 버전 ID 보존을 명시한다. 현재 서버 소스
`9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`와 호환되는 공식 mc
`RELEASE.2025-08-13T08-35-41Z`를 고정한다. Linux x86_64 binary의 SHA-256은
`01f866e9c5f9b87c2b09116fa5d7c06695b106242d829a8bb32990c00312e891`이다.
[MinIO 설계](https://github.com/minio/minio/blob/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a/docs/bucket/replication/DESIGN.md),
[고정 mc release](https://github.com/minio/mc/releases/tag/RELEASE.2025-08-13T08-35-41Z).

백업은 별도 MinIO installation에 같은 이름의 새 bucket을 만든다. 양쪽 deployment ID를
대조하고 같은 서버·기존 target bucket·기존 source replication 설정은 거절한다. 기존 설정과
통합하는 일반 replication 관리 도구가 아니며 현재 command가 만든 sole rule만 정리한다.
설정 변경은 source endpoint별 로컬 파일 lock으로 직렬화하고 다른 호스트의 관리자는 별도로
조정해야 한다. S3 replication 설정에는 이 명령이 사용할 수 있는 비교 후 갱신 보장이 없다.

먼저 source의 모든 현재/이전 비삭제 object version을 열거하고 각각 bytes/SHA-256을 읽는다.
명시적 `existing-objects`만 활성화하며 삭제/metadata-sync는 전파하지 않는다. 임시 replication
rule과 remote target을 제거한 뒤 target의 고정 버전·길이·SHA가 맞는 manifest를 기록한다.
사후 검증은 source에 접근하지 않고 target deployment ID와 고정 version의 실제 내용을 확인한다.
백업 대상은 모든 열거된 버전이며 bucket 최신 조회의 delete-marker 가시성을 재현하지 않는다.
동시 쓰기가 있는 bucket 전체의 원자적 시점 스냅샷이나 PostgreSQL과의 결합 수용도 별도다.

mc의 마지막 rule을 `--id`로 제거하면 빈 rule 목록을 PUT하면서 거절되는 것을 실제로
확인했다. 현재 rule이 정확히 이번 명령의 sole rule인지 다시 확인한 경우만
DeleteBucketReplication을 사용한다. 고정 서버 구현은 연결된 remote target도 제거한다.
다른 rule이 발견되면 제거하지 않고 실패를 남긴다. 이 검사는 외부 관리자의 동시 변경을
원자적으로 방어하는 기능은 아니다.
[mc 구현](https://github.com/minio/mc/blob/RELEASE.2025-08-13T08-35-41Z/cmd/replicate-remove.go).

rule 없이 남은 remote target도 사전에 거절한다. 제거 직전에는 target 집합이 자신의 rule의
ARN 하나와 정확히 같은지 다시 확인한다. mc에는 이 orphan 조회 명령이 없어 고정 서버의
bucket 범위 `GET /minio/admin/v3/list-remote-targets`를 TLS/SigV4로 호출한다. 응답의 자격 증명은
출력/저장하지 않고 ARN 집합만 사용한다. 원본 region은 기본 us-east-1이며 명시할 수 있다.
[서버 handler](https://github.com/minio/minio/blob/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a/cmd/admin-bucket-handlers.go),
[SigV4](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html).

HTTPS의 CA 검증을 유지한다. source MinIO 자체도 replica의 CA를 신뢰해야 한다.
평문 HTTP는 loopback fixture에만 허용한다. 자격 증명은 명시적인 환경 변수와 mc alias로
전달하며 argv/공개 report에 넣지 않는다. bundle·진단은0700/0600, 검증 중 내려받는 내용은
비공개 임시 파일이다. 공개 CI artifact에는 개수·SHA·정리 여부 같은 시험 요약만 남긴다.

실패 시 자신의 replication rule을 정리하고 manifest를 발행하지 않는다. 일부 복제된 target
bucket은 보존하여 운영자가 점검할 수 있게 한다. 프로세스 강제 종료 때는 자동 정리를 보장하지
않는다. 원격 보관·암호화·보존 기간·별도 장애 영역과 주기 실행은 운영 정책으로 후속 구현한다.

전체 복구 게이트는 DB archive를 새 DB로 복원하고 **그 DB가 참조하는 모든 result/checkpoint
버전**이 replica manifest와 실제 S3에 있는지 검증해야 한다. 현재 object-version 백업 성공을
그 교차 검증으로 대신하지 않는다. Secret/CA·broker/device journal·실행 중 producer 경계와
서비스 활성화도 [M9의 남은 범위](../m9-requirements.md)다.
