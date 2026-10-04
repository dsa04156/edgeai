# 원본 S3의 진행 중 요청 종료 확인

[원본 root 차단](recovery-storage-fence.md)을 완료한 뒤, 기존 자격으로 이미 시작된 요청이
남아 있지 않은지 확인한다. 현재 단일 MinIO 서버용이며 설정 변경이나 서비스 재시작을 하지 않는다.

```bash
bash scripts/verify-recovery-storage-drain.sh \
  --state-directory /private/recovery/storage-state \
  --ca-file /private/recovery/source-ca.crt \
  --root-password-file /private/recovery/original-root.password \
  --output /private/recovery/storage-drain-attempt-1 \
  --timeout 120
```

state directory에는 앞선 명령의 `recovery.json`, `confirmed.json`, `.lock`이 있어야 한다.
파일·폴더는 소유자 전용이며 같은 복구 중 대상/자격을 교체하지 않는다. endpoint·인증서 pin·
deployment·복구 UUID는 확정된 state에서 읽는다. 출력은 새 private directory여야 한다.
timeout은10–300초다.

원래 root의 S3 거절과 새 복구 관리자의 조회를 유지하면서 서버의 실제 진행/대기 수를 확인한다.
완료 누적수가 갱신되는0 표본 두 개를 관측해야 성공한다. 값0을 생략하는 MinIO의 형식과
갱신되지 않은 응답을 구분한다. 자세한 판단은 [ADR0072](adr/0072-recovery-source-storage-request-drain.md).

`drain-report.json`을 확인한다.

- exit0 / `SOURCE_STORAGE_REQUESTS_DRAINED`: 단일 서버에서 새로 확인한 요청 소진 근거다.
  `inFlightRequestsDrained=true`, `remainingInFlight=0`, `remainingQueued=0`이다.
- exit2 / `BLOCKED`: 요청이 남음, deadline/조회 실패, 불완전하거나 오래된 측정값, 여러 서버,
  root 재개방·필수 비공개 파일 접근 불가 등으로 종료를 확인하지 못했다. 차단은 해제하지 않는다.
- exit1 / `FAIL`: 잘못된 신원·상태·입력 또는 다른 오류다. 성공 보고서로 사용하지 않는다.

원본 DB·Kubernetes·Remote·장치 producer 중지 후 남은 연결이 끝나면 같은 state와 새 output으로
재실행한다. timeout을 요청 종료로 간주하거나 임의로 진행 수를0으로 대체하지 않는다.
이미 인증된 요청은 root 차단 후 저장을 완료할 수 있으므로 **소진 확인 뒤 고정 S3 참조와
파일/체크포인트를 다시 대조**해야 한다. 보고서는 그 관측 시점의 근거이고 재가동 허가는 아니다.

새 복구 사용자 자격·mc 설정·관리 응답은 비공개로 보관한다. `globalQuiescenceProven`과
`activated`는 false다. 외부 IdP/STS·내부 복제/lifecycle·분산 MinIO·전체 복구 수용은 후속이다.
[실제25개 결합 시험](evidence/m9-recovery-storage-drain.md)은 원래 root 차단18개에 요청 소진 검증을 더한다.
