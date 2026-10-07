# 백업과 복구

EdgeAI의 복구 대상은 DB뿐 아니라 고정 객체 버전, 인증 자료, 장치 journal과 외부 실행 주체입니다.
현재 도구는 각 대상을 확인·격리·복원하는 구성 요소를 제공합니다. 전체 플랫폼 자동 재가동은 별도 수용 범위입니다.

## 보호 대상 선택

| 대상 | 포함하는 데이터 | 별도로 확인할 항목 |
|---|---|---|
| PostgreSQL | 관리·실행·연결·감사 메타데이터 | 파일 객체와 외부 실행 상태 |
| MinIO | 결과·checkpoint의 고정 버전과 내용 | DB 참조와 키·접근 설정 |
| 비공개 자료 | 서명 키·인증 파일·CA | 암호화 키의 보관·접근 |
| 장치 journal | 세션·sequence·송신·완료 이력 | 원본 장치와 broker 권한 |

## 복구 순서 이해하기

1. 대상 환경과 복구 ID, 백업의 신원·버전을 확인합니다.
2. 원본과 분리한 새 DB·파일 경로에 복원합니다.
3. 조회 전용 점검으로 DB·파일·Kubernetes·Remote 이력을 대조합니다.
4. 필요한 원본 producer·접속 차단을 명시한 대상에 적용합니다.
5. 증거가 있는 종료·결과·업무 상태만 격리 복원본에 반영합니다.
6. 남은 참조·권한·실제 서비스 검증을 확인합니다. marker 삭제나 worker 활성화로 단계를 건너뛰지 않습니다.

차단 명령은 DB 연결, broker, MinIO 자격, 실제 Pod·계산에 영향을 줄 수 있습니다.
각 절차가 요구하는 namespace UID, context, 원본 신원과 선행 보고서를 유지합니다.
아래 순서는 개념 안내이며 모든 환경에서 그대로 실행하는 일괄 복구 스크립트가 아닙니다.

## 백업 절차

- [장치 journal 암호화 백업·격리 복원](backup/device-journal-backup.md)
- [PostgreSQL 백업·복원 실행](backup/postgres-backup.md)
- [정적 키 파일 백업·복원](backup/private-material-backup.md)
- [고정 버전을 보존하는 MinIO 백업](backup/storage-backup.md)

## 복원 점검부터 시작

- [복원 DB 조회 전용 점검](recovery/recovery-inspection.md)
- [DB와 객체 저장소 참조 대조](recovery/recovery-references.md)
- [Kubernetes 실행 대조](recovery/recovery-kubernetes.md)

## 구성 요소별 복구 참고

다음 문서는 각 구성 요소의 원래 세부 옵션과 제한을 보존합니다. 지원 schema와 선행 조건이 서로 다르므로 필요한 절차만 선택합니다.

- [복구 중 원본 PostgreSQL 연결 차단](recovery/recovery-database-fence.md)
- [복원한 장치 journal과 PostgreSQL 대조](recovery/recovery-device-journal.md)
- [장치 복구 데이터와 원본 브로커 권한 확인](recovery/recovery-device-readiness.md)
- [원본 장치 source 종료와 최종 데이터 보존](recovery/recovery-device-source-retirement.md)
- [복원 DB 조회 점검](recovery/recovery-inspection.md)
- [복원 Kubernetes BATCH 성공 결과 반영](recovery/recovery-kubernetes-results.md)
- [복원 DB의 Kubernetes 실행 정리](recovery/recovery-kubernetes-retirement.md)
- [복원 Kubernetes/VD 작업 상태 조정](recovery/recovery-kubernetes-workflows.md)
- [복원 DB와 현재 Kubernetes 실행 대조](recovery/recovery-kubernetes.md)
- [복구 중 원본 MQTT 접근 차단](recovery/recovery-mqtt-fence.md)
- [복구 중 Kubernetes producer 중지](recovery/recovery-producer-stop.md)
- [복원 DB와 MinIO 파일 참조 대조](recovery/recovery-references.md)
- [복원 Remote 실패·취소 작업 정리](recovery/recovery-remote-failures.md)
- [참조 Remote 제공자 복구 차단](recovery/recovery-remote-fence.md)
- [복원 DB와 참조 Remote 이력 점검](recovery/recovery-remote-inventory.md)
- [Remote 복구 결과 파일 회수와 독립 검증](recovery/recovery-remote-outputs.md)
- [복원 Remote 성공 결과 확정](recovery/recovery-remote-results.md)
- [복원 DB의 Remote 실행 정리](recovery/recovery-remote-retirement.md)
- [회수한 Remote 파일을 복구 저장소에 등록](recovery/recovery-remote-storage.md)
- [원본 S3의 진행 중 요청 종료 확인](recovery/recovery-storage-drain.md)
- [원본 MinIO root 접근 차단](recovery/recovery-storage-fence.md)
- [복원 DB의 누락 STREAM 완료 이력 반영](recovery/recovery-stream-completions.md)
- [복원 STREAM 확정 결과 반영](recovery/recovery-stream-results.md)
- [복원 DB의 원본 STREAM 경로 종료](recovery/recovery-stream-retirement.md)
- [복원 STREAM 그룹의 취소·재시도 기한 조정](recovery/recovery-stream-workflows.md)
- [복원 VD BATCH 성공 결과 반영](recovery/recovery-vd-results.md)

## 결과 판단

성공 보고서의 적용 범위와 `activated: false`를 확인합니다. DB가 복원됐다고 장치 송신·Run·외부 작업이 재개된 것은 아닙니다.
불명확한 COMMIT이나 응답 유실은 도구의 재검증 규칙을 따릅니다. 원본 파일과 보고서를 먼저 보존합니다.
전체 수용의 남은 범위는 [지원 범위](../reference/support.md)와 [개발 기준](../requirements/m9-requirements.md)을 확인합니다.
