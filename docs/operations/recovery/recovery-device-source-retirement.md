# 원본 장치 source 종료와 최종 데이터 보존

> **심화 운영 절차** — 격리된 복원 환경에서 사용하는 구성 요소별 절차입니다.
> 적용할 schema·대상 신원·선행 보고서는 아래 조건을 따릅니다. 이 명령의 성공만으로 서비스를 재가동하지 않습니다.
> 전체 순서는 [백업과 복구 안내](../backup-and-recovery.md)를 확인하세요.

## 목적과 선행 조건

같은 호스트의 원본 `DeviceSource` journal을 종료한다. 원본 source와 격리 복원본은
동일 Device Session·출력 경로·generation이어야 한다. Linux 로컬 파일 잠금을 사용하며
디렉터리0700/파일0600과 현재 사용자 소유를 요구한다. 원격 장비의 물리 종료 명령은 아니다.

## 실행

```bash
bash scripts/ops/retire-device-source.sh \
  --source /private/original-device-source \
  --journal-restore /private/device-restore \
  --recovery-id '<원본 MQTT 차단과 동일한 복구 UUID>' \
  --timeout 30 \
  --output /private/new-device-retirement
```

## 결과 확인과 제한

명령은 원본 `journal/retirement.json`을 영속 기록한다. 이 버전의 SDK는 실행 중 marker를
확인해 연결과 journal을 닫으며 원래 journal의 재시작도 거절한다. 명령은 원래 owner.lock을
실제로 획득할 때까지 기다린다. SDK 호출을 멈춘 프로세스나 이전 SDK가 잠금을 계속 잡으면
BLOCKED/exit2이며 marker는 남는다. 해당 장치의 정상 종료 후 같은 복구 UUID와 새 output으로
재시도한다. marker를 지우거나 owner.lock 파일을 교체하지 않는다.

`retirement-report.json`의 `DEVICE_SOURCE_OWNER_RETIRED`/exit0은 원본 journal 소유권
해제 관측이다. 데이터는 그대로 남는다. `snapshotMatchesSuppliedRestore=false`이면
지정한 복원본에 최종 프레임이 빠질 수 있으므로 원본에서 [백업·격리 복원](../backup/device-journal-backup.md)을
다시 실행하고 새 복원본을 사용한다. 종료 뒤에도 백업 명령은 원본을 읽을 수 있다.

[장치 복구 결합 검사](recovery-device-readiness.md)의 기존 DB/S3/MQTT 인자에 아래 두
인자를 함께 추가한다.

```bash
--original-source /private/original-device-source \
--source-retirement-report /private/new-device-retirement/retirement-report.json
```

`--journal-restore`는 종료 시점의 최종 snapshot과 같아야 한다. 검사는 같은 호스트/부팅,
DB·잠금 파일 inode, 복구 UUID·marker와 실제 잠금 해제, 전체 논리 snapshot SHA를
검사 전후에 다시 확인한다. 원본이 없어졌거나 바뀌면 옛 보고서로 통과하지 못한다.
성공 상태는 `DEVICE_DATA_BROKER_AND_SOURCE_OWNER_VERIFIED`다. 두 인자를 생략하면
기존 데이터·브로커 검사만 수행하고 source 소유권 검증은 false다.

이 결과는 원본 journal 소유권 해제이며, 다른 프로세스·API 권한·장치 전체 종료 증명이
아니다. 격리 복원본은 계속 실행 불가이며 새 권한·서비스 활성화는 별도다. 원본과 개인
복구 보고서를 Git/CI artifact에 올리지 않는다.

회귀 시험은 `bash scripts/test/test-device-source-retirement.sh --report <새 비공개 보고서>`다.
고정 age 설치와 실제 Linux SQLite/flock·별도 writer 프로세스를 사용한다.
