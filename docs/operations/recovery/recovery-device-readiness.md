# 장치 복구 데이터와 원본 브로커 권한 확인

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

[journal/DB 대조](recovery-device-journal.md), [고정 객체 백업](../backup/storage-backup.md),
[원본 MQTT 차단](recovery-mqtt-fence.md)을 연결해 복원 입력을 확인한다. 매번 실제 저장소와
브로커를 다시 조회하며 과거 성공 보고서만으로 통과시키지 않는다.

## 실행

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-환경>/bin/python \
  bash scripts/ops/verify-recovery-device.sh \
  --database edgeai_restore_example \
  --restore-report /private/postgres-restore/restore-report.json \
  --journal-restore /private/device-restore \
  --run-id '<원래 Run UUID>' \
  --storage-input /private/storage-backup \
  --mqtt-state-directory /private/original-mqtt-fence-state \
  --mqtt-ca-file /private/original-broker-ca.crt \
  --mqtt-original-password-file /private/original-admin.password \
  --recovery-id '<MQTT 차단에 사용한 동일 복구 UUID>' \
  --timeout 60 \
  --output /private/new-device-readiness
```

## 결과 확인과 제한

PostgreSQL과 replica 접속 환경은 [DB/S3 참조 대조](recovery-references.md)와 같다.
`EDGEAI_BACKUP_STORAGE_URL/USER/PASSWORD`, 필요하면 `EDGEAI_BACKUP_CA_FILE`을 사용한다.
원본 MinIO 자격은 필요하지 않다. Compose PostgreSQL에는 `--transport compose`를 추가한다.
MQTT endpoint·leaf 인증서 pin·broker digest·교체한 관리자 비밀번호는 앞선 차단 명령의
비공개 `recovery.json`에서 읽는다. 같은 복구 UUID와 원래 비밀번호도 확인한다. 비밀번호나
state 원문을 명령 인자·Git·CI artifact로 옮기지 않는다.

검사는 다음 조건을 연결한다.

1. 정확한 복원 DB와 격리 journal의 세션·경로·처리 순번이 일치해야 한다. Run 설정과
   journal이 가리키는 generation의 broker digest도 같아야 한다.
2. DB의 모든 결과/checkpoint 참조를 replica의 고정 bucket/key/version/bytes/SHA로 읽는다.
   선택한 장치의 소비자 최신 checkpoint는 실제 JSON을 검증하고 serial/revision/실행 digest,
   입력 바인딩과 경로 순번, 상태 bytes/SHA까지 DB summary와 비교한다.
3. 실제 TLS 브로커에서 동일 복구 marker, 기본 deny, 무group, 전체 EdgeAI 계정의 disabled
   상태, 원래 관리자 자격의 거절을 확인한다. 장치 계정은 없거나 disabled여야 한다.
4. 저장소·브로커·DB/journal을 재관측해 입력이나 권한이 달라지면 결론을 폐기한다.

원본 로컬 장치의 journal 소유권 해제도 함께 확인하려면 [원본 장치 종료](recovery-device-source-retirement.md)를
먼저 실행하고 `--original-source`와 `--source-retirement-report`를 함께 추가한다. 동일 복구
UUID·최종 snapshot과 실제 잠금/파일 신원을 전후 대조한다. 이 경우 성공 상태는
`DEVICE_DATA_BROKER_AND_SOURCE_OWNER_VERIFIED`, `sourceJournalOwnerQuiescenceProven=true`다.
인자를 생략한 기본 검사의 source 소유권 검증은 false이며 한쪽 인자만 주면 실패한다.

성공 결과는 `readiness.json`의 `DEVICE_DATA_AND_ORIGINAL_BROKER_VERIFIED`/exit0이다.
충돌·관측 변경·차단 상태 미확정은 BLOCKED/exit2, 잘못된 입력이나 신원·파일 누락 등은
FAIL/nonzero다. output은 새 경로이며 비공개 진단에는 자격이 포함될 수 있다.

이 명령은 broker 정책을 고치거나 DB·journal을 변경하지 않는다. 장치 source의 격리
marker도 유지한다. `producerProcessQuiescenceProven`, `globalQuiescenceProven`,
`activated`는 false다. 원본 장치의 프로세스 종료, 다른 producer 회수, 새 권한 배정과
서비스 활성화는 별도 절차다. 이 보고서를 송신 재개 허가로 사용하지 않는다.

시험은 `EDGEAI_STREAM_PYTHON=... bash scripts/test/test-recovery-device-journal.sh
--verify-authority --minio-binary <실제 MinIO> --report <새 비공개 보고서>`로 실행한다.
고정 age/mc/Paho와 Mosquitto Dynamic Security plugin·OpenSSL이 필요하다. 비표준
Mosquitto 설치 변수는 기존 MQTT 차단 시험과 같다.
[실제 27개 검증과 경계](../../evidence/m9-device-recovery-authority.md)를 참고한다.
원본 소유권 검증을 포함한 후속 [33개 결합 검증](../../evidence/m9-device-source-retirement.md)도 제공한다.
