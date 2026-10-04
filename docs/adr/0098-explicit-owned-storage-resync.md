# ADR 0098: 백업이 생성한 S3 대상의 명시적 재복제

상태: 채택, 실제 TLS 저장소11개 통과. 2026-10-05.

복원 STREAM 결합 시험 두 번이 기존 객체 복제120초 제한에서 실패했다. 독립 복제와
작은 복원 시험은 통과했으므로 복구 SQL의 실패와 구분했다. 고정 MinIO 소스는 기존
객체의 재복제를 백그라운드 scanner와 별도의 resync 작업으로 수행한다. 자체 시험 서버의
scanner를 `slowest`로 설정한 재현에서 기존 백업은 5초 제한을 넘었고 manifest를 발행하지
않았다. 스캔 진행에 의존하는 백업 경계를 확인했다.

ADR0060의 새 bucket/sole rule/remote target 소유 규칙을 유지한 채, rule 생성 직후
`mc replicate resync start origin/<bucket> --remote-bucket <ARN>`을 호출한다.
직전에 rule ID·Enabled·ExistingObjectReplication=Enabled·remote target 집합을 다시
대조한다. 이번 백업의 활성 대상 하나로 증명되지 않으면 resync를 시작하지 않는다.

resync 접수만으로 백업 성공을 판정하지 않는다. 캡처한 모든 원래 version ID의 실제
bytes/SHA-256을 대상에서 확인하고 소유 rule/target을 제거한 뒤에만 manifest를 발행한다.
실패 시 성공 manifest를 발행하지 않고 소유 규칙 정리와 부분 복제 bucket 보존을 따른다.
공개 backup-report의 `resyncsStarted`는 접수된 대상 수이며 완료된 버전 수와 별개다.

고정 mc의 지원 옵션을 실제 `--help`로 확인했다. source/target 시험 MinIO 모두
`MINIO_SCANNER_SPEED=slowest`로 시작한11개 실제 TLS 회귀가 통과했다. 기존/이전 버전,
빈 파일·한글 key·256KiB 파일, source 종료와 target 재시작, 기존 bucket/rule/orphan 보존,
잘못된 rule ID의 resync 거절, 검증 장애·누락/손상 거절을 포함한다.
[재현과 검증](../evidence/m9-storage-explicit-resync.md)을 따른다.
