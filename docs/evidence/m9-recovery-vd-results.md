# VD 시작·확정 결과의 격리 DB 복원 검증

2026-10-05. [ADR0107](../adr/0107-recovery-vd-results.md), [실행법](../operations/recovery/recovery-vd-results.md).

`20261005T001119Z-2e34d057`: 실제 결합24개 PASS. 원본 PostgreSQL DB/MinIO 데이터를
제거한 뒤 별도 TLS S3 백업과 복원 DB5개를 사용했다. 실제 Secret 소유 supervisor Pod,
Pod-bound TokenReview와 TLS Runner claim·서명 업로드·commit으로 원래 시작/결과 기록을
만들었다. 공개 API가 만든 Profile/VD/DAG/Run을 사용한다. Supervisor readiness와
allocation은 명시적 DB fixture이며 시험 클라이언트가 합성 출력을 제출한다. 이 시험
자체가 실제 AI 모델 계산이나 VD supervisor 프로그램의 작업 배정을 입증하지 않는다.

원래 Result ID/committedAt·manifest digest·고정 파일 버전, VD ID/배정/세대/세션/슬롯을
대조했다. claim 이전 복원본은 원래 producer 신원만 채우고 종료 상태·시각·nonce는
보존한다. 부모 성공/자식 READY·QUEUED1개를 같은 transaction으로 반영하며 새 runtime은
없다. 기존 VD allocation/supervisor를 포함한 다른38개 테이블 전체 행 해시를 보존했다.
이미 claim한 복원본과 이미 commit된 복원본, 자식 실패 후 재실행도 확인했다.

검증 범위:

- 원래 digest 대조, 결과 신원/시간/출력23종과 VD 시작 배정/세션/슬롯/설정/기한18종 변형 거절.
- 나중 heartbeat의 lease가 최초 기록을 대체하지 않음, NOT_STARTED/미종료 배정 거절.
- 같은 Pod 신원으로 시작/결과의 allocation ID를 함께 바꾸고 백업 manifest도 맞춘 경우 거절.
- 시작 기록 유실, TLS pin 불일치, 동일 bytes의 새로운 Result version, 중복 JSON, 고정 출력 유실 거절.
- 늦은 취소/실패, 실제 DB 관측 전후 경쟁 쓰기, 실제 테이블 잠금 제한, 마지막 자식 변경 오류 시 전체 rollback.
- 실제 COMMIT 응답 유실 후 무변경 재실행, COMMIT 후 저장소 기록 변경 시 성공 보고 거절·격리 유지.
- 원래 발행 큐 완료와 동일 CLI 재실행0변경, 조회 전용 API 읽기와 관리 쓰기403.
- 실제 부모/자식 프로세스 종료의 CHILD_REAPED, 보존 Pod 종료 시각, namespace/DB/API/MinIO 소유 자원 정리.

원시 보고서: `.tools/vd-results-recovery-verified.json`, 위 evidence run 디렉터리.
시험 API JAR SHA256은 `ac681d4ca1bcf588f0fbd3b8a4fb17876097567c0ff1557def6f88029cd9a453`다.
이 변경에서 Java/migration을 수정하지 않았다. 실제 Pod 이미지는 선행077d139 소스의
검증된 Runner index `8c004ca49d72e78ad1dd1b49a6d868adf2899a7a2f2d208cb13f2d2309507632`다.

최초 `20261005T000319Z-f98d09f2`는 fixture의 supervisor lease1시간이 실제 시작 기록의
최대60초 규칙과 달라 claim400으로 실패했다. 실제 규칙에 맞춘 후 기본20개
`20261005T000441Z-1778f4ba` PASS였다. 제품 시간 제한은 바꾸지 않았다.
확장24개 `20261005T000645Z-a26f0858`는 모든 업무 검사를 통과했지만 마지막 DB 정리에서
TimeoutExpired로 전체 FAIL이다. 당시 DB wait/명령별 경계 증거가 없어 원인을 확정하지
않는다. 원래 복원 OID·namespace UID/소유 label을 대조한 별도 정리
`20261005T001059Z-fc43b9a7`에서 DB5개·namespace 부재를 확인했다. 이후 실패 경계/개인
traceback 진단을 보완했고 최종24개는 정리까지 PASS다. 재실행 성공을 최초 시간 초과
원인 해결로 간주하지 않는다.

기존 Kubernetes 결과20개 `20261005T001319Z-48d6d047`, 공유 transaction의 Remote 결과15개
`20261005T001459Z-362633f0`도 PASS/소유 정리다. 공통 시작 기록의 기존91개 회귀 `20261005T001546Z-842f567c`도 PASS/소유 정리다.
실제 TokenReview/TLS 시작 기록·혼합 전환·Java 작업 digest17입력·원래 claim/Result와
타43테이블 보존을 재확인했다.
새24개를 kind CI 명령과 보고서 artifact에 연결했다.
선행edc9625의 CI37245085073에서 완료5jobs/원시35개를
`20261005T001301Z-86a2651b`로 감사했다: 단위122/PG232/Remote Python24·Java13,
native ARM/x86 각각 Runner111/MQTT97 PASS. 저장소 job의 runtime79도 원시
RUNTIME_TEST_COUNTS 0실패/0skip을 확인했다. images는 진행 중이며 이 새 복원 코드의
원격 CI·배포 근거는 아니다. native index의 두 실제 registry manifest 대조도
`20261005T001511Z-7722ee72`에서 PASS다. index는
`21ff84a4a6478080934e06275bcf721bbe7119121769903209f762595971fed9`이며 전체 CI/배포와 구분한다.

최종 근거 감사 `20261005T002201Z-f9c0e7b5` PASS: 새24+기존20+15+91=150개,
모든 소유 정리, 같은 API JAR/적용 V36 바이트, Python/shell 문법·CI YAML/명령/보고서
연결·문서 링크를 확인했다. `.tools/vd-recovery-final-audit.json`에 보고서와 소스 해시를 남겼다.
이번 검증은 새 코드의 원격 CI/배포나 최초 DB 정리 지연 원인 해결을 주장하지 않는다.

진행 중 VD 전환의 시작 허가 조정·STREAM 결과 권한, 백업 이후 알려지지 않은 모든
실행의 발견·회수, 전역 writer/API 차단, 서비스 종합 재가동·실제 모델/외부 계약과
전체 M0–M10 수용은 남는다.
