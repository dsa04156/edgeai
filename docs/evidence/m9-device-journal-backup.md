# M9 Device journal 일관 백업·격리 복원 검증

2026-10-04, ADR0087. 실제 SQLite/고정 age의13개 `20261004T140112Z-41923f08` PASS/0,
`.tools/device-journal-verified.json`. 선행11개 `20261004T135811Z-1393daae`도 PASS다.

1. 원본 owner를 연 상태에서 fanout의 서로 다른 처리 확인 위치·상태·DATA/END를 암호화했다.
   source의 serial/checkpoint/outgoing은 불변이고 외부 JSON에 장치 식별자·값이 없었다.
2. 원본 볼륨 삭제 후 새 SQLite에 모든 cursor/state/frame/END를 정확하게 복원했다.
   실제 integrity_check와 파일 권한을 검사하고 Journal 열기가 marker로 거절됨을 확인했다.
3. 기존 출력 덮어쓰기·격리본 재백업·dangling marker로 우회하기는 거절했다.
4. 다른 개인 키와 손상 암호문은 게시된 source나 정상 종료 후 임시 평문을 남기지 않았다.
5. 별도 프로세스가 실제 journal owner로 계속 commit/ACK하는 동안8개 snapshot에서
   state/revision/경로 순번의 일치를 확인했다. writer의 revision 증가와 생존도 확인했다.
6. 실제 BEGIN EXCLUSIVE writer 때문에 읽기가 제한 시간 안에 실패했다.
7. Task/input/EXTERNAL 및 서로 다른 Device Session의 journal을 거절했다.
8. 실제 age로 암호화했어도 논리 sequence/cursor/actor/completion이 틀리면 게시 전에 거절했다.
9. 모든 END가 확인된 source의 완료 intent와 끝 cursor가 그대로 복원됐다. 새 grant는 없다.
10. 읽기 중 실제 completion 파일을 교체하면 snapshot을 거절하고 원래 파일 복구 후 동일해졌다.
11. 연결된 source 디렉터리와 실제 SQLite frame index/wire 불일치는 성공 manifest를 만들지 않았다.
12.8MiB가 넘는 실제 payload를2개 조각으로 암호화해 누락 없이 복원했다.
13. 새 source 디렉터리 게시 전과 직후 실제 SIGKILL을 각각 주입했다. 게시 전에는 source가
    없고, 게시 후에는 marker가 있어 열리지 않았다. 두 경우 성공 보고서는 없었다.

확장 시험의 첫 실패135916Z-34a2623c는 fixture가 기존 완료 파일에 새 파일 전용 writer를
사용했기 때문이다. 실제 교체로 수정했다. 이후135957Z-db0f8ad5는13개를 통과했지만 기존
보고서 경로를 다시 사용해 최종 쓰기에서 실패했다. 새 경로의 최종13개 PASS 근거로 판정한다.

공통 논리 검증 추출과 Journal 격리 검사의 기존 Runner111개
`20261004T135456Z-4855a9b2` PASS/0다. API JAR·V1–V34는 변경하지 않았다.
실제 TLS MQTT95개 `20261004T140012Z-b4fa7f1d`도 PASS/0이며 ResourceWarning을 오류로
처리했다. 최초135917Z-f6637bdc는 브로커 실행 경로 미설정으로95개 모두 setup에서
실패했다. 기존 설치의 binary/passwd/library 경로를 명시한 재실행으로 검증했다.
최종13개 보고서에서 별도 writer/locker/강제 종료 프로세스 정리도 확인했다.
새 CI scaffold에 실제 Device journal 검사를 추가했다. 원격 CI/이미지/배포는 후속이다.
합성 Device binding이며 실제 DB/브로커의 재개 권한이나 운영 센서의 replay를 수용한 것은 아니다.
원본 차단·DB/route/journal 대조·키/broker 재적용·서비스 재개와 전체 M9 수용은 남는다.
