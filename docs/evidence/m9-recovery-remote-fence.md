# M9 참조 Remote 제공자 복구 차단 검증

ADR0073은 단일 SYNTHETIC 참조 제공자 전체의 접근 차단과 계산 종료를 검증한다.
실제 외부 업체 실행기·장비·플랫폼 종합 복구의 완료 근거는 아니다.

2026-10-04 로컬 검증:

| 근거 | 결과 | 확인 범위 |
|---|---|---|
| 20261004T090831Z-b7537085 | 최종 CLI TLS7개 PASS/0 | 이전 controller 거절 뒤 설치/복구 ID·종료 상태를 다시 확인하는 최종 검증 포함 |
| 20261004T090552Z-5b612425 | Python12개 PASS/0 | 새 TLS 복구7개 + 기존 TLS2/부하 판정3개 |
| 20261004T090235Z-293568be | Java Remote gateway13개 PASS/0 | 실제 별도 Python/HTTP/SQLite·파일·실행/취소·재시작·무결성 |
| 20261004T090407Z-e28f1eae | 실제 PG/MinIO27개 PASS/0 | RemoteWorker13·RuntimeArtifact3·RemoteRuntime11, 별도 DB/MinIO 생성 및 종료·삭제 |

새 TLS 복구7개는 다음을 확인한다.

1. 별도 자격·실제 TLS CA/요청 socket pin·provider UUID 오류 거절, inspect 무변경.
2. 실제 성공·실패·실행 중·예약·선행 취소5할당 전체 차단. 실행 중 계산 종료·이전 controller
   PUT/start/cancel/GET403, 완료 파일 bytes·기존 성공/실패 row·실행 횟수3 보존.
   소유 프로세스 SIGKILL 재시작 뒤 같은 provider/recovery ID·전체 이력 보존, 다른 복구 거절.
3. 인증 후 실제 HTTP100을 받은 예약/입력 두 연결을 보류하고 차단. 늦은 본문은403이며
   새 할당 디렉터리·입력 파일·계산이 생성되지 않는다.
4. 차단 PUT 응답을 읽지 않고 연결 종료. 서버 차단을 조회한 뒤 별도 운영 토큰 회전·
   이전 운영 토큰401·같은 복구 ID의 CLI 재개.
5. 일반/운영 자격 동일 값·잘못된 UUID·중복 JSON 필드를 거절하고 비차단 상태 보존.
6. 기존 allocations-only SQLite 레이아웃 업그레이드·입력/row 보존·설치 ID 재시작 안정성.
7. 실제 TLS 서버의 계산 스레드를 시험용 Event로 보류. CANCELLING/worker1에서10초
   CLI timeout/BLOCKED·차단 유지. 계산이 CANCELLED여도 스레드가 살아 있으면 quiescent=false.
   Event 해제 후 실제 thread 종료와 같은 ID CLI 성공을 대조한다. 정지 지연은 명시적 fixture다.

OpenAPI 타입 생성·각 변경 shell 문법·git diff 확인도 수행한다. Java/API/DB migration은
변경하지 않는다. 실제 Spring/S3 시험의 Pod 신원·Kubernetes 전환 관측은 기존 fixture 범위다.
새 테스트는 기존 `scripts/test/test-remote.sh`의 Python discover를 통해 CI scaffold에도 포함된다.
원격 CI/이미지 배포 판정은 별도이며 로컬 시험으로 대신하지 않는다.
API JAR SHA256은 기존3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7과 같다.

보고서는700/600 출력에 보관하고 자격·요청 본문·예외 원문을 출력하지 않는다.
시험 서버/스레드/소유 임시 디렉터리를 정리한다. 설치 DB를 복제해 동시에 실행하는 환경,
외부 Remote 종료 계약, 복원 DB와 orphan 실행 이력의 조정, 장치 journal·서비스 활성화는 남는다.
