# 복원 Kubernetes BATCH 성공 결과 반영

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[실행 종료 조정](recovery-kubernetes-retirement.md) 후 V35 또는 V36 격리 DB에서 실행한다.
기본 대상은 Kubernetes BATCH Result다. V36 VD 자식은 [VD 복원 절차](recovery-vd-results.md)의
`--vd-tasks` 옵션으로 명시적으로 선택한다.
봉인된 STREAM 결과는 [STREAM 복원 절차](recovery-stream-results.md)의
`--stream-results`와 원래 broker 증거를 함께 지정한다.
원래 시작 허가·확정 Result 기록과 출력 파일이 같은 독립 S3 백업에 있어야 한다.
실행이 전환 target이면 먼저 원래 시작 기록으로 전환 성공을 조정한다.

## 실행

```bash
export EDGEAI_BACKUP_STORAGE_URL=https://recovery-storage.example:9000
export EDGEAI_BACKUP_STORAGE_USER=operator
# EDGEAI_BACKUP_STORAGE_PASSWORD는 개인 비밀 관리 경로로 전달한다.
export EDGEAI_BACKUP_CA_FILE=/private/recovery-ca.crt

bash scripts/ops/recovery-kubernetes-results.sh \
  --context '<대상 Kubernetes context>' \
  --namespace '<격리 namespace>' --namespace-uid '<원래 namespace UUID>' \
  --recovery-id '<종료 작업 UUID>' \
  --database edgeai_restore_example \
  --restore-report /private/restore/restore-report.json \
  --termination-report /private/termination/termination-report.json \
  --runtime-start-backup /private/storage-backup \
  --runtime-start-bucket edgeai-artifacts \
  --runtime-start-certificate-sha256 '<백업 leaf 인증서 SHA256>' \
  --runtime-id '<복원 DB에 기록된 runtime UUID>' \
  --unclaimed-jobs \
  --output /private/new-kubernetes-result-recovery
```

## 결과 확인과 제한

`--runtime-id`를 반복해 대상을 명시한다. `--unclaimed-jobs`는 DB에 Job UID는 있지만
producer claim이 없는 백업을 허용한다. 실제 Job 자식 명단·종료·원래 시작 및 확정 결과가
모두 일치해야 producer 이력이 복원된다. 관측만으로 실행 성공이나 시작 허가를 만들지 않는다.
`runtime-start-*` 세 옵션은 기존 시작 기록 검사와 같은 백업·bucket·TLS 신원을 지정하며
이 명령에서는 Result 기록과 파일도 해당 백업에서 읽는다.

native `--pg-bin` 또는 `--transport compose`를 사용한다. 개인 출력 디렉터리는 새 경로여야
한다. `--timeout` 기본120초는 각 저장소 검증 연결의 제한이며 DB 잠금5초/statement30초다.
성공은 exit0, `KUBERNETES_RESULTS_COMMITTED`다. `results.json`의 결과/claim 복원,
자식 준비, Run 집계, 발행 완료 수와 `activated:false`를 확인한다. 후속 Task는 대기만 하며
새 runtime·Job을 만들지 않는다. 모든 값이0인 재실행은 타임스탬프도 변경하지 않는다.

조건 미충족/연결 오류는 exit2, 다른 오류는 exit1이다. `failure.json`, 개인 `intent.json`과
transaction을 보존한다. `databaseModified:null`이면 COMMIT이 반영됐을 수 있으므로
같은 입력을 새 출력 경로로 다시 검증한다. 고정 파일이나 보존 Pod를 지우지 않는다.

현재 head가 다른 시작/결과 기록, 고정 파일 유실, 기존 결과의 ID/시각/내용 불일치,
취소·실패·재시도·더 새로운 Attempt·옵션 없는 STREAM·활성 전환·복원본에 없는 관측 실행은 거절한다.
이 명령은 일부 선택 결과의 복원이다. 전체 참조, 다른 producer, API/장치 권한과 종합
재가동 검증은 [M9 수용 범위](../../requirements/m9-requirements.md)에 남는다. 설계는 [ADR0104](../../adr/0104-recovery-kubernetes-results.md)를 따른다.
