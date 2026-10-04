# M9 원본 DB 연결 차단 검증

2026-10-04, ADR0069. `20261004T065711Z-4d27de4e`에서 실제 PostgreSQL16과 패키징 API의
10개 사례가 PASS다. 격리 DB를 공개 API로 초기화하고 Hikari 연결2개·미완료 INSERT 연결1개,
다른 DB의 독립 연결을 함께 실행했다. 기존 운영 DB에는 차단을 적용하지 않았다.

1. 실제 API와 원본의 미완료 transaction, 다른 DB의 살아 있는 연결을 준비했다.
2. 잘못된 OID는 차단 전에 거절하고 기존 연결과 DB 주석을 보존했다.
3. EdgeAI migration 이력이 없는 DB를 거절했다.
4. 이미 있는 DB 주석을 덮어쓰지 않았다.
5. 원본의 backend3개 종료·남은 연결0·실제 새 연결 거절·fence 보존을 확인했다.
6. 다른 DB의 기존 PID·연결과 저장값99를 보존했다.
7. 원래 API의 쓰기503과 같은 원본을 사용하는 새 API의 기동 거절을 확인했다.
8. 같은 복구 UUID 재개는 추가 종료0으로 통과하고 다른 UUID의 인계를 거절했다.
9. 시험 소유 API 종료 뒤 시험 DB만 열어 미완료 INSERT rollback·기존 commit/Profile 보존을
   확인했다. 외부에서 연결 허용을 다시 켠 상태의 재개는 거절했다.
10. 사전 검사와 ALTER 사이에서 시험 DB를 실제 rename하고 같은 이름의 새 OID를 만들었다.
    신원 재검사가 실패하여 새 DB의 ALTER까지 rollback했고 두 DB의 연결 허용·신원을 보존했다.

최종 보고서의 `ownedApisStopped`, `ownedClientsStopped`, `ownedDatabasesRemoved`,
`replacedDatabasePreserved`는 모두 true다. 공개 요약에는 합성 사례 이름·개수·판정만 보존한다.
SQL/API 원문 로그와 환경 값은 `.tools`의 소유자 전용 경로에 남기며 Git/CI artifact에 넣지 않는다.

초기 시험의 API 기동 실패는 새 시험 환경 변수의 이름을 바로잡은 뒤 해소됐다.
Spring Boot의 [환경 변수 변환 규칙](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables)에
맞춰 `MAXIMUMPOOLSIZE`, `MINIMUMIDLE`, `CONNECTIONTIMEOUT`, `INITIALIZATIONFAILTIMEOUT`을
사용한다. 제품 Java 복구 설정은 수정하지 않았다. 최종 JAR SHA256은 기존 검증본과 같은
`3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`이다.

CI scaffold에 Compose PostgreSQL17의 동일10개 gate와 공개 요약 수집을 추가했다.
이 새 CI의 성공은 아직 확인하지 않았다. 실제 prepared transaction이 있는 서버·다른 locale의
오류·운영 권한별 검증은 이번10개에 포함하지 않았다. 준비된 transaction이 있으면 구현상
BLOCKED를 반환하며, 이를 자동 정리하는 계약은 없다.

원본 DB 연결 차단만 검증했다. 원래 프로세스의 외부 권한·진행 중 외부 요청과 Remote·MQTT·
장치·S3 writer 회수, 종합 복구/활성화 및 M9 전체 수용은 남는다.
