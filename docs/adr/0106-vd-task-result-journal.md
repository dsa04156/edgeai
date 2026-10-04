# ADR 0106: 확정된 VD 자식 Result를 원래 배정과 함께 별도 보존한다

상태: 채택, 실제 PG/MVC/S3 대상9개 포함 runtime79개·PG232개·저장소11개와
단위122개 개별 수트, 실제 Kubernetes7개 통과. 2026-10-05.
전체 명령의 단위 시간 초과와 최초 Kubernetes 실패·분리 재검증은 근거에 보존한다.
신규 CI/배포 검증은 후속이다.

VD의 최초 시작 허가만으로 계산 성공을 증명할 수 없다. DB에 확정된 Result와
원래 자식 배정을 `authority/vd-task-result/<runtime UUID>.json`에 보존한다.
공유 supervisor의 종료나 슬롯 반납과 결과 발행의 완료는 서로 다른 사건이다.

V36은 V35의 독립 발행 큐를 재사용한다. Result의 committed 전환과 발행 요청을
같은 PostgreSQL transaction에 저장하며 기존 확정 VD 결과도 backfill한다.
기존 Kubernetes 발행 행·완료 상태와 물리 CREATE/DELETE 명령을 보존한다.
Remote 결과는 기존 별도 경로를 유지한다. 새 테이블이나 S3 참조 열은 추가하지 않는다.
V36 적용 뒤 원래 migration 파일은 수정하지 않는다.

API는 Result transaction이 끝난 후 DB를 다시 읽어 PostgreSQL이 보존한 원래
ID·시각·manifest digest·고정 출력 version/bytes/SHA/media type을 기록한다.
VD·allocation·supervisor runtime·generation·session·Pod/node 신원,
slot·배정 sequence/시각과 고정 configuration digest도 포함한다.
토큰·claim nonce·parameters 원문·서명 URL은 기록하지 않는다.

버전 관리가 활성화된 S3에 조건부 최초 쓰기를 하고, 고정 version을 다시 읽어
길이·media type·encoding·엄격 JSON 전체 필드를 대조한다. 재요청/동시 발행은
원래 version을 재사용한다. 다른 배정이나 결과, 중복/추가 필드·trailing JSON·
잘못된 media type은 덮어쓰지 않는다. 최대 크기는 기존 결과 기록과 같은1MiB다.

이 단계의 사실은 DB에 이미 확정된 Result다. supervisor의 현재 Ready/lease나
열린 배정을 다시 요구하지 않는다. 불변 배정·세션·신원과 Result의 일치를 검사하여
Pod 종료와 배정 종료 뒤에도 영속 worker가 발행할 수 있게 한다.
새 실행 권한이나 과거의 시작 허가는 생성하지 않는다. `startKey`는 연관 키이며
과거 결과에 해당 시작 기록이 실제 존재한다는 주장은 아니다.

API의 성공 응답은 결과 기록 확인 이후다. 저장소 장애503/충돌400이 발생해도
이미 확정된 Task는 SUCCEEDED이며 Result를 취소하거나 새 Attempt를 만들지 않는다.
유효한 producer는 같은 commit을 재요청할 수 있다. producer 인증이 사라진 뒤에는
namespace별 영속 발행 worker가 만료된 lease를 회수해 재처리한다. 회수된 lease의 이전 owner는 완료할 수 없다.
DB transaction 안의 발행은 금지하여 rollback되는 성공 기록을 만들지 않는다.

조건부 쓰기/versioning은 S3 관리자에 대한 Object Lock 보장이 아니다.
독립 백업과 원본 writer/API 차단, 실제 producer 종료, 복원 DB의 시작/결과 대조,
STREAM 그룹 및 전체 활성화 절차는 후속이다. 결과 기록만으로 작업을 재개하지 않는다.
[검증 근거](../evidence/m9-vd-task-result-journal.md)를 따른다.
