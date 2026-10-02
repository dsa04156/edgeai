# M2 Device/Node 검증

2026-10-02. 로컬 구현과 검증을 마쳤으며 M2 CI/배포 검증은 별도로 확인한다.
구현 계약은 ADR 0003, 원본 REST 계약은 contracts/openapi/platform-api.yaml이다.

| testRunId | 범위 | 결과 |
|---|---|---|
| 20261002T043407Z-7f84538c | PostgreSQL 16.15 통합 13개: lifecycle, 8개 동시 등록/보고/세션, 이력·FK·UNIQUE, 기존 Profile 회귀 | PASS/0 |
| 20261002T044508Z-e34da173 | 생성 TypeScript 및 패키징 OpenAPI 일치, 선택 MVC·문서 보안 | PASS/0 |
| 20261002T044859Z-9442212e | 실제 API/DB의 Profile·Device·Swagger PC/모바일 6개, DB 중지 시 503 및 같은 프로세스 복구 | PASS/0 |
| 20261002T045530Z-71bdab52 | 단위/MVC/Node adapter 19개, HTTP proxy Upgrade 회귀 포함 | PASS/0 |
| 20261002T045642Z-ab33aa48 | UI lint/typecheck/build, offline·proxy 허용 경로 PC/모바일 12개 | PASS/0 |
| 20261002T045749Z-d87635c0 | 실제 Kubernetes 노드 10개 UID·자원·labels 대조, 합성 장치 attachment replay/release, 배포 HTTP probe, PC/모바일 6개 및 Swagger 17개 역할 | PASS/0 |

로컬 Kubernetes 검증은 명시한 기존 context를 읽는 loopback kubectl proxy를 사용했고,
프로젝트 API/UI/proxy 프로세스는 시험 후 정리했다. Node API의 metadata는 실제 클러스터 데이터다.
장치 시험은 SYNTHETIC이며 물리 센서·추론·스트림 처리·KubeEdge 하드웨어 수용을 주장하지 않는다.
단위 HTTP server의 fixture와 실제 Node API 대조 시험을 구분한다. 브라우저 스크린샷을 직접 확인했다.

배포 manifests는 실제 서버 dry-run을 통과했다. 전용 node-reader RBAC를 적용하고 impersonation
조회로 get/list nodes=yes, patch nodes/list secrets/create pods=no를 확인했다.
ArgoCD AppProject는 namespace ServiceAccount만 추가 허용하고 clusterResourceWhitelist는 유지한다.

발견·수정한 실패:

- 최초 통합 시험 20261002T043233Z-abede528: 생성 응답과 DB replay의 timestamp 정밀도 차이.
  Clock을 밀리초 단위로 통일했다. Profile TRUNCATE 시험은 새 FK와 함께 불변 trigger를 실제 실행하도록
  rollback-only 트랜잭션에서 CASCADE를 명시했다. 적용된 V1/V2 migration은 변경하지 않았다.
- 브라우저 데이터 출처 선택은 label 내부 option 텍스트 때문에 exact label 선택자가 실패했다.
  실제 combobox 접근성 이름으로 선택했고, Next route announcer와 오류 alert는 비어 있지 않은 alert로 구분했다.
- 20261002T045025Z-63a76845: UI 실행과 빌드를 동시에 시작해 standalone 파일이 교체되는 시험 오케스트레이션 오류.
  이후 빌드→실행 순서로 검증했고 프로세스 종료 감지도 각 PID별로 보강했다.
- 실제 kubectl proxy에서 JDK HTTP/2 cleartext Upgrade는 HTTP500, 같은 URL의 HTTP/1.1은 200으로 재현했다.
  loopback HTTP만 HTTP/1.1로 고정하고 단위 회귀와 실노드 대조를 통과했다. HTTPS CA 검증은 유지한다.

남은 범위: M2 새 이미지/클러스터 내 ServiceAccount 관측 배포 확인, M3–M10 전체 구현.
기존 Argo Ingress health Progressing, 공유 control-plane 상태, 운영 identity/TLS/HA/백업은 기존 위험을 유지한다.
원시 result.json/로그/브라우저 증거는 Git에서 제외한 runs 및 dashboard/test-results에 보존한다.
