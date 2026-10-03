# ADR 0048: 같은 API 프로세스의 추가 HTTPS 포트

2026-10-03. M7 Device/Runner SDK는 인증된 HTTPS 제어 주소가 필요하다. 기존 API 프로세스에
선택적인 native Tomcat HTTPS connector를 추가해 같은 servlet context·인증/CSRF 규칙을 사용한다.
별도 프록시나 두 번째 API writer를 만들지 않는다. 기본값은 비활성이며 배포 연결은 후속 작업이다.

## 설정과 신뢰

`EDGEAI_API_TLS_ENABLED=true`일 때 기본18443 포트를 추가한다. 포트는1–65535 범위에서 기존
API 포트와 달라야 한다. bind 주소는 기존 `EDGEAI_BIND_ADDRESS`를 따른다. 공개 범위는 배포
소유의 Service/Ingress/네트워크 설정이 결정하며 이 코드가 클러스터 리소스를 만들지 않는다.

`EDGEAI_API_TLS_CERTIFICATE_FILE`과 `EDGEAI_API_TLS_PRIVATE_KEY_FILE`은 읽기 가능한 절대 경로의
1바이트–1MiB PEM 파일이다. 인증서는 leaf와 필요한 intermediate chain, 키는 암호화되지 않은
개인 키다. Kubernetes projected Secret의 정상 파일 참조를 허용한다. 키는 API만 마운트하고
클라이언트에는 공개 CA만 배포한다. TLS1.2/1.3을 사용하며 mTLS 신원을 HTTP 인증 대신 사용하지 않는다.

설정 누락·잘못된 포트는 bean 구성에서 실패하고 잘못된 PEM은 실제 Tomcat 시작을 실패시킨다.
활성화 오류를 잡아서 HTTP만 정상 배포된 것으로 처리하지 않는다. 키/인증서 갱신은 Pod 재기동을
통해 반영하며 자동 인증서 발급/갱신은 이번 변경의 범위가 아니다.

Runner/VD의 HTTPS 제어 주소와 `EDGEAI_RUNTIME_CA_CONFIG_MAP`을 함께 설정한다.
고정된 VD 설정·기존 실행의 신뢰 번들은 ADR0042를 따르며 활성 실행의 설정을 임의로 바꾸지 않는다.
브로커·S3의 TLS 및 장치 세션/서명 키는 별도 운영 연결이 필요하다. 이 포트 추가만으로 공개
STREAM을 활성화하거나 외부 Ingress의 TLS·전체 M9 보안 수용을 완료했다고 판단하지 않는다.

## 구현 근거와 검증

Spring Boot4.1.1의 [추가 connector API](https://docs.spring.io/spring-boot/4.1/api/java/org/springframework/boot/tomcat/TomcatWebServerFactory.html)와
Tomcat11의 [HTTPS connector/PEM 설정](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)을 사용한다.
프로젝트의 잠긴 Boot4.1.1/Tomcat11.0.24에서 컴파일하고 실제 서버로 확인한다.

실제 Spring/PG·HTTP/HTTPS가 동시에 동작하는 시험은 readiness200, 익명401, 인증 읽기200,
CSRF 없는 쓰기403과 내부 경로 접근 거절을 확인한다. 신뢰하지 않는 인증서 및 hostname 불일치는
실제 handshake에서 실패해야 한다. 기본 비활성·누락 파일/잘못된 포트와 실제 잘못된 PEM 시작도 검사한다.
Kubernetes 스트림5개 시나리오의 TLS API도 이 추가 connector를 사용하도록 연결한다.

[실행 결과](../evidence/m7-native-api-tls.md). 의존성과 Flyway V1–V26은 변경하지 않는다.
