# 구현 계약 — M0–M1 작업 기준

상태: 네 설계 문서에서 확인한 원칙과 초기화 및 Profile 구현 범위. 별도 전체 계약 원문은 아직 확인되지 않았다.

## 현재 수용 범위

1. JDK 21 / Gradle wrapper / Node 22 / pnpm lockfile로 반복 빌드 가능하다.
2. Flyway가 실제 PostgreSQL에서 schema를 초기화한다.
3. `GET /actuator/health/readiness`는 DB 연결을 포함하며 장애 시 503을 반환한다.
4. Next.js `GET /api/health`는 Spring readiness 결과를 전달하며 연결 실패를 UP으로 표시하지 않는다.
5. `GET /api/v1/platform`은 인증을 요구하고 milestone=M1, capabilities=[profiles]를 반환한다.
6. 개발 서비스는 loopback에만 publish한다. 비밀번호는 무작위 local `.env`로 관리한다.
7. 미구현 시험은 exit 2/BLOCKED를 반환한다. scaffold 성공을 전체 플랫폼 완료로 표시하지 않는다.

## M1 Profile 수용 범위

- DEVICE/SERVICE/VD 등록·목록·정확한 버전 조회를 제공한다.
- 발행 즉시 불변, identity=(kind,key,version), UUID는 미래 FK 대상이다.
- 새 등록 201, 정규화 내용이 같은 재등록 200, 같은 identity의 다른 내용 409.
- JSON 정규화 SHA-256 digest와 원래 발행 ID/시각을 재등록에서도 보존한다.
- PostgreSQL UNIQUE와 UPDATE/DELETE/TRUNCATE 차단 trigger로 불변성을 보강한다.
- 동시 등록·충돌, 실제 인증·CSRF, paging/filter/404/400/413/503을 검증한다.
- Dashboard 입력/오류/조회와 큰 숫자의 정밀도 보존을 실제 DB 연결에서 검증한다.
- spec은 비어 있지 않은 JSON 객체다. kind별 실행 스키마나 참조 무결성은 아직
  보증하지 않는다. 등록만으로 장치 연결·서비스 실행 가능 상태로 판정하지 않는다.
- 구체적인 형식·한도·정규화·인증·페이지 일관성은 ADR 0002와 OpenAPI를 따른다.

## 후속 구현에서 유지할 불변 조건

- 발행 ProfileVersion·WorkflowVersion 불변.
- Device/Node/VD/Runtime 구분; VD runtime 교체 후 vdId 유지.
- retry/offload는 동일 Task의 새 Attempt; 늦은 producer 결과는 epoch/claim으로 차단.
- kube-scheduler가 최종 bind; 일반 `nodeName` 우회 금지.
- 실제 runtime 상태 watch/reconciliation; Job 성공만으로 Result commit 금지.
- 외부 호출은 adapter 경계, timeout·retry budget·terminal error 구분.
- 비동기 작업 식별자/상태 및 runId/taskId/attemptId/runtimeId 관측 필드.
- idempotency와 DB→외부 전송 일관성은 관련 write 경로 설계 때 함께 확정.

## 미확정

상세 lifecycle·오류·DDL·생성/실행/result commit API, production identity/RBAC,
2세부 실제 API, 실장비 inventory, GPU/NPU 공유 방식, 성능 수용 수치.
