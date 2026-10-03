# M7 STREAM 그룹 노드 전환 검증

2026-10-04 KST. [ADR0051](../adr/0051-stream-group-offload.md), V27.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 공개 STREAM·기존 Offload 실제 PostgreSQL | 20261003T161217Z-cbb7d359 | 45개, 실패/오류/skip0 |
| 실제 Spring·PG·TLS MQTT·SDK·독립 Runner·S3 | 20261003T161406Z-58be8caf | 7개, 실패/오류/skip0 |
| 전체 PostgreSQL·추가 NODE 유지/별도 retry 예산 | 20261003T161702Z-7a790cb0 | 197개, 실패/오류/skip0 |
| UI lint/typecheck/build·PC/모바일 | 20261003T161504Z-0abef5c9 | 40개 PASS/0 |
| 펼친 그룹 상세·새 Attempt의 실제 가시성/스크린샷 | 20261003T161740Z-1d6daa94 | PC/모바일2개 PASS/0 |
| 전체 단위 | 20261003T161844Z-11f6a7fc | 105개, 실패/오류/skip0 |
| OpenAPI 5개·생성 타입·포장 YAML·MVC | 20261003T162048Z-fa3d378c | PASS/0 |
| 전체 실제 저장소 회귀 | 20261003T162531Z-94e99964 | 37개, 실패/오류/skip0 |
| 최종 그룹 구성원 정렬/멱등성·STREAM DB 회귀 | 20261003T162714Z-1d232be1 | 30개, 실패/오류/skip0 |
| 실제 API/Swagger·PC/모바일·DB 중단/복구 | 20261003T162831Z-cd75b697 | 브라우저10개·503/동일 프로세스 회복 PASS/0 |
| 실제 Kubernetes 노드 전환·대기 중 API 교체·취소 | 20261003T164929Z-88aeca68 | 새2개 시나리오·Runner Pod7개·고정 S3파일3개 PASS/0 |
| 실제 Kubernetes 전체 스트림 회귀 | 20261003T165130Z-e5016260 | 전체7개 시나리오·Runner Pod24개·고정 S3파일15개 PASS/0 |
| d3797d6 완성 이미지·전체 CI 근거 감사 | 20261003T171024Z-5237e391 | CI37137184323 5jobs·17개 원시 결과 PASS/0 |
| d3797d6 실제 이미지 배포 | 20261003T171025Z-f7b61da9 | GitOps4263ecb·정확한3imageID·Ready·PVCBound·ArgoSynced PASS/0 |
| 완성 API/Runner 이미지의 실제 노드 전환·취소 | 20261003T171145Z-57cf39f1 | 새2개·Runner Pod7개·고정 S3파일3개 PASS/0 |
| 새 이미지 배포 후 기존 저장소 보존 | 20261003T171301Z-f84b93cb | 기존10개 고정파일 bytes/SHA·메타데이터·두PVC UID 보존 PASS/0 |

실제 DB는 전체 종료/CREATE·경로 회수 장벽, 동시 요청과 전진, peer 취소, drain/start
기한, 부분 claim 뒤 실패, 확정 전 누락 체크포인트 및 공동 완료 허가 뒤 거절을 검증한다.
선택 Task의 NODE 변경과 peer의 기존 NODE/AUTO 유지, OFFLOAD를 제외한 retry 예산,
DB 불변 계획·체크포인트 연결·source/target 이력도 확인했다. 독립 분기는 유지한다.

실제 서버 시험은 공개 MVC 전환 요청 후 두 독립 Runner 프로세스가 종료되고 새 Attempt/폴더에서
S3 checkpoint를 내려받아 서버 handover를 거친다. 인계 항목의 원본 ID·revision·state SHA를
대조하고 같은 DeviceRunSource 객체들이 자동으로 재연결한다. Python probe의9→14/23→BATCH37과
각 고정 S3 version의 실제 bytes/길이/SHA를 확인했다. Pod provisioning·TokenReview·노드 신원·
종료 관측은 이 시험에서 fixture이며 실제 Kubernetes 노드 이동의 증거는 아니다.

최초 시험161026Z-bf503771은 V27 PL/pgSQL 조건의 괄호 없는 CASE 구문 때문에 마이그레이션
단계에서 실패했다. PostgreSQL 오류 위치가 CASE 내부 THEN에 해당함을 확인하고 표현식 괄호를
추가했다. 격리 DB의 재실행45개와 전체197개에서 통과했으며 프로젝트 DB에는 이 실패 migration을
적용하지 않았다. 수정된 V27의 프로젝트 로컬 적용은 마지막 실제 API/Swagger 시험에서 확인했다.
당시 공유 API의 새 이미지 배포는 별도였으며 후속 결과는 아래에 기록했다.
기존 적용 V1–V26과 의존성 lock3개는533d850 대비 byte 불변을 확인했다.
현재 실행 JAR SHA-256은 `4bb50dea9abe364e2c72393da716303e6efdd95a81f772cf00b8889824ed7c1d`다.

전체 저장소 회귀162212Z-0d11cd56은37개 중36개 통과, 기존 checkpoint TRUNCATE 방어 시험1개가
실패했다. 새 FK 참조표 task_offload_member가 시험의 TRUNCATE 표 목록에 없어 PostgreSQL이
불변 trigger 이전에SQLSTATE0A000을 반환했다. 의도한23514 불변 검사를 유지하도록 참조표를
목록에 추가했고162531Z-94e99964 전체37개에서 실패/오류/skip0을 확인했다.

## 실제 Kubernetes 전환·취소

164929Z-88aeca68은 현재 JAR와 기존 CI 검증 Runner digest를 사용한다. 소유 namespace 안의
고유한 시험용 API/DB/TLS broker/MinIO와 실제 scheduler·Pod TokenReview를 사용했다.
기존 공유 API/DB/저장소나 노드 설정은 변경하지 않았다. `--cases offload offload-cancel`은
새 경로의 진단용 선택이며 기본 CI 실행은 기존5개와 새2개, 전체7개를 요구한다.

- 실제 두 Runner의 상태9 checkpoint 뒤, 이 시험이 만든 두 Job에만 UID/resourceVersion을
  대조한 삭제 대기 finalizer를 붙였다. 실제 종료 대기 상태에서 새 Attempt가 없음을 확인했다.
- 정상 전환은 대기 중 시험용 API Pod를 교체했다. 새 Pod의 동일 JAR와 준비 완료를12.151초에
  관측했고 Operation ID·고정 checkpoint·멱등 요청 재전송이 보존됐다. 이는 단일 시험의 관측값이다.
- 대기 해제 후 이전 Runtime2개가 TERMINATED, 이전 route generation3개가 CLOSED인 것을
  확인했다. 새 OFFLOAD Attempt/Pod2개 중 선택 작업은 다른 실제 Node UID, peer는 원래 Node다.
- 체크포인트2개의 원본 ID·새 Attempt·serial 증가·동일 state revision/내용을 DB에서 대조했다.
  같은 Device 객체와 센서 커서를 유지하며9→14/23→BATCH37, 고정 S3 version3개의 bytes/SHA를 검증했다.
- 두 시나리오 모두 이전 producer의 실제 자격으로 보낸 늦은 commit은401이었다. 자격은
  메모리/비공개 pipe로만 전달했고 보고서에는 상태 코드만 기록했다.
- 취소 시나리오는 peer 취소 후 CANCELLING→CANCELLED, target Attempt 없음·전체 Attempt2개·
  Result 없음·실제 자원 회수를 확인했다. 모든 소유 fixture와 추가 finalizer를 제거했다.

별도 `report.json`에 실제 Pod/Node/Attempt 신원·전환과 checkpoint 대조를 저장했다.
이 시험의 센서/모델은 합성이며 실제 외부 장비·모델 수용의 증거는 아니다.

165130Z-e5016260은 같은 JAR로 기본 전체7개를 실행했다. AUTO/NODE·계산 중 그룹 장애·
완료 허가 뒤 최종 처리 장애·일반 취소·전환/전환 대기 취소가 모두 통과했다.
실제 Runner Pod24개와 고정 S3 결과15개를 대조했고, AUTO 실행 중 API 교체20.998초는
기존 Runner 신원을 유지했다. 전환 대기 중 API 교체11.980초는 같은 Operation/고정 checkpoint와
멱등 요청을 유지했다. 기존 최종 처리 grant/checkpoint·peer Result 보존 검사도 통과했다.
전환2개에서 이전 producer4개의 늦은 commit은 모두401이었다. 모든 소유 자원과 Job 대기
finalizer를 제거했고 종료0을 확인했다. 공유 driver의 기존5개 기본값은 유지하며 Kubernetes
수용 스크립트만 전체7개를 명시하므로 기존 배포 데모의3개 선택과 호환된다.

## 완성 이미지·CI·실제 배포

소스d3797d6의 CI37137184323은5 jobs 모두success, 원시JSON17개 모두PASS/0이다.
PG197개 실패/오류/skip0, 실제 컨테이너 Runner111개·TLS MQTT87개를 원시 결과에서 확인했다.
kind164536Z-162a1bfb은 기존 실행22Run/S320개·VD/S35개·STREAM5개/17Pods/S312개와
신규 신원 복구·영속 TLS broker Pod 교체·실제 렌더링 MinIO TLS·배포 데모3개/8Pods/S36개를
통과했다. 생성한 `edgeai-ci-051974a2c853`만 삭제한 종료 로그도 확인했다.
PVC가 Bound가 될 때까지 기다리는 수정은 새로 생성한 kind의 영속 broker 게이트를 통과했다.
이 CI의 STREAM은 기존5개이며 이번 기본7개 변경의 CI 수용과 구분한다.

GitOps4263ecb의 실제 API/dashboard/MinIO imageID·Ready, PVC Bound와 Argo Synced를
171025Z-f7b61da9에서 대조했다. 공유 PostgreSQL의 V27 적용 성공도 실제 조회했다.
Argo aggregate health는 기존 공유 Ingress 상태 때문에Progressing이며 서비스 Ready와 구분한다.
기존 저장소10개 고정 version·메타데이터·bytes/SHA256과 두PVC UID는 배포 뒤에도 보존됐다.

171145Z-57cf39f1은 로컬 JAR 교체 없이 게시된 API digest와 같은 소스의 Runner digest를 사용했다.
새2개 전환/취소·실제 Pod7개/S33개·원본 checkpoint2개·다른 Node/peer 유지·늦은 producer4개401을
확인했다. DRAINING 중 API Pod 교체10.592초와 동일 Operation/요청 재전송도 통과했다.
포장된 JAR SHA는 위 로컬 JAR와 같고 모든 소유 자원·Job 대기 finalizer를 제거했다.

새7개 기본 게이트의 CI 실행, 자동 정책·VD/단계별 배치와
외부 장비/모델 수용은 남는다. 기존 MQTT 간헐 timeout/lease 실패의 원인도 아직 미확정이다.
전체 M5 잔여/M7–M10과 전체 목표는 미완료다.
