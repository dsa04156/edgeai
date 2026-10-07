# 운영 안내

운영자는 접속 경로, 관리 API, 외부 수집기와 실행 서비스를 각각 확인해야 합니다.
화면 접근 성공만으로 데이터 수집이나 작업 실행까지 정상이라고 판단하지 않습니다.

## 환경 준비 순서

1. [로컬 빠른 시작](../guides/local-development.md)에서 기본 API·DB·화면 연결을 확인합니다.
2. [설정 참고](../reference/configuration.md)를 기준으로 프로세스별 주소와 기능 플래그를 정합니다.
3. [인프라](../guides/infrastructure-inventory.md)와 [Prometheus](../guides/node-metrics.md)를 연결합니다.
4. 실제 서비스 경로에 맞게 [DDS 배포](../guides/workflow-editor-integration.md) 또는 [Spring 실행](../guides/dag-execution.md)을 준비합니다.
5. 클러스터 운영은 [CI/CD](cicd.md), 데이터 보호는 [백업과 복구](backup-and-recovery.md)를 확인합니다.

## 일상 점검

| 질문 | 확인할 대상 |
|---|---|
| API를 사용할 수 있는가? | Dashboard 연결 상태와 readiness |
| 데이터가 최근 값인가? | 원본 수집 시각, exporter 상태, 센서 Reading |
| 자원 분류가 맞는가? | Kubernetes 라벨과 EdgeX 등록 원본 |
| 배포가 반영됐는가? | 원하는 Git revision, Argo sync/health, 실행 이미지 digest |
| 작업이 완료됐는가? | 해당 Run·Task·Result 또는 DDS 서비스 자체의 결과 |
| 변경 요청이 처리됐는가? | 감사 접수·HTTP 결과와 대상 리소스 상태 |

## 변경과 장애

설정 변경은 프로세스에 새 환경을 전달해야 반영됩니다. 앱 이미지 롤백은 DB migration을 되돌리지 않습니다.
삭제·배포·센서 명령은 실제 외부 자원에 영향을 줄 수 있으므로 해당 절차의 대상과 결과 확인 단계를 따릅니다.

연결 장애는 [문제 해결](troubleshooting.md), 변경 추적은 [감사 기록](management-audit.md)을 사용합니다.
복구 도구는 전용 자원과 확인된 입력에 적용하는 구성 요소입니다. 개별 명령 성공을 전체 서비스 재가동으로 해석하지 않습니다.
