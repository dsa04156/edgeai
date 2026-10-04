# M9 복원 Remote 이력 점검 검증

2026-10-04. [ADR0074](../adr/0074-recovery-remote-inventory.md)의 단일 SYNTHETIC 제공자와
복원 DB의 읽기 점검이다. DB 조정 쓰기·서비스 활성화·외부 업체 종료 계약은 포함하지 않는다.

| 실행 | 결과 | 범위 |
|---|---|---|
| 20261004T092539Z-1d04c194 | 실제 PG/TLS10개 PASS/0 | 별도 제공자2·실제 archive 복원DB2·43테이블·페이지 크기2·소유 DB/프로세스 정리 |
| 20261004T092408Z-ed5d7eed | Python14·Java gateway13 PASS/0 | 기존 차단7+새 페이지2, TLS2/부하3, 실제 별도 Python gateway 회귀 |
| 20261004T091744Z-e8dfb4d6 | TLS9개 PASS/0 | 고정 전체7할당의4페이지, 비밀 업무 필드 제외·중복/누락/ID 변경 거절 포함 |

실제10개 시험은 다음을 확인한다.

1. 패키징 API로 Profile/Workflow/REMOTE Run을 생성한다. 실제 Java binding과 요청 digest를
   독립 Python 계산과 대조하고 TLS 제공자에서 합성 선형 계산을 실행한다. 실제 pg_dump/restore
   후 기존 성공 관측과 선행 취소를 MATCHED_TERMINAL/CANCELLED_BEFORE_RESERVATION으로 분류한다.
2. 다른 설치/복구 UUID·TLS 지문을 거절한다.
3. 실제 DB OID와 다른 복원 보고서를 거절한다.
4. 미지원 migration999 fixture를 거절하고 원래 이력을 복구한다.
5. 기존 private inventory와 bytes를 덮어쓰지 않는다.
6. 같은 할당 ID라도 provider key/binding이 다르면 BINDING_CONFLICT와 otherTargets를 보존한다.
7. 제공자 SIGKILL/restart 후 차단·전체 목록 SHA·분류가 유지된다.
8. 위 점검 전후 복원 DB43테이블과 제공자 row 전체가 같다.
9. 별도 실제 복원에서 provider 누락, request digest 충돌, 전체 신원 충돌, 관측 revision 충돌,
   백업 이후 실제 API로 만든 할당을 모두 개별 분류한다. 원본 DB 삭제 후에도 점검한다.
10. 충돌을 발견한 점검에서도43테이블과 제공자 row를 변경하지 않는다.

플랫폼 worker는 시험에서 비활성이다. 제공자 reserve/start/cancel은 실제 TLS RPC로 실행한다.
기존/더 높은 provider 관측은 보호 trigger를 유지한 명시적 SQL fixture이며 Runtime/Result를
자동 조정했다는 증거가 아니다. 출력 목록은 metadata 검증이며 S3 또는 결과 파일 bytes 검증은
ADR0061 등 별도 게이트를 따른다. PostgreSQL16 로컬 검증이며 Compose17 CI 게이트를 추가했다.
검증 보고서의 API JAR은3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7이다.

선행9caa7dd의 CI37188905983은5jobs/원시26개 PASS(092606Z-b89a28f6)다.
PG230·단위122·Runner111/MQTT95·DB차단10/MQTT차단15/S3root차단18·STREAM24를 포함한다.
GitOps d351a3b의 정확한 API/dashboard/MinIO imageID·Ready/PVC·ArgoSynced
(092608Z-eeb39b40), 기존10파일·두PVC·TLS/S3보존(092659Z-cff29cb6)도 PASS다.
공유Ingress aggregatehealth는 Progressing이다. 이는 새 ADR0072–0074 코드의 CI/배포 증거가 아니다.
