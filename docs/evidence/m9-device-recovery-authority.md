# M9 장치 복구 데이터·원본 브로커 결합 검증

2026-10-04, ADR0089. 실제 27개 `20261004T144757Z-420384e3` PASS/0,
`.tools/recovery-device-authority-final2.json`. 선행 26개
`20261004T144308Z-78926d5a`도 PASS다.

ADR0088의 실제 공개 STREAM API·Device fanout·원본 DB/source 삭제·43개 테이블/파일
보존 시험을 재사용했다. 이번에는 실제 EXTERNAL Journal checkpoint 4개를 원본 TLS MinIO에
기록하고 별도 TLS MinIO에 고정 version을 보존해 복제한 뒤 원본 저장소도 종료했다.
PostgreSQL은 서로 다른 snapshot에서 새 DB 4개에 복원했다.

실제 TLS Mosquitto에서 원래 Device/Task 계정으로 같은 binding의 DATA 프레임을 송수신했다.
기존 MQTT 차단 명령으로 관리자 자격을 교체하고 두 계정을 disable한 뒤 기존 연결 종료와
재접속 거절을 확인했다. 결합 검사 CLI는 이 실제 브로커·replica·복원 DB/journal을 사용했다.

추가 검증:

- 모든 DB 참조의 고정 객체를 읽고 현재 소비자 checkpoint 1개의 실제 JSON과 상태를
  metadata에 대조했다. 원래 MinIO/DB/source가 없어도 통과했다.
- 다른 복원본에 checkpoint 파일 SHA/고정 version은 맞지만 상태 summary SHA가 다른
  SQL receipt를 넣었다. DB 제약과 불변 trigger는 유지했다. 단순 참조/metadata 대조는
  통과해도 실제 bytes를 파싱한 결합 검사는 거절했다.
- 검사 중 브로커 명령은 getRole/getDefaultACLAccess/listGroups/listClients뿐이었다.
  DB 43개 테이블과 journal 파일 해시는 그대로였다.
- 같은 checkpoint key에 새 버전을 써도 기존 고정 version으로 검증했다. 이후 필요한
  원래 version을 삭제하면 이전 성공 보고서나 새 latest 버전으로 통과하지 못했다.
- Device 계정을 다시 enable하면 거절하고 그 상태를 자동 수정하지 않았다. 파일 확인
  뒤 실제 enable 경쟁도 거절했다.
- 다른 복구 UUID, 다른 TLS leaf pin, 다른 MinIO deployment manifest를 거절했다.
- 객체 검증 중 실제 DB 변경, 원래 관리자 비밀번호 복구도 이전 성공을 무효화했다.
- 마지막까지 source 격리가 유지됐고 시험 DB/API·MinIO/Broker 프로세스와 MQTT client를
  정리했다. 원본 프로세스/전역 quiescence 및 activated 플래그는 false였다.

첫 실행 `144043Z-855cd5f9`는 로컬에 없는 MinIO 실행 경로를 지정해 setup에서 실패했다.
설치된 `.tools/minio`를 사용한 `144109Z-8b0115d8`은 17개 통과 뒤 Mosquitto가 false인
disabled 필드를 생략한 것을 시험이 직접 인덱싱해 실패했다. 기존 inventory 계약처럼
누락을 false로 읽도록 고쳤다. 추가 복원본 시험의 `144526Z-47363699`는 DB 이름이
63자 제한을 초과해 생성 전에 거절됐다. 짧은 소유 이름을 사용한 최종 27개로 판정한다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`와
V1–V34/Runner 실행 코드는 불변이다. 로컬 PostgreSQL16, MinIO 실행파일 SHA256은
`a18c259d800694d3d48b5d4d25091b053359be11d8e8c834b8481e445ad52c48`다. CI는 동일 job에서
검증한 MinIO binary와 PostgreSQL17/Paho/age를 사용하도록 연결했다. 기존 별도 16개
gate를 storage job의 27개 결합 gate로 확장했다.

런타임 claim·broker 활성화/DB checkpoint receipt는 SQL fixture이며 실제 장비/SDK의
종합 재개는 아니다. 원본 Device 프로세스 종료, API/다른 producer 권한 회수, 새 Secret·
broker grant·복구 활성화, 신규 원격 CI/배포와 전체 M5 잔여/M7–M10 수용은 남는다.
