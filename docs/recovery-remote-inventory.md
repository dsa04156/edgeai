# 복원 DB와 참조 Remote 이력 점검

먼저 [Remote 차단 절차](recovery-remote-fence.md)로 동일 설치/복구 ID의 차단과 계산 종료를
확인한다. [DB 복원](postgres-backup.md)의 새 `edgeai_restore_*` DB는 활성화하지 않는다.
이번 명령은 제공자와 DB를 읽기만 하며, 누락을 새 작업으로 채우거나 이력을 변경하지 않는다.

```bash
bash scripts/inspect-recovery-remote.sh \
  --database edgeai_restore_<복원명> \
  --restore-report /private/db-restore/restore-report.json \
  --endpoint https://remote.example:8443 \
  --provider-key reference \
  --provider-id <확인한-설치-UUID> \
  --recovery-id <동일-복구-UUID> \
  --ca-file /private/remote-ca.pem \
  --certificate-sha256 <확인한-인증서-SHA256> \
  --recovery-token-file /private/recovery.token \
  --output /private/remote-inventory-new
```

로컬 DB 설정은 `.env`에서 읽는다. Compose PostgreSQL은 `--transport compose`를 추가한다.
기존 Run/Attempt에 고정한 endpoint·CA 파일 bytes·provider key를 그대로 사용한다. 인증서
지문은 실제 연결 socket에서 자격 전송 전에 검증한다. `--page-size`는1..100(기본100),
`--timeout`은10..300초(기본120)다. 이전 출력 경로는 덮어쓰지 않는다.

`inventory.json`에는 DB snapshot/OID·복원 보고서 SHA256·선택한 binding·전체 provider 집계·
페이지를 합친 목록 SHA256·할당별 분류·다른 target이 기록된다. 업무 입력과 자격은 포함하지 않는다.
파일에는 작업 신원과 출력 metadata가 있으므로 개인 출력 경로에서 보관한다.

- 종료0 / `OBSERVED_REMOTE_INVENTORY`: 선택한 대상의 모든 조회를 마쳤고 신원/관측 대조가 일치한다.
- 종료2 / `REVIEW_REQUIRED`: 누락·충돌·다른 target을 찾았다. 전체 목록에서 원인을 확인한다.
- 종료2 / `BLOCKED`: 통신·차단/종료 미확인·미지원 schema 등으로 목록을 완성하지 못했다.
- 종료1 / `FAIL`: 잘못된 복원 보고서·입력 등이다. 새 성공 inventory는 없다.

어떤 결과도 서비스 활성화나 DB 상태 변경을 수행하지 않는다. `ABSENT_FROM_RESTORED_DATABASE`는
백업 이후 생긴 작업일 수 있으며, 원래 DB에 없었다고 자동 삭제하지 않는다. DB에 같은 ID가
있어도 대상 binding이 다르면 `BINDING_CONFLICT`다. 외부 provider·운영 키 회전·journal·종합
복구는 별도이며 상세 분류는 [ADR0074](adr/0074-recovery-remote-inventory.md)를 따른다.

검증 명령은 `bash scripts/test-recovery-remote-inventory.sh [--transport compose]`다.
실제 API/DB 백업·복원·TLS 제공자 시험을 사용하며 [근거](evidence/m9-recovery-remote-inventory.md)에
부분 fixture와 완료 범위를 명시한다.
