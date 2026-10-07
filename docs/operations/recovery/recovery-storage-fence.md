# 원본 MinIO root 접근 차단

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

현재 API가 사용하는 root 자격과 그 자격으로 발급한 S3 URL을 차단하는 복구 명령이다.
DB·MQTT·Kubernetes 중지와 함께 조합할 구성 요소이며 전체 서비스 복원 명령은 아니다.
[ADR0071](../../adr/0071-recovery-source-storage-root-fence.md)을 따른다.

## 실행

```bash
bash scripts/dev/install-minio-client.sh
bash scripts/ops/fence-recovery-storage.sh \
  --endpoint https://storage.example.internal:9000 \
  --ca-file /private/recovery/source-ca.crt \
  --certificate-sha256 '<원본 leaf DER 인증서 SHA256 64hex>' \
  --deployment-id '<원본 MinIO deployment UUID>' \
  --root-user '<원래 root access key>' \
  --root-password-file /private/recovery/original-root.password \
  --recovery-id '<이번 복구의 UUID>' \
  --state-directory /private/recovery/storage-state \
  --output /private/recovery/storage-attempt-1
```

## 결과 확인과 제한

인증서와 deployment ID는 대상 설치에서 미리 확인한 값이어야 한다. 비밀번호 파일은 소유자만
읽을 수 있는 일반 파일이며 줄바꿈 없이 정확한 원래 비밀번호를 담는다. 비밀번호를 인자나 셸
출력으로 전달하지 않는다. 기본 region은 `us-east-1`, `--timeout`은10–300초, 기본120초다.
CA 검증을 끄거나 HTTP로 실행하는 옵션은 없다.

성공하면 원래 root의 S3·관리 요청과 기존 presigned URL이 거절된다. 파일 내용과 고정 버전은
유지되며 새 복구 사용자로 조회할 수 있다. 새 자격은 `state-directory/recovery.json`에0600으로
보관한다. 관리 권한을 가진 비밀정보이므로 [암호화 백업](../backup/private-material-backup.md)의 대상으로
관리한다. 원래 `.env`, Kubernetes Secret, 실행 중 API의 설정은 자동으로 교체하지 않는다.

중단되면 **같은 recovery ID·state directory·대상 정보**와 새 `--output` 경로로 실행한다.
새 사용자가 이미 생성됐거나 root 차단 응답이 유실된 경우에도 재개할 수 있다. 상태 파일을
잃으면 이 명령으로 기존 복구 소유권을 인수할 수 없다. 외부에서 확인된 차단을 해제한 경우,
다른 복구 정책, IAM 사용자/group/service account, site replication, 익명 bucket 정책은
자동 정리하지 않고 거절한다.

종료 코드는0 성공,2 외부 거절/확인 불가,1 잘못된 신원·소유권·입력 또는 기타 실패다.
`fence-report.json`에는 비밀번호나 관리 응답이 없다. `client-*.log`와 mc 설정은 비공개 출력
폴더에만 남긴다. 실패하면 `fenceStateUnconfirmed=true`이며 root가 일부 시점에 계속 허용됐는지,
이미 차단됐는지 보고서의 실패만으로 추측하지 않는다. 자동 원복은 하지 않는다.

이 명령은 기존 자격으로 시작된 업로드의 종료, 외부 IdP/STS·복제·내부 writer 전체 중지,
분산 MinIO의 모든 노드 및 운영 재가동을 보장하지 않는다. 세 완료 필드
`inFlightRequestsDrained`, `globalQuiescenceProven`, `activated`는 항상false다.
다음 복구 단계에서 producer 종료와 진행 중 저장 요청의 소진을 증명하고 고정 참조를 대조해야 한다.
단일 MinIO의 S3 요청은 [별도 소진 검증](recovery-storage-drain.md)으로 확인한다. 원본 root
차단 보고서의 false 필드는 그대로 유지하고 두 결과를 함께 사용한다.

격리 검증은 `bash scripts/test/test-recovery-storage-fence.sh --minio-binary <시험 binary>`다.
별도 TLS MinIO와 합성 파일만 사용한다. [검증 결과](../../evidence/m9-recovery-storage-fence.md).
