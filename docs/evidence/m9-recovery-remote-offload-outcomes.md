# M9 Remote 전환 대상 결과와 원래 재시도 예산

2026-10-04, ADR0086. 수정 전 `20261004T133854Z-dd8edd2d`는66개 검사 후 실제
STARTING 전환의 성공 결과를 별도 Result 복구가 허용하는 문제로 FAIL했다.
실제 참조 Remote의 성공 파일을 회수하고 TLS MinIO 고정 version에 등록한 상태였다.
소유 namespace/DB/API/Remote/MinIO는 실패 뒤에도 모두 정리했다.

수정 후 같은 결합 시험69개 `20261004T134212Z-7fca0d0d` PASS/0,
`.tools/recovery-mixed-outcomes-fixed.json`. 복원DB6개, Kubernetes 부모/자식5쌍,
never-bound Pod2개와 실제 Remote 할당6개를 사용했다. 기존64개에 다음5개를 추가했다.

1. 실제 FAILED 대상2개는 이미 지난 STARTING 기한 대신 WORKLOAD_FAILED를 사용한다.
2. 실제 task_retry INSERT trigger 오류로 전환/Attempt/runtime/Task 쓰기 전체가 원복된다.
3. 실제 COMMIT 응답 유실 뒤 재실행은 변경0이다. 전환 실패2개 중 하나는 최종 실패,
   하나는 원래 Run 정책의 재시도1개다. OFFLOAD를 제외한 사용 횟수1, 첫 Attempt 시각의
   원래 기한과 실패 반영 시각+7초 backoff를 SQL로 확인했다. 기존 예약을 합한 대기는2개다.
   source runtime 전체/OFFLOADED와37개 다른 테이블은 변경되지 않았다.
4. 예약 기한을1초 늘린 DB는 쓰기 없이 거절한다. 원래 기한으로 되돌리면 전체 hash가 같다.
5. 원본 S3를 종료한 뒤 실제 성공 파일을 별도 TLS 저장소에 등록해도 STARTING 전환은
   Result로 확정할 수 없다. 정확한 거절 사유를 검사하고 Operation/Task/Attempt와
   전체 DB 불변·일반 workflow 변경0을 확인했다. 시작 claim/허가를 생성하지 않는다.

기존 Result 복구에 검사 뒤 실제 DRAINING 전환 삽입 경쟁을 추가한15개
`20261004T134221Z-9f81f337` PASS/0다.16테이블 guard가 커밋을 거절하고, 이후
DRAINING/CANCELLING 상태에서도 Result 복구가 쓰기 없이 거절된다. 기존 동시 복구,
고정 S3·Java digest, 원복/응답 유실·후속 Task·조회 격리 검사도 모두 통과했다.
공통 실패 복구15개 `20261004T134221Z-e1e9e102` PASS/0다. 세 시험의 소유 DB/프로세스/
namespace 정리를 확인했다.

현재 API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`,
MinIO binary SHA256은 `a18c259d800694d3d48b5d4d25091b053359be11d8e8c834b8481e445ad52c48`이다.
JAR/V1–V34는 변경하지 않았다. Python 변경 파일 구문·CI YAML과 MinIO digest 연결도 확인했다.
CI kind 검사를69개로 확장하고, storage job이 검증한 정확한 이미지 digest에서 MinIO
binary를 추출하도록 연결했다. 새 소스의 원격 CI/배포 성공은 아직 주장하지 않는다.

전환/claim/과거 시각은 명시적인 DB history fixture이며 Remote 계산·TLS 파일·DB transaction·
Kubernetes 종료는 실제다. 이 시험은 공개 offload 요청이나 실제 업체 계약의 수용이 아니다.
시작 허가가 누락된 성공의 journal 복원·STREAM/group/checkpoint·전역 writer·종합 활성화,
M5 잔여/M7–M10 전체 목표는 남는다.

선행 fd8830db의 CI37204890109은 완료된 runner/storage/scaffold의 다운로드 원시29개,
PG230·Remote 결과14/실패15 및 소유 정리가 `20261004T134749Z-6c945846`에서 PASS다.
images의 Kubernetes 검사는 당시 진행 중이었다. 이 부분 감사는 이번 ADR0086이나
전체 CI/새 배포의 성공 근거가 아니다. 진행 중인 CI를 취소하지 않도록 새 push는 대기한다.
