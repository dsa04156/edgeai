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
클러스터 배포는 아직이며 기존 적용 V1–V26과 의존성 lock3개는533d850 대비 byte 불변을 확인했다.
현재 실행 JAR SHA-256은 `4bb50dea9abe364e2c72393da716303e6efdd95a81f772cf00b8889824ed7c1d`다.

전체 저장소 회귀162212Z-0d11cd56은37개 중36개 통과, 기존 checkpoint TRUNCATE 방어 시험1개가
실패했다. 새 FK 참조표 task_offload_member가 시험의 TRUNCATE 표 목록에 없어 PostgreSQL이
불변 trigger 이전에SQLSTATE0A000을 반환했다. 의도한23514 불변 검사를 유지하도록 참조표를
목록에 추가했고162531Z-94e99964 전체37개에서 실패/오류/skip0을 확인했다.

새 실제 Kubernetes 전환/취소/API 재시작, CI 이미지·배포 수용, 자동 정책·VD/단계별 배치와
외부 장비/모델 수용은 남는다. 기존 MQTT 간헐 timeout/lease 실패의 원인도 아직 미확정이다.
전체 M5 잔여/M7–M10과 전체 목표는 미완료다.
