# ADR 0003 — Device, Node, Session, Observation

2026-10-02. Notion API/ERD의 M2 10개 작업과 관계를 상세화한다. 문서 수정 시각은 docs/sources.md와 같다.

- Device는 물리 장치의 영속 식별자이며 Kubernetes ExecutionNode와 별개다. key는 전체 고유,
  DEVICE ProfileVersion을 FK로 참조한다. sourceMode LIVE/REPLAY/SYNTHETIC을 명시해 시험 데이터를 구분한다.
- 등록 입력(key/displayName/profileVersionId/sourceMode)이 같은 재요청은 기존 ID로 200,
  새 등록은 201, 같은 key의 다른 생성 입력은 409다. 생성 입력 digest는 수정 후에도 유지한다.
- PATCH는 displayName만 변경한다. 발행 규격/장치 식별과 sourceMode를 조용히 바꾸지 않는다.
  revision으로 낙관적 동시성 충돌을 409로 알린다. DELETE는 RELEASED로 해제하고 이력을 남긴다.
- Node는 Kubernetes UID로 식별하고 공식 Node API의 실제 관측을 저장한다. 목록/상세 API만 공개한다.
  수동 생성 API로 가짜 클러스터 노드를 만들지 않는다. 관측 비활성/실패는 Ready로 취급하지 않는다.
  성공한 전체 snapshot에서 누락된 노드는 REMOVED, 마지막 관측 60초 초과는 STALE로 표시한다.
- Device당 활성 attachment 하나, 과거 연결 이력은 유지한다. PUT은 관측상 Ready이고 신선한 Node와
  port를 연결한다. 동일 활성 연결 재요청은 이력을 추가하지 않는다. 교체/해제는 기존 이력을 닫는다.
- bootId(UUID)별 Session을 생성한다. 현재 bootId 재전송은 같은 Session/epoch로 200이다.
  새 bootId는 epoch를 올리고 기존 세션을 닫는다. 이미 닫힌 bootId의 재사용은 409다.
- Observation은 sessionId와 증가하는 sequence(0..2^53-1), observedAt, ONLINE/OFFLINE 상태,
  작은 attributes 객체를 받는다. 동일 session/sequence/내용 재전송은 200, 변경/역순/이전 세션은 409다.
  시간은 서버 수신보다 최대 30초 미래·24시간 과거를 허용한다. 건강 신선도는 서버 receivedAt으로 판단한다.
- 장치 생성/세션 시작 직후 연결 상태는 UNKNOWN이다. 활성 세션의 마지막 보고가 60초를 넘으면 STALE,
  해제 시 RELEASED다. raw stream·대형 데이터는 Observation 저장 대상으로 삼지 않는다(요청 16 KiB).
- Device row lock으로 session/attachment/observation/해제를 직렬화한다. DB FK/CHECK와 활성 관계의
  partial UNIQUE로 이력 무결성을 보강한다. REST 읽기/쓰기는 기존 Basic/CSRF 규칙을 유지한다.
- 재현은 실제 PostgreSQL 통합·동시성, 계약/오류·UI, Kubernetes 읽기 adapter를 각각 검증한다.
  simulator 결과는 SYNTHETIC으로 표시하며 실제 물리 장치 수용시험은 M10 증거가 필요하다.
- 현재 API/관측 worker는 단일 replica다. 다중 writer의 snapshot 세대 제어/leader는 M9에서 검증한다.
- Kubernetes HTTPS는 마운트된 CA 검증과 매 요청 ServiceAccount 토큰 파일 재읽기를 사용한다.
  전용 node-reader는 get/list만 허용하고 ClusterRole/Binding은 소유 label을 확인하는 bootstrap에서 관리한다.
  ArgoCD의 clusterResourceWhitelist를 확대하지 않는다. loopback HTTP는 kubectl proxy 호환을 위해 HTTP/1.1을 사용한다.
