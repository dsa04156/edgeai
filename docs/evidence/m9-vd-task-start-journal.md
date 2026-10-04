# VD 자식 최초 실행 허가 기록 검증

[ADR0105](../adr/0105-vd-task-start-journal.md)의 현재 검증 범위다.
복원 DB가 이 기록을 소비하거나 새 권한으로 전체 플랫폼을 활성화하는 시험은 별도다.

첫 대상 시험 `20261004T223308Z-da0e87ed`는 실제 PostgreSQL·MVC 보안 경로·MinIO의
`VDTaskStartJournalIntegrationTest`8개가 실패/오류/생략0으로 통과했다.
소유 DB·MinIO 제거를 확인했다. Pod/Node attestation은 명시적인 fixture다.

- 같은 VD Pod의 두 자식은 서로 다른 allocation·slot과 각각 한 version을 유지한다.
- heartbeat 갱신·drain으로 lease가 짧아진 뒤 재요청에도 최초 기록이 그대로다.
- drain 중 첫 허가는 당시 기한을 보존하며 이후 요청이 이를 바꾸지 않는다.
- 실제 S3 저장 직후503을 주입하고 재시도해 최초 version/시각·단일 Attempt를 확인한다.
- 실제 버킷 부재에서는 실행 응답이 없고 저장소 복귀 후 같은 실행으로 복구된다.
- 저장 직후 취소와 시험 시계로 lease를 만료시키면409이고 결과를 만들지 않는다.
- 동시4개 claim은 실제 version1개다.
- 배정/세션/설정/작업/lease 변조, 중복·trailing JSON, 잘못된 media type은 덮어쓰지 않는다.

전체 회귀의 첫 실행 `20261004T223952Z-a3c228be`는 단위122개 통과 뒤 runtime70개 중
VD→Node 전환의 HANDOVER 대기1개가 실패했다. sink의 RUNNER_FAILED·Run 실패와
source driver 종료를 확인했지만 최초 Runner 상세 코드는 보존되지 않았다.
같은 경로만 재실행한 `20261004T224431Z-9feddbe8`는 PASS다. 실패 시 허용 목록의
Runner 상태/오류 코드만 남기도록 진단을 보완했다. 원인 수정 완료를 뜻하지 않는다.
두 번째 전체 회귀 `20261004T224520Z-da589529`는 runtime70개 중5개 실패다.
checkpoint retry 대기1개와 STREAM4개이며, 그중3개에서 pool5개 모두 사용 중인
상태의5초 연결 대기 초과를 관측했다.

관련 `StreamCheckpointIntegrationTest`14개와 `StreamSourceCompletionIntegrationTest`15개는
`20261004T225147Z-dd54da4c`에서 실패/오류/생략0으로 통과했다.
이 재검증과 이후 전체 실행을 관측한 안전한 DB 진단에서 WALSync 최대3.257초와
그 transaction 뒤의 행 잠금 대기를 확인했다. SQL 원문·자격 증명은 기록하지 않았다.
이는 지연 관측이며 앞선 모든 실패의 원인 확정이나 제품 수정 완료를 뜻하지 않는다.

마지막 전체 명령 `20261004T225433Z-3ccee0f9`의 실제 XML은 단위122개·PostgreSQL232개·
runtime70개 실패/오류/생략0이다. 실제 Python supervisor/child HTTP 시험도 포함하며
assignment·claim·commit 성공 응답을503으로 바꾼 뒤 재시도해 같은 Attempt·시작 version과
계산 결과를 확인했다. 이 시험의 Pod 인증은 명시적 fixture다.
저장소11개 중1개는 checkpoint의 SQLite export subprocess가15초 안에 끝나지 않아 실패했고,
전체 명령은 FAIL이다. 실패 단계와 제한 시간이 유지되도록 진단 메시지만 명확하게 했다.
저장소11개를 `20261004T230128Z-847a8732`에서 분리 재실행한 결과는 실패/오류/생략0/PASS다.
위 모든 실행은 소유 DB/MinIO를 정리했다. 한 번의 전체 명령435개 PASS로 요약하지 않는다.

실 Kubernetes 첫 실행 `20261004T224044Z-84b60e23`은 업무 case 시작 전 API의 DB 복구
안전성 확인에서 PSQLException으로 종료했다. 소유 시험 Pod/Service/Secret/ConfigMap
제거를 별도 조회했으며 원래 DB/배포는 변경하지 않았다. 재실행과 원인 수정은 구분한다.

동일 JAR로 재실행한 `20261004T224349Z-50a87186`는 실제 Kubernetes/TLS/TokenReview의
7개 경로 모두 PASS다. VD 공유 실행·자식 실패 후 그룹 retry·공유/별도 VD의 Node 전환·
취소·최종 저장 재시도·supervisor 교체를 포함한다.

- VD 자식 시작 기록27개를 실제 관측 Pod/node와 DB의 배정/세션/세대/슬롯·고정 설정·
  원래 기한에 대조했고 각각 한 version을 확인했다.
- 실제 VD Pod17개·Node Pod2개를 관측했다.
- Node 시작 기록2개·확정 Result 기록2개와 발행 대기0을 확인했다.
- 출력18개는 고정 S3 version/bytes/SHA와 합성 계산 값이 일치한다.
- 소유 자원과 Job barrier 제거를 확인했다. 실제 AI 모델 수용이나 기존 배포 교체는 아니다.
- JAR SHA256은 `35c922c7c6a628207774cbb9765cd11d5cb8c9976d607b0a772c2684577ea9a4`다.
  Runner는 기존 CI 검증 index `8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff`,
  source `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`를 사용했다.

OpenAPI 생성·MVC/Swagger 계약26개 `20261004T230206Z-e6de42fd`도 PASS다.
최종 근거/패키지 감사 `20261004T230408Z-c589fa6b`는 위 JAR의 실제217개 app 파일과
domain/adapters JAR2개, 실제 Kubernetes 보고서의 같은 SHA, 개별 수트 결과와 문서 링크를
대조했다. 전체 명령 PASS 및 간헐 실패 해결 여부는 명시적으로 false다.
새 대상 클래스는 기존 CI의 runtimeArtifactIntegrationTest에 포함하고 기존 전체 STREAM
Kubernetes gate가 VD 시작 기록까지 검증하도록 연결했다.

새 코드의 원격 CI/배포는 아직 확인하지 않았다. 복원 VD 권한 소비·독립 결과 기록·
전역 writer 차단·종합 활성화와 M0–M10 전체 수용은 남는다.
