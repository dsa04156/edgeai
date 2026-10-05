# M9 복구 잠금 시험의 측정 범위 수정

2026-10-05. 실제 PostgreSQL16·패키징 API·참조 Remote TLS·TLS MinIO를 사용하는
Remote Result 복구 시험에서, 5초 잠금 제한의 측정 범위에 전체 DB 전후 검증이
포함되는 오류를 재현했다. 제품 복구 SQL·잠금 제한·권한·원자성 조건은 변경하지 않았다.

## 재현과 수정 검증

| 검사 | 수정 전 | 수정 후 |
|---|---|---|
| evidence | `20261005T080049Z-1fb43882` | `20261005T080254Z-e1553fb3` |
| 실제 복구 요청의 거절 | 5.043초, PostgreSQL lock timeout | 5.032초, PostgreSQL lock timeout |
| 전후 검증을 포함한 시간 | 20.855초 | 20.305초 |
| 시험 결과 | 6개 통과 후 기존 15초 상한 assertion 실패 | 전체 15개 통과 |
| 소유 DB·프로세스 정리 | 보고서 확인 | 보고서 확인 |

격리 DB 두 개의 실제 고정 파일·원래 결과 복원을 수행했다. 잠금을 잡은 구간에서만
읽기 전용 테이블 fingerprint 조회 90회에 각 150ms를 넣었다. DB 잠금·SQL 응답·
복구 결과·시계는 대체하지 않았다. 수정 전에는 `test-recovery-remote-results.py:261`의
시간 assertion만 실패했고 PostgreSQL은 정상적으로 잠금 요청을 거절했다.

`refuse_prepared`는 전후 테이블 비교를 그대로 실행하면서 복구 `apply` 호출에 걸린
시간을 별도로 반환한다. 4초 이상·15초 미만 검사는 이 값에 적용하며, 전체 검사 시간도
`lockCheckSeconds`로 남긴다. SQL의 `lock_timeout=5s`와 `statement_timeout=30s`는
기존과 같다. 실패 보고서에는 최대 5개 코드 파일명·행 번호만 추가한다. 예외 메시지,
응답 본문, SQL, 자격 정보는 공개하지 않는다.

수정 후 15개에는 실제 동시 복구의 단일 승자, 후반 SQL 오류의 전체 rollback,
COMMIT 응답 유실 후 중복 없는 재실행, 원래 Result·파일 version 보존과 조회 전용
API의 쓰기 거절이 포함된다. 다른 40개 테이블도 보존했다. JAR SHA256은
`9203c8650a183516ab6f00befdaf8ae8e1e33f2328f9f44a9b9684576fbea79e`로 같다.

개인 원시 보고서는 `.tools/remote-lock-timing-{before,after}.json`, 요청 시간·실제
lock timeout·주입 조회 횟수는 같은 이름의 `-probe.json`에 있다. 재현 도구는
`.tools/probe-remote-lock-timing.py`이며, `with-stream-completion-env.sh`로 환경을
읽어 실행했다. 시험 DB·파일 내용·로그와 자격 정보는 공개하지 않는다.

최종 감사 `20261005T080631Z-4596ba86` PASS에서 원본 소스/수정 소스와 각 시험의
SHA256 일치, JAR·V39·V40 불변, 두 시험의 소유 DB 총6개 실제 부재를 확인했다.
`.tools/remote-lock-timing-audit.json`에 결과를 보존했다.

## CI와 남은 범위

선행 `e15e705`의 CI37279044604는 storage Remote Result 복구 시험의 같은
6개 성공 이후 `AssertionError`로 실패했다. 양쪽 native Runner·index·scaffold는
성공했고 images/GitOps는 생략됐다. 원격 private traceback이 보존되지 않아
해당 CI의 실패 원인이 이번 시간 측정 오류와 같다고 단정하지 않는다.

새 실패 위치·두 시간 값을 통해 후속 원격 실행에서 구분한다. 로컬 Docker socket을
사용할 수 없어 Compose 경로는 원격 CI에서 확인한다. 기존 Kubernetes VD STREAM
`vd-distinct-second`의 `RUNNER_FAILED`, DB I/O 지연, M5 잔여 및 M7–M10 전체 수용은
별도 미해결 사항이다. 이 수정은 새로운 제품 배포나 전체 단계 완료를 의미하지 않는다.
