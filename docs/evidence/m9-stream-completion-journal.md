# STREAM 완료 허가 독립 보존 검증

[ADR0110](../adr/0110-stream-completion-journal.md)의 V37 outbox·S3 기록·공개 API 확인·
재발행 worker를 검증한다. 누락된 DB 허가를 이 기록에서 복원하는 소비 단계와 종합
활성화는 아직 구현 완료 범위가 아니다.

## 확인된 근거

- `20261005T034533Z-98648de1` PASS: 원본 V34 DB를 별도 시험 DB에 복사해 V36까지
  적용한 뒤 V37을 적용했다. 기존44테이블의 업무 행을 SHA256으로 대조했고 완료 그룹
  97개를 backfill했다. 원본 DB 불변·시험 API 종료·시험 DB 부재를 확인했다.
  V37 SHA256은 `cefac32b269066e9aa048b77d9a4d10f0c65c7ac226f673471694adcdb67963c`이며
  처음 적용한 뒤 수정하지 않았다.
- `20261005T035042Z-03a9ad5e` PASS: 실제 PostgreSQL·HTTP·버전 관리 MinIO13개,
  실패/오류/생략0. 소유 DB와 MinIO를 정리했다. Pod/broker/체크포인트 metadata는
  이 Java 수트에서 명시적 fixture다.
- `20261005T035140Z-9266845a` PASS: V37 패키지의 기존 독립 백업 참조9개. 원본 DB/S3
  제거 뒤 실제 TLS 백업의 고정 결과·과거 체크포인트를 검증했다. 이 수트의 outbox는 비어
  있으며 실제 완료 기록의 추가 참조는 아래 별도 검사로 확인한다.
- `20261005T040316Z-c8778e10` PASS: 실제 NODE/VD 혼합 실행의 `sealed` 백업을 별도
  DB에 복원하여 완료 기록1개·원래 두 actor·공통 grant instant·터미널 checkpoint2개와
  과거 checkpoint5개를 대조했다. 저장소 백업 manifest12개 중 완료 객체9050바이트의
  SHA256과 DB 문서 bytes가 같다. 복사된 checkpoint version 모순과 목록 누락을
  거절하고 의도적 시험 DB 변조를 원상복원했다. 이5개 검사에서 객체 bytes 대조는
  검증된 백업 manifest 기준이며 거절 경로의 저장소 설치 신원은 fixture다.
- `20261005T040329Z-099f2b9a` PASS: OpenAPI5개 생성과 MVC/Swagger 계약 검증.
- `20261005T041016Z-73b99690` PASS: 실제 TLS MinIO/MQTT/Python Runner를 사용하는
  STREAM source15개(실패/오류/생략0). 복사된 완료 기록의 checkpoint 참조22개와 원본을
  대조했다. 소유 DB/MinIO 정리도 확인했다. 앞선 간헐 실패의 원인 해결을 뜻하지 않는다.
- `20261005T041327Z-5f6ab6f7` PASS: 저장소11개(실패/오류/생략0), 소유 DB/MinIO 정리와
  최종 패키징. 최종 JAR SHA256은
  `67a2003ad58c3d45f3967f055cdc8a7adbfebbc38189bacbe4dd6c576a8df36f`다.
- `20261005T041417Z-c286beeb`는 **전체 FAIL**이며 기능24개는 통과했다. 실제 TLS 백업의
  독립 완료 객체1개 bytes, 복사된 terminal checkpoint 참조2개, 과거 checkpoint5개를
  검증했다. 잘못된 목록/고정 version을 거절하고 소유 시험 변조를 원복했다. 양쪽 복원 순서,
  peer 모순14종·실제 경쟁/원복/COMMIT 응답 유실·40개 다른 테이블 보존도 확인했다.
  원본 DB/S3·복원DB6개·API/저장소는 제거/종료됐다. namespace 정리 중 Kubernetes
  조회의 nonzero 응답으로 실패했다. 원래 보고서의 FAIL을 유지한다.
- `20261005T042012Z-b6965e30` PASS: 위 실패 후 복원DB6개와 소유 namespace의 실제
  부재를 확인했다. namespace 삭제는 이미 완료돼 추가 삭제나 finalizer 변경은 없었다.
  최초 Kubernetes 조회 실패의 근본 원인은 미확정이다.
- `20261005T042201Z-6cea6f03` PASS: 최종 V37 JAR의 실제 PostgreSQL16 백업/복원13개.
  45테이블의 내용/마이그레이션/백업 경계를 보존하고 원본 불변, 격리 DB 일반 기동 거절,
  검사 모드 쓰기 거절, 기존 대상/변조 archive/잘못된 권한 거절과 소유 API/DB 정리를 확인했다.

13개 보존 시험은 다음을 포함한다.

- Task2/Device1의 원자적 완료·원래 checkpoint 고정 참조·비밀값 배제·반복 API 요청과
  worker 재발행의 최초 object version 보존.
- 실제 저장 뒤 응답 유실을 주입한503과 같은 요청의 성공. 원래 grant/문서를 변경하지 않는다.
- 닫힌 TCP 포트로의 실제 저장 실패, SQL fixture의 producer 종료, 새 worker가 만료된
  lease를 인계받아 재발행. 이전 owner는 큐를 완료하지 못한다.
- DB rollback 시 허가와 outbox가 함께 사라지고 transaction 안의 발행은 거절된다.
- 다른 내용·중복 필드·trailing JSON·추가 필드·잘못된 media type·버전 관리 중단 거절.
- 동시 발행4개, 별도6MiB 저장소 전용 문서의 동시 발행3개가 각각 한 version을 유지한다.
- S3 저장 중 실제 취소 API 호출 후 FINALIZE 응답 거절. 저장 불능은 최종 checkpoint
  다운로드·출력 업로드·Result commit endpoint를 모두 차단한다.
- 허가 문서/시각 삭제·변경·TRUNCATE 거절, namespace/lease owner 경계,
  기존 완료 행 backfill과 rollback, 같은 Device fanout과 별도 그룹의 분리.

## 실패와 재검증 범위

초기 시험 절차의 V36 원본 가정(`034421Z-c8ab97fb`)과 격리 복원 DB의 일반 기동 시도
(`034445Z-1c3c051b`)는 실패했다. 실제 원본 V34를 확인하고 독립 마이그레이션 시험 DB로
수정했다. 격리 정책을 완화하지 않았다. 시험 코드의 import/인자형 오류
(`034735Z-a2386f19`), 기동하지 않은 기본 S3 포트(`034800Z-209da440`), 비동기 취소의
기대 상태 오류(`034848Z-a48c6eb8`)를 수정한 뒤 위13개를 통과했다.

전체 회귀 `20261005T035140Z-6e0624aa`는 **FAIL**이다. 단위122개/PG232개는 통과했지만
runtime93개 중16개가 로컬 Mosquitto 경로 누락에 따른 클래스 초기화 실패다. 저장소 수트는
실행하지 못했다. 환경을 보완한 `20261005T035517Z-aed251ac`도 runtime92개 중4개 실패다.
VD 전환 시 `RUNNER_FAILED INVALID_RESPONSE`, Device 배정 연결 실패와 Hikari5개 모두
사용 중인 상태에서5초 연결 대기를 관측했다. 동시 시점 PG `WALSync/WALWrite` 대기도
관측했으나 이 자료만으로 근본 원인을 확정하지 않는다. 최초 실패와 분리 재검증을 구분한다.
분리15개 `20261005T040434Z-d28c1079`에서는 앞선4개가 통과했으나 다른 DAG 사례의
Hikari 연결 대기1개가 실패했다(5개 active, 대기16개,5초 제한). 소유 DB/MinIO를
정리했다. 같은 기준에서 DB 대기 이벤트와 잠금 관계를 수집한 후속15개는 위와 같이
통과했으나, 최초 실패의 원인을 확정하거나 연결 풀/timeout을 변경하지 않았다.

V37의 실제 혼합 복구 `20261005T035240Z-060364e1`은16개 통과 뒤 source partial-commit
백업의 CLI에서 실패했다. `intent.json`은 있으나 transaction SQL은 없고,
예외형은 RuntimeError, PostgreSQL 진단 로그는0바이트다. 정확한 실패 경계는 남지 않았다.
후속 CLI는 예외 메시지/요청 내용 없이 파일·함수·행 번호만 개인 진단에 기록하도록 보완했다.
정리 중 DROP DATABASE30초 관측 제한으로 다른 DB와 namespace 정리가 생략됐다.
`20261005T040247Z-36992bb8`에서 OID/소유 label/UID·실제 producer 종료를 다시 확인하고
남은 DB5개와 namespace를 제거했으며 전체 복원 DB6개 부재를 확인했다. 이제 DB 하나의
정리 오류가 다른 DB·namespace 정리를 생략하지 않으며, 시간 초과 DROP을 재발행하지 않는다.

백업 metadata 감사 초기3회(`035801Z-df4040cb`, `040053Z-3939714e`, `040156Z-143d8ba9`)는
시험의 UTC 문자열 가정·checkpoint 시각 표기 비교·읽기 전용 snapshot 전제 오류로
실패했다. 원래 ISO8601 offset의 instant를 비교하고, 의도적 변조 뒤 실제 읽기 전용
검증을 수행하도록 수정했다. 각 감사가 만든 DB는 제거했다. production의 시각이나
읽기 전용 보호를 시험에 맞추어 변경하지 않았다.

새 원격 CI/배포와 전체 M0–M10 수용은 아직 확인하지 않았다. 누락 grant/checkpoint
선행 이력/실행의 독립 복원, 전역 writer/API 차단·종합 활성화·실제 모델/외부 계약이 남는다.
