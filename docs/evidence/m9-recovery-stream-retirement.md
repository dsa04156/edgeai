# M9 원본 브로커·복원 STREAM 경로 종료 검증

2026-10-04, ADR0091. `20261004T152904Z-e8228c9f` 실제 결합47개 PASS/exit0.
원시 개인 보고서는 `.tools/recovery-stream-retirement-first.json`이다. 기존 ADR0090의
장치/DB·checkpoint·TLS S3/MQTT·원본 owner33개와 새 STREAM 종료14개를 한 실행에서 검증했다.

공개 STREAM Run에서 나온 DB를 PostgreSQL16의 새 복원 DB5개에 복원했다. 실제 TLS
Mosquitto의 원래 Device/Task 연결을 차단하고 기존 관리자 자격을 회수했다. 복원 DB의
별도 대상에서 SQL fixture로 runtime claim과 checkpoint receipt를 준비했으며 모든 DB
제약·불변 trigger는 활성화했다. Task 프로세스 종료나 재개를 이 시험의 범위로 주장하지 않는다.

새14개에서 확인한 내용:

- 실제 원래 계정을 재활성화하면 DB 쓰기 전에 거절했다. 다른 복구 UUID와 실제 DB marker
  교체도 거절했다.
- broker 관측 중, plan 이후, 최종 관측 이후 transaction 직전의 실제 DB 변경을 각각
  거절했다. 마지막 경우는 22개 테이블 잠금 뒤 SQL guard가 실제로 transaction을 중단했다.
- 두 generation의 fence/close 뒤 SQL 오류를 주입하자 두 갱신 모두 rollback됐다.
- 별도 psql 프로세스가 route 테이블 잠금을 잡으면 SQL lock timeout으로 끝났고 변경은 0이었다.
- 실제 CLI에서 기존 ACTIVE1개와 CANCELLED로 fence한1개를 모두 CLOSED로 바꿨다.
  새 fence1개/close2개이며 기존 취소 이유·시각과 나머지42개 테이블은 보존됐다.
- 동일 명령을 반복하면 fence/close 모두0이며 terminal 이력 bytes는 달라지지 않았다.
- 새 PREPARING generation에서 실제 COMMIT 뒤 응답 유실을 주입했다. DB는 CLOSED였고
  재관측/재실행은0변경으로 완료했다. 새 generation 생성의 heartbeat fixture와 실제
  복구 명령의 변경을 분리해 비교했다.
- 실제 COMMIT 직후 broker 계정 재활성화는 성공 보고를 거절했다. DB marker·intent를
  유지하고 broker 차단을 복구한 뒤0변경으로 다시 확인했다.
- 다른 broker digest의 열린 generation1개는 미해결로 보고하고 그대로 보존했다.

전체 시험에서 실제 경로4개가 종료됐다. 원래 checkpoint·Task/Run/Attempt·runtime·명령·
Device session·완료 이력과 각 시험 입력의42개 다른 테이블은 변경하지 않았다. 기존33개
검증의43개 테이블/격리/원본 owner 검사도 통과했다. 소유 DB/API·MinIO2개·broker·MQTT client·
잠금 프로세스 정리를 보고서에서 확인했다. activated와 전역/물리 종료 플래그는 false다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7` 및
V1–V34·Runner 코드는 불변이다. ADR0090의 Runner111/MQTT97/백업13 근거를 재사용하며
이번에 바뀐 broker 관측/복구 경로는 실제47개로 다시 검증했다. CI storage의 기존 결합33개를
`--retire-routes`의47개로 확장했다. 새 원격 CI/배포 확인은 별도다.

STREAM 그룹의 업무 결과/재시도·진행 중 전환·새 권한·Secret·종합 활성화와 M5 잔여/M7–M10
전체 수용은 남는다. 경로 CLOSED를 전체 producer 종료나 복구된 서비스 실행 허가로 사용하지 않는다.
