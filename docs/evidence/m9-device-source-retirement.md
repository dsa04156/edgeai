# M9 원본 Device source 소유권 종료·결합 검증

2026-10-04, ADR0090. 로컬 검증:

| 범위 | 결과 | 원시 실행 |
|---|---|---|
| 별도 writer·종료 CLI·최종 암호화 snapshot·잠금 경쟁 | 11개 PASS/0 | `20261004T150639Z-87144f5a` |
| 공개 API/복원 PG·TLS MinIO/MQTT·원본 source 결합 | 33개 PASS/0 | `20261004T151649Z-82a21d30` |
| Runner 전체 | 111개 PASS/0 | `20261004T151238Z-82901393` |
| HTTPS/TLS MQTT 전체 | 97개 PASS/0 | `20261004T151238Z-d7b91f12` |
| 기존 장치 암호화 백업 회귀 | 13개 PASS/0 | `20261004T151238Z-fca4c959` |

개인 보고서는 `.tools/device-source-retirement-first.json`,
`.tools/recovery-device-source-authority-final2.json`,
`.tools/device-journal-retirement-regression.json`이다. 원본/키/상태/로그를 게시하지 않는다.

별도 writer는 백업 이후 실제 revision2와 두 경로의 다음 프레임을 기록했다. 종료 marker를
감지한 뒤 잠금을 해제하고 exit0으로 종료했다. CLI는 오래된 복원 snapshot과 다름을
표시했고 원본 최종 snapshot을 다시 age 암호화/격리 복원한 경우에만 신선한 종료 증거와
결합됐다. 이전 snapshot 재사용은 거절했으며 모든 최종 프레임을 보존했다.

잠금을 계속 잡은 실제 프로세스는 2초 제한에서 BLOCKED였다. 프로세스는 여전히 살아 있었고
marker는 남았다. 실제 종료 후 같은 UUID로 재개했으며 다른 UUID는 거절했다. 실제 잠금
파일 교체, 원본 SQLite 쓰기, intent 게시와 잠금 획득 사이 inode 교체, SDK 잠금 획득
직후 marker 등장, dangling marker를 검증했다. 테스트가 소유한 프로세스는 모두 정리했다.

실제 HTTPS/MQTT 별도 DeviceSource 프로세스도 DATA를 전송한 뒤 marker를 보고 연결과
journal을 닫고 종료했다. transaction 중 marker 생성은 쓰기를 되돌렸고 재시작은 새 HTTP
요청 전에 거절됐다. 초기 두 사례 `150330Z-5c327b07` 뒤 전체 97개로 재검증했다.

기존 ADR0089의 27개에 실제 원본 종료 증거 결합 6개를 추가했다. 공개 STREAM·새 PG16
복원 DB4개·43개 테이블·TLS MinIO2개·원래 MQTT 연결2개 회수를 재사용했다. CLI로 동일
복구 UUID·최종 snapshot·잠금 해제를 확인하고, 현재 잠금 점유·다른 종료 UUID·객체 검증 중
원본 SQLite 쓰기·부분 인자·원본 디렉터리 유실은 거절했다. DB/journal 및 격리를 보존했고
테스트 DB/API/MinIO/broker/client와 추가 원본을 정리했다. 활성화는 수행하지 않았다.

첫 결합 실행 `151455Z-40c8b736`은 로컬 Mosquitto control 경로 누락으로 setup에서 실패했다.
직접 Python 실행 `151632Z-b2d3cab5`은 DB 환경 미로딩으로 시작 전에 실패했다. 설치된
control/plugin 경로와 프로젝트 환경을 읽는 기존 wrapper를 사용한 최종33개로 판정한다.
이 두 실행은 성공 근거에 포함하지 않는다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`과
V1–V34는 불변이다. 실제 MinIO SHA는 ADR0089와 같다. CI에 종료11개를 추가하고 기존
결합27개 gate를33개로 확장했다. MQTT 전체는95→97개다. 신규 원격 CI/배포는 후속 확인한다.

이 증거는 원본 LOCAL journal의 소유권 해제다. 전체 호스트/외부 producer 물리 종료,
API 자격 회수, 새 Secret/grant·STREAM/group/종합 활성화, 실제 장비 수용을 증명하지 않는다.
런타임 claim·checkpoint DB receipt는 제약이 활성화된 SQL fixture다. M5 잔여/M7–M10
전체 완료는 아니다.
