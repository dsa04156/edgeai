# M7 native API HTTPS 연결

2026-10-03. [ADR0048](../adr/0048-native-api-tls-connector.md).

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 Spring/PG·HTTP와 추가 HTTPS·인증/CSRF·신뢰/hostname | 20261003T143123Z-0db4d9d0 | 2개 PASS |
| 전체 단위/MVC·TLS 설정/실제 잘못된 PEM 시작 | 20261003T143412Z-94fa2f98 | 105개 PASS |
| 전체 PostgreSQL 회귀·실제 HTTP/TLS 포함 | 20261003T143717Z-340daee2 | 190개, failure/error/skip0 |
| 추가 HTTPS→실제 Kubernetes 스트림/복구/취소 | 20261003T143715Z-4ed559f2 | 5개 시나리오·Pod17개·S3파일12개 PASS/0 |

단위 첫 실행 `20261003T143306Z-e9bad6b5`는105개 중 설정 검사2개가 실패했다.
간소화한 ApplicationContextRunner fixture에 Boot의 InetAddress 변환기가 없어서 목표한
포트/파일 검증 전에 실패한 것이 원인이었다. 실제 Spring 통합시험은 이미 통과했다.
fixture에 실제 Boot ApplicationConversionService를 제공한 뒤 같은 전체105개를 통과했다.
제품의 검증 규칙을 바꾸거나 예외 기대값을 완화하지 않았다.

Kubernetes API는 HTTP18080과 추가 HTTPS18443을 함께 열고 SDK·Runner는 HTTPS18443을 사용했다.
보고서의 `apiTlsMode=additional-native-connector`를 확인했다. API Pod 교체9.207초 동안 기존
Runner2개의 UID가 유지됐다. 그룹/최종 처리 복구, 정상 AUTO/NODE·취소와 실제 고정 S3
12개 결과14/23/BATCH37을 검증했고 모든 시험 소유 자원을 제거했다.

사용한 현재 JAR SHA256은 `bac16191ce7ececdafdbc8c59789320863beb9c275508ebbb20c23a462916b05`다.
Runner는 ca56e2f의 `sha256:addba1032bc7dc6783f2c58ab81965b70a06648c279b0cfbc914c26f9d45b4a2`,
MinIO는 `sha256:fca36951fcbfec3c2c1b7609a2d6d8a1ca5e1d897fb23e9f5dee8b8e9c64e7e7`이며
둘 다 선행 CI에서 검증된 digest다. 원시 보고서는 해당 실행 폴더의 `kubernetes.json`이다.
V1–V26 migration과 모든 Gradle lockfile이 HEAD와 바이트 단위로 동일함을 확인했다.

이 구성 요소는 운영 TLS 브로커·S3/Runner 신뢰·영속 키/인증서와 다중 장치 데모 연결을 대신하지 않는다.
새 이미지/CI/배포와 M5 잔여/M7–M10 전체 수용은 아직 미완료다.
