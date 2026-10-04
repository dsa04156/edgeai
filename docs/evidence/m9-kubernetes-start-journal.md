# Kubernetes 최초 시작 기록 검증

[ADR0101](../adr/0101-kubernetes-start-journal.md)은 실제 Pod의 claim 성공 응답 전에
원래 허가를 버전 관리 S3에 보존한다. 복원 DB가 이 기록을 소비하는 기능과는 구분한다.

초기 컴파일은 MinIO SDK의 boxed Long 길이 인수에 int를 전달해 실패했다
(`20261004T200600Z-cdb2ac1b`). long으로 명시한 뒤 동일 단위 시험
`20261004T201125Z-6ed8e72b`가 통과했다.

실제 MinIO 시작 기록6개·기존 artifact5개·기존 실제 STREAM15개를
`20261004T201347Z-2eed9e70`에서 검증했다. 모두 PASS이며 소유 DB/MinIO를 제거했다.

- 동시12개 같은 허가는 객체 버전1개다. 서로 다른 작업의 경쟁에서는 한쪽만 성공한다.
- 반복 요청은 최초 admittedAt/version을 보존하며 원래 시작 기한 뒤에도 기존 기록을 재사용한다.
- 신원/작업/기한/접수 시각/추가·누락 필드19종, 중복 JSON/trailing JSON/배열/
  잘못된 media type/초과 길이는 덮어쓰지 않고 거절한다.
- 원래 생성 시각 전·시작 기한/lease 경계의 최초 허가는 객체를 만들지 않는다.
- 버전 관리 중단은 기존 기록이 있어도 거절한다. nonce/parameters 원문을 저장하지 않는다.

실제 Spring HTTP 보안/컨트롤러·PostgreSQL·S3의 새5개와 기존 Runner API의
신규2개 포함9개를 `20261004T201754Z-590cb6e6`에서 검증했다. 모두 PASS다.
정상/재요청·실제 버킷 부재 후 복귀·기존 기록 변조 거절과 실제 S3 저장 직후
응답 유실/취소를 포함한다. Kubernetes 인증은 명시적 Pod proof fixture이고
응답 유실·취소 시점은 실제 저장 메서드 뒤의 시험용 경계다.
실제 소켓 응답 유실이나 실제 Kubernetes Pod를 이 시험으로 주장하지 않는다.

초기 API 결합 `20261004T201634Z-7862850d`의 취소 시험은409와 실제 기록 보존을
확인했지만 Task를 즉시 CANCELLED로 기대해 실패했다. 기존 계약은 실제 실행 종료까지
CANCELLING을 유지하므로 그 상태를 검증하도록 수정했다. 제품 취소 동작은 바꾸지 않았다.

최종 전체 회귀 `20261004T201849Z-7a15a836`는 단위122개·PostgreSQL232개·
실제 runtime/S3/broker53개·저장소11개 모두 PASS다. 실패/오류/생략은0이며
소유 DB/MinIO 제거를 확인했다. 원본/후속 STREAM peer·BATCH 자식의 실제 HTTP claim
기록을 고정 version으로 읽어 신원·최초 시각·원래 lease/전환 기한과 대조했다.

OpenAPI 생성·MVC/Swagger 계약 `20261004T202358Z-55730dde`와 패키징
`20261004T202504Z-9ed703bc`도 PASS다. 실제 Kubernetes/TLS/TokenReview/Runner의
AUTO·NODE 전환·취소3개 `20261004T202525Z-305738ce` PASS:

- 실제 Pod10개와 최초 기록10개를 DB·관측 Pod/node 신원·원래 lease/전환 기한과 대조했다.
- 각 시작 기록은 S3 version1개이고, 결과6개는 고정 version/bytes/SHA/계산 값이 일치한다.
- AUTO 실행 도중 실제 API Pod 교체, 노드 전환 뒤 양쪽 STREAM 상태 복원, 취소 후 결과 없음과
  소유 자원/Job barrier 제거를 확인했다. 기존 배포나 저장 데이터를 교체하지 않았다.
- 실행 JAR SHA256은 `85e001ab79e72f5cda2d65fb5e29e9ff0cb63dc533d17fbe122b981d201bc1f8`이다.
  Runner는 기존 CI 검증 index `8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`,
  source `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`이며 새 API 이미지 배포 증거는 아니다.

신규 저장소/HTTP 시험은 기존
CI의 storageIntegrationTest/runtimeArtifactIntegrationTest에 포함한다.
STREAM DAG 검증은 실제 원본/후속 peer와 BATCH 자식의 고정 version 기록을 대조한다.
실제 Kubernetes 스크립트도 관측한 Pod/node와 DB의 모든 해당 claim을 TLS S3 기록과
대조하며 `verifiedStartJournals`를 남긴다. 실제 외부 계약, VD 독립 시작 기록,
복원 DB 반영·전역 writer 차단·새 권한·종합 활성화와 전체 M9 수용은 남는다.
선행 f66c4cd CI37228787573은 이 변경을 포함하지 않는다. 신규 CI/배포는 후속이다.
