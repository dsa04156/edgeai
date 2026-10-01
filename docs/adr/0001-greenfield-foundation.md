# ADR-0001: 신규 M0 개발 환경

날짜: 2026-10-01. 상태: 초기 구현에 적용.

## 결정

- 설계 문서에서 요구한 greenfield modular monolith를 따른다.
- REST prefix는 API 정의서의 `/api/v1`을 선택한다. 전체 설계도의 `/v1` 비교 항목은 이 결정으로 정합화한다.
- JDK 21, Spring Boot 4.1.1, Gradle 9.7.1, Node 22.23.2, pnpm 10.34.6, Next.js 16.3.8을 고정한다.
- Spring Initializr 생성 wrapper와 공식 체크섬을 사용한다. pnpm/Gradle lockfile을 커밋한다.
- M0는 health·metadata만 구현하고 상세 도메인 계약을 확정하지 않는다.
- 개발용 HTTP Basic과 loopback 제한을 사용한다. production identity는 후속 계약이다.
- Compose의 PostgreSQL/MQTT를 기본으로, MinIO source build를 선택적 storage profile로 둔다.
  M0 필수 health 경로에는 object storage가 필요하지 않으며 M4 전에 별도 검증한다.
- 공개 GitHub 저장소 `dsa04156/edgeai` 생성·초기 push는 사용자가 명시적으로 요청했다.

## 근거와 제한

- [Spring Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)과
  [Next.js 설치 요구사항](https://nextjs.org/docs/app/getting-started/installation)을 확인하고 실제 빌드로 검증한다.
- 현재 호스트의 Docker 소켓 접근은 거부됐다. 권한을 자동 확대하지 않는다.
- 기존 Kubernetes context는 테스트 소유 context가 아니므로 사용하지 않는다.
- MinIO Docker Hub/Quay 이미지 조회가 401이었다. 확인된 공식 release commit
  `9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a`로 선택적 빌드를 제공한다. 실행 검증 전에는 READY로 보고하지 않는다.
- 별도 전체 실행 계약은 미제공이다. M1+ 상태 전이와 수용 기준은 추가 정합화가 필요하다.
