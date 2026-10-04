# M9 Remote 미반영 성공 파일 회수 검증

2026-10-04, ADR0076. 최종 실제 PG16/API/archive/TLS 결합17개
`20261004T100728Z-2813d1a2` PASS/0, `.tools/recovery-remote-outputs-final.json`.
최종 Python 전체17개·실제 Java gateway13개는 `20261004T100728Z-f6d01f72` PASS/0.
선행 결합17개 `20261004T100517Z-a3a2bce2`, 제공자 복구 TLS12개
`20261004T100519Z-75e054d3`도 PASS/0다.

ADR0075의13개 트랜잭션/보존/격리 시험에 파일 회수4개를 추가했다. 실제 Java API 할당4개,
TLS 성공 계산2개·선행 취소1·예약 후 차단 취소1, 실제 복원 DB2를 사용한다. 확정 Result1과
artifact 참조는 기존 SQL fixture이며 이를 실제 S3 파일 복구 근거로 사용하지 않는다.

| 범위 | 확인 |
|---|---|
| 전체 회수 | 실제 미확정 성공 파일1개가 고정 크기/SHA와 일치하며600 객체로 보존. 확정 Result1은 유지 |
| 일치·거절 | 잘못된 설치/binding/TLS와 실제 원본 bytes 변조는 완료 manifest 없이 거절 |
| 다운로드 경쟁 | 실제 TLS 다운로드 직후 DB 명령 시도 횟수를 바꾸면 전후 DB 해시 불일치로 완료 거절 |
| 독립 검증 | 제공자 SIGKILL·DB 환경변수 제거 뒤 검증 성공. 손상/링크/관측 누락/불필요 파일은 거절 |
| 제공자 경계 | 별도 운영 자격, 미차단 상태·다른 복구 ID·중복 query·잘못된 method/port·비성공·없는 할당 거절 |
| 원본 파일 경계 | 변조/삭제·파일/부모 디렉터리 symlink·FIFO를 실제 파일시스템에 만들면409, 정상 복원 뒤200 |
| 응답 검증 | 실제 TLS 응답을 받은 뒤 truncate/hash/Content-Length/중복header/encoding/302를 주입하면 거절 |
| 보존 | DB43테이블·제공자 전체 행·일반 bearer 차단, SIGKILL 뒤 같은 파일 bytes 유지 |

정상 요청은 실제 네트워크/서버·DB/파일을 사용한다. 응답 변조는 다운로드 클라이언트 호출
경계에 명시적으로 주입한 것이며 제공자가 실제 악성 HTTP를 보냈다고 주장하지 않는다.
원본 API/DB는 archive 이후 삭제하며 모든 소유 DB/API/TLS 프로세스는 종료·정리한다.
새 Remote OpenAPI0.4.0을 타입 생성기로 검증했다. CI의 기존 retirement gate에17개를 연결한다.

S3 등록·고정 object version·Result/Task/Run 확정·재시도·외부 업체 계약·종합 활성화는
이 시험 범위 밖이며 후속 구현/검증 대상이다. 새 commit의 원격 CI/배포도 별도로 확인한다.
