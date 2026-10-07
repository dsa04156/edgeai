# Remote 복구 결과 파일 회수와 독립 검증

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[제공자 차단](recovery-remote-fence.md) 후 복원 DB에 아직 확정하지 못한 성공 파일을
개인 복구 묶음으로 보존한다. [실행 정리](recovery-remote-retirement.md) 전후에 사용할 수 있으며,
명령 자체가 실제 DB·TLS 전체 이력과 선택한 binding을 다시 대조한다.

## 실행

```bash
bash scripts/ops/recovery-remote-outputs.sh recover \
  --database edgeai_restore_<복원명> \
  --restore-report /private/db-restore/restore-report.json \
  --endpoint https://remote.example:8443 \
  --provider-key reference \
  --provider-id <확인한-설치-UUID> \
  --recovery-id <동일-복구-UUID> \
  --ca-file /private/remote-ca.pem \
  --certificate-sha256 <확인한-인증서-SHA256> \
  --recovery-token-file /private/recovery.token \
  --output /private/remote-results-new

bash scripts/ops/recovery-remote-outputs.sh verify --input /private/remote-results-new
```

## 결과 확인과 제한

`recover`의 DB 설정은 `.env`에서 읽으며 Compose는 `--transport compose`를 추가한다.
원래 binding의 endpoint·CA bytes·provider key와 같은 설치/복구 UUID를 사용한다.
`verify`는 `.env`·DB 접속·제공자 연결 없이 동작한다. 출력 디렉터리 전체를700/파일600으로
함께 보관해야 하며 기존 디렉터리는 덮어쓰지 않는다.

- `REMOTE_OUTPUTS_RECOVERED` / 종료0: 미확정 SUCCEEDED 전체의 실제 bytes를 검증·보존하고 전후 DB/제공자 이력도 일치한다.
- `REMOTE_OUTPUT_BUNDLE_VERIFIED` / 종료0: 현재 개인 bundle의 intent·manifest·전체 파일이 서로 일치한다.
- `BLOCKED` / 종료2: live 신원/차단/목록/응답 불일치·통신·파일 접근 거절 등이다. 개인 실패 정보를 확인한다.
- `FAIL` / 종료1: 잘못된 복원/manifest·파일 손상·입력/DB 오류 등이다. 성공으로 취급하지 않는다.

`intent.json`은 회수 전 검증한 이력, `manifest.json`은 선택한 모든 성공 관측과 대상 binding,
`objects`는 SHA256 이름의 실제 파일, `verification.json`은 독립 파일 검증 결과다.
manifest가 없는 경로는 완료된 묶음이 아니다. 실패 후에는 같은 복구 ID·새 출력 경로로
처음부터 재조회한다. 마지막 receipt 기록만 실패했다면 독립 `verify`로 현재 묶음을 확인한다.
미확정 성공이 없으면 파일0개도 명시적인 빈 묶음으로 검증한다.

이미 확정한 Result는 `committedResultsRetained`로 기록하고 회수 대상에서 제외한다.
그 파일의 복구는 [고정 S3 참조 대조](recovery-references.md)를 따른다. 이 명령은 DB·제공자를
수정하거나 S3에 올리지 않으며 Task/Run 성공 확정·재시도·서비스 재개를 실행하지 않는다.
독립 검증은 개인 묶음의 내부 일치이며 외부 서명/원본 인증은 아니다. 신뢰하는 보관 경로에서
전체 묶음과 manifest SHA256을 함께 관리한다. [ADR0076](../../adr/0076-recovery-remote-outputs.md),
[실제 검증](../../evidence/m9-recovery-remote-outputs.md)을 참고한다.
