# M9 복원 Remote runtime·명령 정리 검증

2026-10-04, ADR0075. 로컬 최종 실제 PG16/TLS13개 PASS/0:
`20261004T095215Z-59a4cda4`, `.tools/recovery-remote-retire-final.json`.
기존 읽기 점검10개 회귀도 `20261004T095133Z-eaa68841` PASS/0다.
Python 전체14개·실제 Java Remote gateway13개는 `20261004T095512Z-28739504` PASS/0다.
첫11개 시험은 `20261004T095032Z-f7e05e88`이며 최종 실행은 marker/잠금 시험을 추가한다.

실제 Java 공개 API로 Remote 할당4개와 CREATE를 생성한다. 별도 TLS 제공자에서 두 계산을
실행하고 하나는 선행 취소, 하나는 예약 후 전체 차단으로 취소한다. 실제 archive를 두 새 DB에
복원하고 원본 API/DB를 종료·삭제한 뒤 시험한다. 이미 관측한 성공·확정 Result1/고정 artifact
참조1·DELETE lease는 불변 trigger를 유지한 명시적 SQL fixture다. 실제 S3 bytes 복구 시험은 아니다.

| 확인 | 실제 근거 |
|---|---|
| 쓰기 전 거절 | 살아 있는 미차단 제공자, 잘못된 설치/복구 ID·TLS pin·binding·복원 OID·미지원 schema |
| DB 경쟁 | 조회 뒤 command 시도 횟수 변경, 복원 marker 변경을 실제 DB에 주입하고 전체 쓰기 거절 |
| 잠금 제한 | 별도 psql이 실제 테이블 잠금을 보유하면5초 뒤 실패, 부분 변경 없음 |
| rollback | command UPDATE trigger가 예외를 내면 앞선 관측/runtime UPDATE도 원복 |
| 한 번의 반영 | 관측3개·runtime3개·명령4개 변경; 이미 종료한 성공 이력은 유지 |
| 보존 | 나머지40개 테이블, 확정 Result·artifact 참조, runtime 식별/nonce, 명령 ID·시도 횟수·예약 시각, 다른 복원 DB |
| 멱등성 | 동일 복구 재실행의 세 변경 수0, 전체43테이블 timestamp/해시 동일; 기존 receipt 덮어쓰기 거절 |
| 응답 유실 | 실제 COMMIT 뒤 호출 경계에 OSError 주입; intent 보존, 새 실제 조회로0변경 확인 |
| 실제 제공자 관측 | 제공자 종료 중에는 거절, SIGKILL 재시작 뒤 같은 차단/전체 이력 보존·0변경 |
| 격리 유지 | 일반 패키징 API 기동 거절, inspection API 조회 성공·쓰기403·DB 보존 |

최종 보고서:43테이블·복원DB2·제공자1·할당4, 소유 DB와 모든 API/TLS 프로세스 정리 확인.
API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`로 기존과 같다.
V1–V34와 API/Runner/SDK 계약은 변경하지 않는다. Compose17 CI에13개 gate를 추가했으며
새 commit의 원격 실행·배포는 후속 확인 대상이다.

Task/Attempt/Run 결과 확정, 미반영 성공 파일 회수, 외부 제공자 계약, 다른 producer와 journal,
복원 활성화·RPO/RTO·전체 M9는 미완료다. 이 근거로 전체 장애 복구 성공을 선언하지 않는다.
