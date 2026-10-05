# 완료 그룹 체크포인트 이력 보존 검증

[ADR0111](../adr/0111-stream-completion-checkpoint-history.md)의 구현을 검증 중이다.
현재 검증되지 않은 항목을 완료로 판정하지 않는다.

- `20261005T043141Z-613f4059` PASS: 실제 PG/HTTP/버전 관리 S3의17개 보존 시험,
  실패/오류/생략0. 원래 완료13개와 새 선행 이력·UTC 정규화·부분 저장 실패/재요청·
  변조 거절·이미 완료한 그룹의 worker 재처리를 포함한다. 복사된 참조36개 대조와
  소유 DB/MinIO 정리를 확인했다. 이 Java 수트의 checkpoint metadata/Pod/broker는 fixture다.
- `20261005T043212Z-981c466a` PASS: 실제 원본 V34를 소유 시험 DB에 복사해 V37을
  적용한 뒤 완료 발행 표시를 true로 설정하고 V38로 올렸다. 기존45테이블의 업무 값과
  완료 문서97개/원래 완료 표시는 유지되고97개 모두 이력 보완 대상이 됐다.
  원본 불변·소유 API 종료·시험 DB 부재를 확인했다.
- `20261005T043302Z-4f2452a7` PASS: 실제 NODE/VD claim·checkpoint5·완료/Result2에서
  원본 제거·독립 백업·복원 CLI까지 혼합27개. 선행 receipt5개를 실제 TLS로 읽어 원래
  ID/시각/이전 참조·payload/summary를 대조했고, 선행 receipt 누락과 terminal 시각 변조를
  거절했다. 기존 peer 모순14종·양방향 복원·경쟁/원복/응답 유실·다른40테이블 보존도
  확인했다. 소유 namespace/복원DB6/API/저장소를 정리했다. Device END/route/배정은 fixture다.

실제 검증한 JAR SHA256은 `1f45fef7769ba705a5c533c2117f00f117fbf40b78ccd51dee258211f4270048`이다.
V38 SHA256은 `cba3bbdc3eccdd64cbc7fe0d26aed2afc393aa052057465de127b10cd414d341`이며
적용 뒤 수정하지 않았다. 이전 마이그레이션도 수정하지 않았다.

## 기존 PostgreSQL 인스턴스의 회귀 실패

`20261005T043743Z-ba561628`은 단위122개 PASS, STREAM source15개 중4개 실패로 전체
FAIL이다. 서로 다른 VD의 첫 checkpoint, finalizer의 최초 WAITING, 실제 source의 완료
대기와 shared VD→NODE 전환이 실패했다. 마지막 사례는 Hikari 연결을 얻지 못했다.
소유 시험 DB/MinIO는 정리됐다.

DB 관측584개 중 시작60초 이후 active query2초 초과 표본은250개였다. WALSync/WALWrite를
기다리는 COMMIT은 최대8.85346초였고, 그 transaction 뒤로 workflow_run/device/VD
행 잠금 대기가 연결됐다. 이것은 관측한 대기 경로이며 디스크나 애플리케이션의 근본 원인을
해결했다는 판정이 아니다. fsync·synchronous_commit·연결 풀·시험 제한 시간을 변경하지 않았다.

독립 PostgreSQL 비교의 최초 `20261005T044436Z-533c0e4b`는 긴 Unix socket 경로 때문에
서버가 기동하지 못했다. 서버 종료/소유 data 삭제를 확인하고 같은 시험 helper를 loopback
TCP 전용으로 수정했다. 이 실패는 production이나 업무 시험 결과가 아니다.

독립 PostgreSQL16.15의 `20261005T044517Z-e9d4d228`도 STREAM15개 중2개 실패로
FAIL이다. 앞선4개는 통과했지만 distinct VD 재시도와 shared VD fanout의 완료 단계에서
Runner가 실패했다. 관측432개 중 active query2초 초과98개, WAL COMMIT 최대5.168761초다.
fsync·synchronous_commit·full_page_writes는 모두 on이다. 시험 DB/MinIO와 독립 PG 서버/
data를 정리했다. 서버 분리로 문제가 해결됐다고 판정하지 않으며 전체 회귀는 미해결이다.

`20261005T044956Z-6fce3102`의 V38 PostgreSQL 백업·격리 복원13개는 PASS다.
45테이블·원본 불변·복원 DB 쓰기 차단·변조 거절·소유 DB/API 정리를 확인했다.

테스트 전용 VD 자식 진단을 추가했다. 실제 supervisor/Runner 동작을 호출하면서 자식
로그를 삭제되는 작업 폴더 밖에 보존하고, 오류 타입·고정 코드·Runner 내부 코드 위치만
실패 보고서로 전달한다. 메시지·요청·자격 증명·작업 출력은 기록하지 않는다.
대상2개 `20261005T050129Z-9cd173bf`, 전체 STREAM15개
`20261005T050233Z-eebd63fc`는 독립 PG에서 PASS/소유 정리다. 전체 실행에서 완료 참조22개를
대조했다. 이는 Runner 완료 응답 지연 수정 전 결과이며 앞선 간헐 실패의 원인 해결을 증명하지 않는다.

초기 실행 `20261005T042823Z-09a7b5c9`는 Gradle wrapper의 실행 디렉터리 오류로
시험을 시작하지 못했다. 소유 DB/MinIO는 정리됐다. 디렉터리를 바로잡은
`20261005T042838Z-1c2b550c`는 확장된 저장 인터페이스를 내부 완료 객체 전용 helper가
계속 구현하고 있어서 컴파일에 실패했다. 인터페이스는 두 객체를 제공하는 S3ArtifactStore가
구현하고 내부 helper는 자기 객체 저장만 담당하도록 수정했다. 이 시도도 소유 자원을 정리했다.

`20261005T042947Z-e50bc17f`는17개 중1개 실패다. 완료 객체 발행을 막은 기존 시험은
모든 버킷 객체가0개라고 기대했으나 새 선행 receipt2개가 먼저 저장됐다. HTTP503·최종
다운로드/출력 업로드/commit 차단은 통과했다. 기대값을 정확한 receipt2개·완료 객체0개·
Result0개로 수정했으며 production 권한 검사를 완화하지 않았다. 소유 DB/MinIO는 정리됐다.

누락 완료 상태의 DB 반영·새 실행 복원·종합 활성화·전체 M0–M10 수용은 남는다.
