# M9 복원 DB/Kubernetes 실행 관측 검증

2026-10-04, ADR0063. 전체 M9 복구 완료가 아니다.

`recovery-kubernetes` 실행 `20261004T023306Z-de6fe9bb`가 PASS다. 실제 PostgreSQL16
archive/새 DB 복원과 별도 소유 Kubernetes namespace에서 다음6개 사례를 통과했다.

1. 실제 패키징 API로 생성한 Profile·V1–V33 schema를 백업/복원하고 정상 marker/OID와 읽기 전용 DB snapshot을 확인.
2. 백업 후 만든 Job1개·Runner Pod1개·VD Pod1개가 DB 행 없이도 목록에 포함됨.
3. 예약 이름의 다른 소유 Job은 충돌로 표시하고, 무관한 Job은 보존함. Pod 환경변수/annotation의 canary는 출력·보고서에 없음.
4. 기존 결과 디렉터리를 거절하고 원래 보고서 bytes를 보존함.
5. 다른 복원 marker 및 소유하지 않은 namespace를 거절하고 성공 보고서를 생성하지 않음.
6. 복원 DB41개 테이블의 전체 내용과 실제 Kubernetes5개 객체의 UID·spec·label을 조회 전후 보존함.

소유 namespace·원본/복원 DB·API 프로세스 정리를 모두 확인했다. 관측 대상4개 중 DB에 없는
실행3개/소유 충돌1개다. `quiesced=false`, `activated=false`다. Pod는 실제 API 서버 객체이나
의도적으로 스케줄하지 않은 메타데이터 fixture이며 실제 모델·Runner 동작을 입증하지 않는다.

분류/페이지 회귀7개도 PASS다. 동일 이름의 새 UID, UID 미기록, 소유/epoch/VD generation
불일치, 고아 Pod, 페이지 snapshot 변경·중복 UID/continuation, namespace 교체와
목록에서 보이지 않는 객체를 종료로 오판하지 않는 것을 확인했다. DB에 있는 실행과의 일치/불일치
분류는 이 회귀의 명시적 메타데이터 fixture다. 실제 DB 시험은 실행 행이 없는 복원 시점을 사용했다.

새 CI에는 분류 회귀를 추가한다. 실제 namespace 시험은 로컬 클러스터 검증이며 CI 실클러스터
게이트 연결은 후속이다. 원래 제어기 차단·producer 회수·Remote/broker/장치·키/journal 복구와
활성화, 전체 장애 수용은 남는다.
