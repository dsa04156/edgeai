# 복원 DB의 Remote 실행 정리

[Remote 차단](recovery-remote-fence.md)과 [전체 이력 대조](recovery-remote-inventory.md) 뒤,
격리된 복원 DB에 확인한 종료 사실을 기록한다. 아래 명령 자체가 DB·실제 TLS 전체 이력을
다시 조회하므로 예전 inventory 파일로 현재 관측을 대체할 수 없다.

```bash
bash scripts/retire-recovery-remote.sh \
  --database edgeai_restore_<복원명> \
  --restore-report /private/db-restore/restore-report.json \
  --endpoint https://remote.example:8443 \
  --provider-key reference \
  --provider-id <확인한-설치-UUID> \
  --recovery-id <동일-복구-UUID> \
  --ca-file /private/remote-ca.pem \
  --certificate-sha256 <확인한-인증서-SHA256> \
  --recovery-token-file /private/recovery.token \
  --output /private/remote-retirement-new
```

DB 접속 설정은 `.env`에서 읽는다. Compose는 `--transport compose`를 추가한다.
기존 Run/Attempt의 endpoint·CA bytes·provider key와 확인한 설치/복구 UUID를 유지한다.
`--timeout`10..300초는 TLS 조회 제한이며 DB 잠금은5초·SQL 문장은30초다. 큰 이력 때문에
제한을 넘으면 성공으로 간주하지 않는다. 출력 경로는 매번 새로 지정한다.

성공하면 해당 Remote 관측을 반영하고 runtime을 STOPPED/TERMINATED로 바꾸며 기존
CREATE/DELETE 명령을 완료 처리한다. 확정 결과·artifact·Task/Attempt/Run·명령 ID/시도
횟수는 보존한다. 새 작업/재시도는 만들지 않으며 복원 DB의 일반 기동 차단도 유지한다.

| 결과 | 의미와 후속 |
|---|---|
| 종료0 / REMOTE_RUNTIMES_RETIRED | 커밋 후 DB·TLS 재조회까지 일치. `retirement.json`의 변경 개수를 확인한다. |
| 종료2 / BLOCKED | 제공자 차단/종료·전체 이력·binding 등이 불일치하거나 통신에 실패했다. 격리를 유지하고 원인을 확인한다. |
| 종료1 / FAIL | 입력/DB 오류·동시 변경·잠금 제한 등. 개인 진단과 `failure.json`을 확인한다. |

`intent.json`은 DB 쓰기 전의 신원·개인 inventory·행 해시다. `retirement.json`은 커밋 뒤 확인
영수증이다. `failure.json`의 `databaseModified:false`는 쓰기 단계 전 거절이고, `null`이면
커밋 여부를 단정하지 못한다. 응답 유실 때 원복됐다고 가정하지 말고 동일 입력·ID와 새 출력
경로로 재실행한다. 이미 반영됐다면 변경0으로 검증하며 timestamp·이력을 다시 쓰지 않는다.
기존 intent/receipt를 덮어쓰거나 다른 복구 ID로 우회하지 않는다. 이 파일들은 공개 저장소에 넣지 않는다.

미반영 성공 파일 회수·workflow 상태 확정·재시도·다른 producer/journal·종합 활성화는 남는다.
전체 복구가 끝나기 전 DB marker 제거·일반 API/worker 기동을 하지 않는다.
상세 계약은 [ADR0075](adr/0075-recovery-remote-retirement.md), 시험은
`bash scripts/test-recovery-remote-retire.sh [--transport compose]`와 [검증 근거](evidence/m9-recovery-remote-retirement.md)를 따른다.
