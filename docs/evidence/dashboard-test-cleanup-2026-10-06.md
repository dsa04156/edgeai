# 테스트 데이터 정리와 모니터링 표시 수정

2026-10-06 사용자가 연결된 테스트 데이터 전체 삭제를 명시적으로 승인했다.

## 원인과 코드 변경

- 서비스 프로필은7,899개 키/8,005개 버전이었다. broker/runtime/public-stream/offload 등
  UUID 접미사 데이터와 통합시험 생성 코드가 일치했다. 전체 Profile은14,498개 버전이었다.
- 정상 GET은 ManagementAuditFilter에서 저장하지 않는다. Next 개발 요청 요약 출력은
  `logging.incomingRequests=false`로 비활성화했다. 오류/경고 출력 및 변경 요청 감사는 유지한다.
- CPU를 `rate(...[5m])`에서 `irate(...[2m])`로 바꿨다. 최근 두 샘플의 변화량이며,
  2분은 탐색 범위다. 현재 약30초 수집 간격을 UI/가이드/계약에 명시했다.
- ‘실행 상태’를 ‘노드 준비 상태’로 바꾸고 최신 Kubernetes 정보가 없으면
  ‘관측 정보 없음’으로 표시한다. 실행 작업 유무를 뜻하지 않는다.

## 삭제 및 보존

- 실행 중인 로컬 API와 동일한 edgeai DB에서 처리했다. 전체 DB 사전 백업은
  `/mnt/data3tb/edgeai/backups/before-test-cleanup-20261006`에 소유자 전용 권한으로 저장했다.
  12,064,111 bytes, SHA256 `7bf662f75225d8c6f4d4396a4f1cca5c8583faf7d234810a2db12cf7862b89aa`.
  pg_restore 목록 검증은 통과했다. 실제 복원 시험은 하지 않았다.
- Profile14,498, Device4,429, VD1,840, Workflow5,581, Run5,578 및 연결된
  테스트 테이블 데이터를 한 트랜잭션으로 비웠다. 모의 노드2,396개도 삭제했다.
- 실제 노드10개 행과 Flyway41개 행은 내용까지 동일함을 확인했다.
  일시 해제한 사용자 trigger75개를 원래 활성 모드로 복원한 것을 확인했다.
- DB 외 Kubernetes workload, Prometheus, 객체 저장소의 파일은 변경하지 않았다.
  앱 시작/재시작/배포는 하지 않았다.

## 검증

- IP13080 프록시에서 SERVICE/DEVICE/VD 프로필, 장치, VD, 감사 목록 모두0개/다음 페이지 없음.
- nodes10개, node-metrics AVAILABLE10개. workflow API는 현재 프록시에서404이므로
  Workflow/Run 삭제 결과는 DB의 정확한 count0으로 검증했다.
- 새 CPU PromQL을 기존 Prometheus에 읽기 전용 실행:10개 결과, 경고 없음.
  실행 중 백엔드에는 재시작 전까지 기존 계산이 남는다.
- NodeMetricsTest11 + ManagementAuditFilterTest7:18개 성공, 실패/오류/skip0.
  프론트 tsc --noEmit, 변경 파일 ESLint, 계약 타입 재생성, diff --check 성공.
- 전체 E2E/새 production build는 이번 작업에서 실행하지 않았다.
- 향후 같은 개발 DB에 통합시험을 실행하면 fixture 데이터가 다시 쌓일 수 있다.
