# M7 STREAM 그룹 자동 전환 검증

2026-10-04. ADR0052/V28. 공개 Run의 선택적 측정 정책과 그룹 체크포인트 전환을 연결한다.
이 문서는 새 코드의 로컬 검증이며 전체 M7 또는 새 이미지/Kubernetes 수용 완료 기록이 아니다.

## 구현과 확인한 범위

- 공개 정책 생성/재전송/충돌, 혼합 DAG의 복구 불가 SERVICE 거절.
- 현재 체크포인트 누락·오래된 측정·자원 제한 누락·완료 허가 시 전환 보류.
- 모든 구성원의 시작/전환 대기와 자동 예산, 동시 판단의 단일 Operation, 방문 노드 제외.
- 취소 경합에서 새 Attempt 생성 차단, BATCH 하위 대기와 독립 분기 보존.
- V28의 정책/선택 구성원/다른 구성원 배치 위조 거절과 전체 삽입 rollback.
- 실제 Runner의 스트리밍 중 HTTPS 측정, 최종 처리까지 sequence 유지, 권한 거절과503 구분.
- 설정·그룹 구성원 배치·체크포인트·자동 판단 근거의 PC/모바일 표시.

## 실행 근거

원시 로그/JSON은 무시된 `docs/evidence/runs/<runId>/`에 보관한다.

| 검증 | runId | 결과와 범위 |
|---|---|---|
| 취소 경합 재현 | 20261003T173637Z-9cf73f09 | PASS, 원래 실패한 DB 시험1개 |
| 자동 정책8 + 기존 STREAM30/Offload16 | 20261003T174336Z-06167497 | PASS, 실제 PostgreSQL/MVC54개; Pod/broker/S3 receipt fixture |
| 실제 Runner/모델/HTTPS/TLS broker | 20261003T174501Z-dcd4236f | PASS,18개; 신규 측정3개 포함 |
| Runner 회귀 | 20261003T174642Z-3abac63e | PASS,111개; 컨테이너 자원 제한 검증은 CI 별도 |
| 화면 lint/type/build/브라우저 | 20261003T174642Z-f256c674 | PASS,40개; 자동 그룹 표시 PC/모바일 확인 |
| 자동 전환 실제 서버 단독 | 20261003T174900Z-4d471ffb | PASS, 실제 Spring/PG/MinIO/TLS MQTT/독립 Runner와 Device SDK; Pod 배정·신원·종료 관측 및 지연 파일 입력 fixture |
| 전체 실제 서버 연결 | 20261003T175029Z-b76dbcbe | PASS,8개; 자동·수동 그룹 전환·retry·최종 처리·취소 포함 |
| 전체 TLS MQTT/HTTPS 회귀 | 20261003T175030Z-e82f5124 | PASS,90개 |
| 최종 PostgreSQL 전체 | 20261003T175617Z-1e47f9cb | PASS,206개, 실패/오류/skip0; 명시적 opt-in 없는 Run의 DB 위조 거절 추가 포함 |
| 실제 저장소 전체 회귀 | 20261003T175805Z-0a91a34f | PASS,38개; Remote/VD/Result/STREAM 포함 |
| 서버 단위 | 20261003T180027Z-36103220 | PASS,105개, 실패/오류/skip0 |
| 최종 OpenAPI/생성 타입/Swagger YAML/MVC | 20261003T180331Z-783d42a1 | PASS,5개 계약·MVC26개·포장 YAML 일치 |

실제 서버 시험은 동일 Device owner의 센서 커서를 보존하고 외부 상태9를 새 Attempt에
인계한다. 이후 root14/sink23/BATCH37을 각각 고정 S3 버전·bytes·SHA와 비교한다. 지연
1200μs 입력은 제어된 시험 파일이며 실제 서비스의 성능 측정 결과로 해석하지 않는다.
해당 파일의 읽기/sequence/HTTPS 인증/서버 판단과 상태 인계는 실제 경로다.

## 실패와 판정 한계

- `20261003T172921Z-ba49187d`:52개 중 취소 fixture의 잘못된 fence 사유1개 실패.
  허용된 CANCELLED로 수정한 뒤 원래 시험 및54개 회귀를 통과했다.
- `20261003T173927Z-5a249991`:54개 중 새 DB guard 시험의 잘못된 digest 표현1개 실패.
  실제 sha256 접두사 계약으로 수정하고 정상 삽입/rollback 및5개 위조 거절을 확인했다.
- `20261003T174156Z-423b9339`:18개 중 새 시험2개의 결과 기대 형식이 잘못됐다.
  실제 JSON 결과 계약으로 수정한 뒤18개를 통과했다.
- `20261003T174613Z-83a4a278`:실제 서버8개 중 자동 전환 시험의 최초 checkpoint 조회에서
  일시적 CheckpointUnavailable1개. 단독 재실행은 통과했으나 외부 지연 원인은 미확정이다.
  검증 도구는 기존 제한 시간 안에서 재조회하도록 보완했다. 거절/fence 오류는 계속 실패한다.

새 V28은 격리 DB에서 검증했으며 공유 개발 DB 적용·새 API/Runner 이미지·CI/배포,
실제 Kubernetes 자원 부하에 따른 자동 전환은 후속 검증이다. 기존 MQTT 간헐 재연결/lease
문제의 원인이 이번 변경으로 해결됐다고 주장하지 않는다. M5 잔여/M7–M10 전체 목표는 유지한다.
