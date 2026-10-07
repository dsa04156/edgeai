# 고정 버전을 보존하는 MinIO 백업

이 문서는 대상별 백업·격리 복원 절차입니다. 다른 보호 대상과 복구 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

`backup-storage.sh`는 source의 현재/이전 파일 버전을 별도 MinIO에 복제하고 버전 ID·길이·
SHA-256을 대조한다. `verify-storage-backup.sh`는 source가 없어도 backup의 같은 버전을 읽어
검증한다. [DB 백업](postgres-backup.md)과 연결한 전체 서비스 복구 판정은 아직 별도다.

생성한 sole replication rule과 remote target의 소유를 다시 대조한 뒤 해당 ARN으로
명시적 resync를 시작한다. 백그라운드 scanner가 기존 객체를 다시 발견하기를 기다리지
않으며, 모든 고정 버전의 실제 bytes/SHA 검증과 소유 규칙 정리 후에 성공을 기록한다.
`backup-report.json`의 `resyncsStarted`는 접수 수다.
[ADR0098](../../adr/0098-explicit-owned-storage-resync.md)을 따른다.

## 준비

```bash
bash scripts/dev/install-minio-client.sh
```

설치기는 공식 mc release와 SHA-256을 고정한다. 현재 Linux x86_64만 지원하며 다른 binary로
기존 `.tools/mc`를 덮어쓰지 않는다. 백업 대상 MinIO는 source와 다른 installation이어야 하고,
같은 이름의 target bucket은 아직 없어야 한다. source bucket은 versioning이 켜져 있어야 한다.
기존 source replication이 있으면 이 명령은 거절하므로 운영 중인 복제 설정을 먼저 검토한다.
rule 없이 남은 remote target도 기존 설정으로 취급해 거절한다. source 계정에는 이 목록을
조회하는 `admin:GetBucketTarget` 권한도 필요하다.

권한에는 양쪽 서버 식별 조회, source 버전 읽기·replication 설정, target bucket 생성·versioning·
replica 쓰기·고정 버전 읽기가 필요하다. 백업 설정 변경은 다른 관리자와 직렬화한다.
source에서 MinIO의 기존 버전 resync를 시작할 권한도 필요하다.
동일 호스트의 이 CLI는 source URL별 lock을 사용하지만 다른 호스트와의 분산 lock은 아니다.

아래 환경 변수는 권한0600의 별도 파일에 보관한 후 shell 환경으로 읽는다. 저장소의 일반
`.env`를 자동으로 읽지 않으므로 source/target 계정을 혼동하지 않고 명시적으로 선택할 수 있다.
실제 자격 증명을 shell 명령 인수·Git·CI artifact에 넣지 않는다.

| 환경 변수 | 값 |
|---|---|
| `EDGEAI_BACKUP_SOURCE_URL` | 자격 증명 없는 source HTTPS origin |
| `EDGEAI_BACKUP_SOURCE_USER` / `EDGEAI_BACKUP_SOURCE_PASSWORD` | source 계정 |
| `EDGEAI_BACKUP_STORAGE_URL` | 별도 backup MinIO의 HTTPS origin |
| `EDGEAI_BACKUP_STORAGE_USER` / `EDGEAI_BACKUP_STORAGE_PASSWORD` | backup 계정 |
| `EDGEAI_BACKUP_CA_FILE` | 필요한 경우 client가 신뢰할 PEM CA bundle |
| `EDGEAI_BACKUP_SOURCE_REGION` | source admin 조회의 SigV4 region, 기본 `us-east-1` |

source MinIO 서버의 CA 신뢰 설정에도 target 인증서의 CA가 필요하다. client CA만 설정해도
서버 간 replication의 TLS가 자동으로 구성되는 것은 아니다. 인증서 검증을 끄는 옵션은 제공하지
않는다. HTTP는 loopback 주소의 격리 시험에만 허용한다.

## 백업과 독립 검증

```bash
bash scripts/ops/backup-storage.sh --bucket edgeai-artifacts --output .tools/backups/storage-first
bash scripts/ops/verify-storage-backup.sh --input .tools/backups/storage-first
```

실제 source bucket 이름을 사용한다. 여러 bucket은 `--bucket`을 반복한다. source/target은
같은 bucket 이름을 사용하므로 DB의 불변 참조를 다시 쓰지 않는다. 대기 시간은 기본180초이며
`--timeout-seconds`로 복제 대기 예산을 지정할 수 있다. 개별 파일 읽기는 별도300초 제한이 있다.

성공하면0700의 bundle에0600 `manifest.json`과 `backup-report.json`이 생긴다. 실제 파일은
replica MinIO의 저장소에 있다. manifest만 복사하는 것은 데이터 백업이 아니다. 원본에 삭제가
발생해도 백업을 함께 삭제하지 않으며, 완료 후 자신의 replication rule/target을 제거한다.
복제 중 추가된 파일 버전이 target에 더 있을 수 있다. 보장은 manifest에 기록된 고정 버전이다.

`BACKED_UP_OBJECT_VERSIONS`와 `VERIFIED_OBJECT_VERSIONS`는 해당 버전들의 보존 판정이다.
delete-marker에 따른 bucket 최신 조회 상태·DB snapshot과의 일치·권한/키/journal·실행 중
작업의 활성화를 함께 검증한 결과는 아니다. 기존 bucket/백업 폴더를 재사용하지 않는다.

실패 시 비공개 진단 경로를 출력한다. `backup-report.json`의 `ownedRulesRemoved`와
`cleanupFailureTypes`를 확인한다. 부분 target bucket은 점검용으로 남으며 성공 manifest가
없으면 완료 백업으로 취급하지 않는다. 강제 종료 뒤 남은 rule과 bucket은 기록과 소유 관계를
확인해 정리한다. 자격 증명/서버 정보/파일 이름이 포함될 수 있는 원문 로그를 공개하지 않는다.

현재 backup 데이터의 암호화·별도 장애 영역/보관 기간/주기 실행은 운영 설정이 남아 있다.
새 DB의 `result_artifact`·`stream_checkpoint` 전체 version/bytes/SHA는
[DB/S3 대조 명령](../recovery/recovery-references.md)으로 확인한다. 이 대조 이후에도 운영 활성화 게이트는 남는다.

## 시험

```bash
bash scripts/collect-evidence.sh storage-backup bash scripts/test/test-storage-backup.sh
```

기존 개발 MinIO 대신 새로운 TLS MinIO 두 개·합성 파일·전용 CA/계정을 만든다. 기본 server
binary는 `.tools/minio`이며 `--minio-binary`로 명시할 수 있다. 원본 종료, replica 재시작,
기존 설정 거절과 검증 실패를 포함한다. 모든 소유 프로세스를 종료하고 파일은 비공개 `.tools`에
남긴다. CI는 같은 job에서 빌드한 MinIO 이미지의 binary를 추출해 실행하며 요약만 업로드한다.
[설계](../../adr/0060-version-preserving-storage-backup.md), [검증 근거](../../evidence/m9-storage-backup.md).
