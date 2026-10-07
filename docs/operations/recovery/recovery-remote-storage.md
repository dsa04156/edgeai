# 회수한 Remote 파일을 복구 저장소에 등록

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[Remote 파일 회수](recovery-remote-outputs.md)의 완료 bundle과 [S3 버전 백업](../backup/storage-backup.md)의
manifest를 사용한다. 대상은 백업 manifest의 별도 MinIO 설치이며 기존 artifact 버킷의
versioning이 Enabled여야 한다. 기존 버킷/설정/객체를 삭제하거나 바꾸지 않는다.

개인 환경에서 다음 값을 설정한다. 자격을 명령행·Git·공개 evidence에 넣지 않는다.

| 환경변수 | 값 |
|---|---|
| EDGEAI_BACKUP_STORAGE_URL | 복구 대상 HTTPS origin |
| EDGEAI_BACKUP_STORAGE_USER / PASSWORD | 대상 저장소 조회·조건부 PUT·설치 ID 조회 권한을 가진 자격 |
| EDGEAI_BACKUP_CA_FILE | 대상 CA 파일. 생략하면 시스템 CA 사용 |
| EDGEAI_BACKUP_STORAGE_REGION | 기본 us-east-1 |

## 실행

```bash
bash scripts/ops/recovery-remote-storage.sh publish \
  --bundle /private/remote-results \
  --storage-backup /private/storage-backup \
  --bucket edgeai-artifacts \
  --certificate-sha256 <확인한-대상-인증서-SHA256> \
  --output /private/remote-publication-new

bash scripts/ops/recovery-remote-storage.sh verify \
  --bundle /private/remote-results \
  --storage-backup /private/storage-backup \
  --bucket edgeai-artifacts \
  --certificate-sha256 <동일-대상-인증서-SHA256> \
  --receipt /private/remote-publication-new/publication.json \
  --output /private/remote-publication-check-new
```

## 결과 확인과 제한

이 wrapper는 `.env`를 자동으로 읽지 않는다. 위의 명시적 복구 저장소 환경을 사용하며 DB/
Remote 자격은 필요 없다. `--timeout`은 전체 대상 저장소 통신의10..3600초 제한(기본300)이다.
bucket은 원래 artifact 버킷 이름이며 backup manifest에 있어야 한다. 원본 설치에 등록하려고
하면 거절한다. 실제 배포의 환경값과 확인한 인증서 지문을 사용한다.

`publish`는 기존 정상 객체의 정확한 version을 재사용한다. 현재 객체가 없을 때만 조건부
PUT을 보내고 새 version을 실제 GET/크기/SHA로 검증한다. 내용이 다른 기존 객체는 거절한다.
응답 유실/부분 실패는 객체가 남을 수 있으므로 삭제하지 말고 같은 입력·새 출력 경로로
재실행한다. 기존 receipt가 있으면 `verify`로 해당 version을 확인할 수 있다.

- 종료0 / REMOTE_OUTPUTS_PUBLISHED: 전체 파일의 고정 version을 개인 `publication.json`에 기록했다.
- 종료0 / REMOTE_PUBLISHED_VERSIONS_VERIFIED: receipt의 전체 매핑과 실제 고정 version을 재검증했다.
- 종료2 / BLOCKED: 대상/파일/조건부 쓰기·통신·versioning·TLS 확인이 실패했다. 남은 객체와 intent를 보존한다.
- 종료1 / FAIL: 입력/receipt/로컬 파일 등의 형식·일치 검사에 실패했다.

개인 intent/receipt는700 디렉터리·600 파일이며 덮어쓰지 않는다. `createdVersions`와
`reusedVersions`를 구분한다. verify는 latest 대신 receipt의 version ID를 사용한다.
기존 확정 Result/체크포인트 전체의 보존은 [DB 참조 검증](recovery-references.md)을 별도로
확인해야 한다. 이 명령은 DB Result·Task·Run 상태를 바꾸거나 서비스를 재개하지 않는다.
[ADR0077](../../adr/0077-recovery-remote-storage.md), [실제 검증](../../evidence/m9-recovery-remote-storage.md)을 따른다.
