# 검증

```bash
corepack pnpm --filter @edgeai/dashboard exec playwright install chromium
bash scripts/test/verify-all.sh scaffold
bash scripts/test/test-integration.sh  # 실제 PostgreSQL 필요
bash scripts/test/test-infra.sh        # Compose의 PostgreSQL·MQTT 필요
bash scripts/test/test-health.sh       # DB + API + Dashboard 실행 필요
bash scripts/test/test-profiles-stack.sh compose # 실제 Profile/Device/Workflow/Swagger UI + DB 장애·복구; 로컬 PG는 local
bash scripts/test/test-node-inventory.sh <context> # 기존 context는 변경하지 않고 실제 Node 목록만 읽음
bash scripts/test/test-storage.sh      # MinIO 실행 필요; 고유 probe bucket만 생성·제거
bash scripts/test/test-runtime-storage.sh # 실제 MinIO 버전·SHA-256·변조 거절
bash scripts/test/test-runtime-results.sh # PostgreSQL + MinIO + Mosquitto/Paho: 결과·Remote/VD·스트림 공동 완료
bash scripts/test/test-runner.sh       # 실제 Python 자식 프로세스 + 격리 HTTP fixture
bash scripts/test/test-stream.sh       # 고정 Paho Python 의존성 + 실제 Mosquitto: 다중 입력·복구·ACL·TLS
bash scripts/test/test-stream-broker.sh # 실제 PostgreSQL + Mosquitto dynamic security: 권한 수명·세대 전환
bash scripts/test/test-remote.sh       # 실제 Remote 참조 프로세스/HTTP/SQLite·파일·장애 시험
bash scripts/test/test-load.sh --measure-only # 별도 API/전용 PG DB: 100→300→1,000대 관리 부하 측정
bash scripts/test/test-load-acceptance.sh # 실제 API/DB로 측정·미정·실패·부분 규모 판정 회귀
bash scripts/test/test-postgres-backup.sh # 전용 DB/API의 실제 백업·새 DB 복원; Compose PG는 --transport compose
bash scripts/dev/install-minio-client.sh # object version을 유지하는 백업용 공식 mc 설치·SHA 검증
bash scripts/test/test-storage-backup.sh # 격리 TLS MinIO 두 개: 버전 복제·원본 유실·백업 재시작·거절 시험
bash scripts/test/test-recovery-references.sh # 실제 DB 복원·전체 결과/checkpoint 참조 대조·원본 유실·누락 거절
python3 scripts/internal/test-recovery-kubernetes.py # 복원 DB/실행 목록 분류·페이지 경계 회귀
bash scripts/test/test-recovery-kubernetes-live.sh --context <시험-context> # 실제 복원 DB와 별도 Kubernetes namespace
python3 scripts/internal/test-recovery-stop.py # 종료 상태·신원·불확실한 종료 거절 판정
bash scripts/test/test-recovery-stop-live.sh --context <시험-context> # 실제 부모/자식 종료·생성 차단·timeout/재개
bash scripts/test/test-recovery-kubernetes-retire.sh --context <시험-context> --vd-tasks # 실제 종료→복원 DB 실행·명령·VD binding/작업 할당 정리
bash scripts/test/test-recovery-database-fence.sh # 격리 DB/API의 연결 차단·다른 DB 보존·미완료 쓰기 rollback
bash scripts/test/test-recovery-mqtt-fence.sh # 격리 TLS broker의 관리자 교체·기존 연결 차단·중단/재개·재시작
bash scripts/test/test-recovery-storage-fence.sh # 격리 TLS MinIO의 root/URL 차단·실제 진행 요청 소진·버전 보존25개
bash scripts/dev/install-age.sh # 고정 공식 age 배포 파일·실행 파일 checksum 확인
bash scripts/test/test-private-material.sh # 합성 키의 실제 암호화·복원·손상/경로/덮어쓰기 거절
bash scripts/test/test-management-audit.sh # 격리 API/DB의 감사 접수·결과 저장 실패·재시작·비밀값 배제
```

`verify-all.sh local|full`은 미구현 fault/hardware 시험과 부하 성능 기준 미정을 숨기지 않고 nonzero를 반환합니다.
관리 부하의 측정 범위·실행 방법·합격 판정은 [부하 시험 문서](load-testing.md)를 따릅니다.
DB 백업과 새 DB로의 복원은 [백업 실행 문서](../operations/backup/postgres-backup.md)를 따릅니다.
관리 API의 변경 접수와 HTTP 결과는 [감사 기록 안내](../operations/management-audit.md)를 따릅니다.
Swagger의43개 관리 operation에 감사 목록/UUID 조회를 포함하며 HTTP 응답과 실제 작업 완료를 구분합니다.
고정 S3 버전 복제와 독립 검증은 [MinIO 백업 문서](../operations/backup/storage-backup.md)를 따릅니다.
정적 Secret·CA 파일의 암호화 백업과 새 경로 복원은 [키 파일 백업 문서](../operations/backup/private-material-backup.md)를 따릅니다.
복원 DB의 모든 결과/checkpoint 참조 대조는 [DB/S3 복원 검증](../operations/recovery/recovery-references.md)을 따릅니다.
복원 DB의 일반 API 기동은 차단하며, 조회는 [복구 점검 모드](../operations/recovery/recovery-inspection.md)를 사용합니다.
DB에 없는 실행까지 찾는 조회 전용 [Kubernetes 복구 점검](../operations/recovery/recovery-kubernetes.md)을 제공합니다.
관측한 실행의 [생성 차단과 종료 확인](../operations/recovery/recovery-producer-stop.md)은 전용 namespace에서 수행합니다.
[원본 DB 연결 차단](../operations/recovery/recovery-database-fence.md)은 명시한 원본 DB의 새 연결을 막고 기존 연결 종료를 확인합니다.
[원본 MQTT 차단](../operations/recovery/recovery-mqtt-fence.md)은 기존 API의 관리 자격을 회수하고 Device/Task 접속을 차단합니다.
[원본 S3 root 차단](../operations/recovery/recovery-storage-fence.md)은 파일 버전을 보존하면서 원래 자격과 기존 URL의 새 요청을 차단합니다.
[진행 중 S3 요청 확인](../operations/recovery/recovery-storage-drain.md)은 차단 전에 인증된 요청까지 끝났는지 단일 MinIO에서 별도로 검증합니다.
[참조 Remote 복구 차단](../operations/recovery/recovery-remote-fence.md)은 새 작업과 늦은 입력을 막고 기존 계산 스레드 종료를 확인합니다.
[복원 Remote 이력 점검](../operations/recovery/recovery-remote-inventory.md)은 전체 제공자 기록과 복원 DB의 누락·충돌을 대조합니다.
[복원 Remote 실행 정리](../operations/recovery/recovery-remote-retirement.md)는 실제 종료를 재확인하고 DB 관측·runtime·기존 명령을 원자적으로 정리합니다. 복원 DB는 격리를 유지합니다.
[Remote 성공 파일 회수](../operations/recovery/recovery-remote-outputs.md)는 미확정 결과의 실제 bytes를 개인 묶음에 보존하고 원본 연결 없이 검증합니다.
[복구 파일 저장소 등록](../operations/recovery/recovery-remote-storage.md)은 기존 artifact 버킷에 조건부 업로드하고 고정 version·bytes를 검증합니다.
[복원 Remote 결과 확정](../operations/recovery/recovery-remote-results.md)은 실제 고정 파일을 다시 검사하고 Result·Task·Run을 원자적으로 반영합니다. 후속 작업은 대기 상태로만 준비하며 기동 격리를 유지합니다.
[복원 Remote 실패·취소 정리](../operations/recovery/recovery-remote-failures.md)는 사용자 취소·기존 결과를 보존하고 원래 기한과 횟수 안에서만 재시도를 예약합니다. 새 실행은 시작하지 않습니다.
[복원 Kubernetes 실행 정리](../operations/recovery/recovery-kubernetes-retirement.md)는 보존한 실제 종료 증거와 DB 신원을 대조해 실행·명령·VD 연결 이력을 정리합니다. 미관측 실행과 작업 결과 조정·전체 재가동은 별도입니다.
[VD 내부 작업 복구 검증](../evidence/m9-recovery-vd-tasks.md)은 실제 supervisor 종료 후 열린 할당을 닫고 기존 종료 사유·성공 결과·미배정 작업을 보존합니다.
[복원 Kubernetes/VD 작업 상태 조정](../operations/recovery/recovery-kubernetes-workflows.md)은 기록된 취소와 원래 기한의 재시도 만료·후속 작업·Run을 정리하고, 결과 미확정 작업은 미해결로 남깁니다.
같은 명령의 `--offloads`는 [증명 범위 안의 전환 취소·기한](../evidence/m9-recovery-batch-offloads.md)을 조정합니다. 미기록 target과 새 epoch는 보존하며 새 실행을 시작하지 않습니다.
[복원 STREAM 그룹 정리](../operations/recovery/recovery-stream-workflows.md)는 실제 producer·broker 종료를 다시 확인해 같은 Device fanout의 취소와 원래 그룹 재시도 기한을 함께 조정합니다. 새 실행과 서비스 재개는 종합 복구 단계에서 처리합니다.
[누락 STREAM 완료 이력 복원](../operations/recovery/recovery-stream-completions.md)은 독립 완료 문서·선행 checkpoint receipt를 대조해 원래 허가를 격리 DB에 반영합니다. 원래 실행 이력이 없는 경우는 거절하며 새 실행 권한을 만들지 않습니다.
MinIO 파일·외부 인증 키·실행 중 작업을 포함한 [M9 전체 복구](../requirements/m9-requirements.md)는 별도 검증이 필요합니다.
모든 테스트는 실행 환경과 함께 기록하며 `docs/evidence/runs/`의 원시 로그는 Git에서 제외합니다.
GitHub Actions는 Linux/JDK 21/Node 22/Compose PostgreSQL 17 환경에서 M0–M4와 추가된 재시도 회귀를 검증합니다.
저장소·Runner 컨테이너와 실제3노드 kind 종단 시험이 이미지 발행 게이트에 포함됩니다.
실제 Kubernetes 노드 관측은 별도 클러스터 검증이며 CI fixture 시험과 구분합니다.
