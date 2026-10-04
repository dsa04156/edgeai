# M9 복원 Kubernetes/VD 작업 상태 조정

2026-10-04, ADR0082. 물리적 종료와 DB 실행 정리 후 기록된 업무 상태를 조정한다.

| 실행 | 판정 |
|---|---|
| `20261004T122213Z-ce659483` | 준비 실패: 시험 API의 VD 실행이 꺼져 공개 VD Run 요청503. 소유 DB/API/namespace 정리 확인 |
| `20261004T122355Z-ccd52684` | 28개 통과 후 시험 기대값 실패: 사후 보존 이력은 Result2개와 확정 취소2개로4개. 실제 정리 수는 기대와 일치 |
| `20261004T122619Z-7811eff1` | 실제 PG/Kubernetes35개 PASS/0 |
| `20261004T122910Z-58941ebc` | 진행 중 offload 제외·실제 재시도 테이블 잠금까지 최종37개 PASS/0 |
| `20261004T122425Z-62da8ab2` | 공통 SQL 분리 뒤 기존 Remote 실패 복구15개 PASS/0 |
| `20261004T122911Z-4233255d` | 최종 공통 SQL의 기존 Remote 실패 복구15개 PASS/0 |
| `20261004T122426Z-83ac6073` | 선행 be41a8b CI37200100790의 완료3jobs/원시29개 PASS/0 부분 감사; 전체 CI/새 코드 판정 아님 |

최종 보고서는 `.tools/recovery-kubernetes-workflows-final.json`이다. 실제 PostgreSQL16과
패키징 API, 복원 DB4개, Kubernetes 부모/자식3쌍·백업 후 never-bound Pod1개를 사용한다.
원본 DB는 복구 전에 제거했다. 기존 ADR0081의6개 VD 이력에 공개 API로 생성한 VD 실행3개를
추가했다. API의 실행 계획은 활성화하되 worker는 비활성화하고 전용 namespace만 지정했다.
할당/실패·취소 상태/과거 시각/성공 Result는 명시적 DB fixture이며 실제 업무 실패나 SDK/S3
복구의 증거로 취급하지 않는다. 원래 실행의 claim과 physical container도 합성 fixture다.

먼저 기존 runtime/VD/할당 정리와37테이블 보존을 검증했다. 추가된 과거 allocation3개까지
기존 closure5개를 보존하며 새로 닫는 할당3개·확정 Result2개·미배정1개 판정은 유지한다.
그 뒤 기록된 Kubernetes/VD 취소2건, 원래 기한의 재시도 만료1건, BATCH 후손 SKIPPED2건,
Run5건을 조정했다. 유효한 재시도1건과 결과 미기록 RUNNING1건은 유지한다. 실행/명령/할당/
결과 등 나머지39테이블을 그대로 보존하고 새 Attempt나 재시도 예약을 만들지 않았다.

완료된 Task/Result를 그대로 둔 채 Run만 RUNNING인 추가 fixture2개는 Run만 완료했다.
새 epoch가 이미 기록된 Task는 이전 실패로 덮어쓰지 않았다. 시간을 함께 이동한 원래 예약은
새 기한 없이 만료되며 동일 입력 재실행은 시각까지 변경0이다.

다른 DB의 복원 보고서, 취소 사유 누락, 변경된 재시도 기한, 마지막 관측 뒤 retry-only 쓰기,
실제 task_retry 잠금, 물리적 증거 없이 DB만 TERMINATED인 후손을 검증했다. 마지막 Run UPDATE
오류는 취소/만료/후손 변경을 모두 원복한다. 실제 COMMIT 후 응답 유실은 개인 intent만 남기고
새 출력 경로에서 재실행하면 변경0으로 확인된다. 활성 offload는 미해결로 분리하고 입력을
바꾸지 않는다. 기존 Pod404 거절·inspection GET/POST403·소유 자원 정리도 통과했다.

21테이블 전체 행 guard와 잠금을 사용한다. 최종 원시 보고서의 DB/API/namespace 정리 확인은
모두 true다. 제품 JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`,
V1–V34는 변경하지 않았다. Remote 회귀15개는 실제 API/제공자/복원 DB2개를 사용한다.

선행 CI 부분 감사는 PostgreSQL230개, Remote 결과14개·실패15개 및 소유 자원 정리를 확인했다.
해당 소스에는 ADR0081/0082 확장이 없다. 새37개 kind 게이트는 원격 CI/배포 검증이 남는다.
진행 중 전환·STREAM/journal·결과 회수·전역 writer·종합 활성화와 M5 잔여/M7–M10 전체 수용은
미완료다. 이 시험만으로 전체 복구나 플랫폼 완료를 판정하지 않는다.
