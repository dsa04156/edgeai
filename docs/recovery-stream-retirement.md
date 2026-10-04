# 복원 DB의 원본 STREAM 경로 종료

[원본 MQTT 차단](recovery-mqtt-fence.md) 뒤 복원 DB의 같은 broker generation을 종료한다.
원본 브로커를 실제 TLS로 다시 조회하고 기존 관리자 자격 거절과 모든 실행 계정 차단을
확인한다. 과거 성공 보고서만으로 DB를 변경하지 않는다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/retire-recovery-stream.sh \
  --database edgeai_restore_example \
  --restore-report /private/postgres-restore/restore-report.json \
  --broker-digest 'sha256:<원래 broker digest>' \
  --mqtt-state-directory /private/original-mqtt-fence-state \
  --mqtt-ca-file /private/original-broker-ca.crt \
  --mqtt-original-password-file /private/original-admin.password \
  --recovery-id '<MQTT 차단과 동일한 복구 UUID>' \
  --timeout 60 \
  --output /private/new-stream-retirement
```

현재 프로젝트 DB 환경을 읽는다. Compose는 `--transport compose`, 비표준 PostgreSQL
실행 경로는 `--pg-bin`으로 지정한다. output은 새 디렉터리다. 관리자 비밀번호 값이나 개인
복구 state를 명령 인자·Git·CI artifact에 넣지 않는다.

`retirement.json`의 `ORIGINAL_BROKER_GENERATIONS_RETIRED`/exit0은 해당 broker의 경로
종료 반영과 재관측 성공이다. `generationsFenced`와 `generationsClosed`가 실제 변경 수다.
PREPARING/ACTIVE는 REPLACED로 fence한 뒤 닫고 기존 FENCED 이유/시각은 보존한다.
이미 CLOSED인 이력은 그대로이며 재실행의 변경 수는 0이다. `unresolvedGenerations`에
다른 broker의 열린 경로가 있으면 그 브로커의 별도 차단/종료가 필요하다.

DB/원본 broker 상태가 바뀌거나 잠금·통신 조건을 확인할 수 없으면 성공을 반환하지 않는다.
transaction 오류는 원복되지만 COMMIT 응답 유실·COMMIT 이후 외부 권한 변경의 경우 이미
DB가 변경됐을 수 있다. `failure.json`과 `intent.json`을 보존하고 원본 broker 차단을 확인한
뒤 동일 복구 UUID와 새 output으로 재실행한다. DB marker를 지우거나 일반 API를 켜지 않는다.

이 명령은 데이터·checkpoint·완료 이력·Task/Run 업무 결과와 원본 세션을 보존한다.
producer 프로세스 종료, 종합 STREAM 그룹 복구·새 Secret/grant·서비스 재개는 별도다.
`globalQuiescenceProven`, `producerProcessQuiescenceProven`, `activated`는 false다.

검사는 기존 장치 결합 시험에 `--retire-routes`를 추가한다. 실제 PG·TLS broker·MinIO·age와
공개 STREAM 데이터 및 별도 복원본을 사용한다. 상세 계약은 [ADR0091](adr/0091-recovery-stream-broker-retirement.md)을 따른다.
[실제47개 검증 근거](evidence/m9-recovery-stream-retirement.md)를 참고한다.
