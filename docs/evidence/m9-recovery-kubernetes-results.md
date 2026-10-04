# Kubernetes 확정 Result의 복원 DB 반영 검증

2026-10-05. [ADR0104](../adr/0104-recovery-kubernetes-results.md), [실행법](../recovery-kubernetes-results.md).

`20261004T221111Z-8496de2b`: 새 결합20개 PASS. 공개 API가 만든 BATCH 부모/자식,
실제 Job/Pod·Pod-bound TokenReview·TLS Runner claim·서명 업로드·commit을 사용한다.
Job과 DB runtime 연결은 명시적 fixture이고 시험 클라이언트가 합성 출력을 제출한다.
이 시험 자체가 실제 모델 계산을 입증하지 않는다. 실제 Runner의 기록 생성 검증은
[ADR0103 근거](m9-kubernetes-result-journal.md)의 Kubernetes3개/결과6개를 따른다.

원본 DB와 MinIO 데이터를 제거하고, 별도 TLS MinIO 백업과 복원 DB5개로 검사했다.
claim 이전2개·claim 후1개·commit 후1개와 후검증 실패용1개 복원본을 사용한다.
소유 namespace의 Pod에서 실제 부모/자식 프로세스를 종료하고 `CHILD_REAPED`와
retained finalizer/종료 시각·quota/Job suspend를 검증했다. 모든 DB/API/MinIO/namespace
정리가 최종 보고서에서 확인됐다.

검증한 동작:

- 최초 Result ID·committedAt·manifest digest·파일 버전 보존, 누락된 원래 claim 신원만 복원.
- runtime 종료 상태/시각·nonce 보존, 자식 READY/QUEUED1개·새 runtime0개·타38테이블 보존.
- 동일 CLI 재실행0변경, 이미 claim된 복원본과 이미 commit된 복원본, pending 발행 큐만 완료.
- parent 성공 이후 child/Run 실패 기록을 보존한 재실행.
- 원래 API digest 대조와 신원·시간·출력23종 변형 거절.
- 시작 기록 유실·다른 TLS pin·동일 bytes의 다른 Result head·중복 JSON·고정 출력 유실 거절.
- 취소/실패, 검증 전후 실제 DB 변경, 실제 잠금 충돌, 자식 변경 시점의 SQL 오류와 전체 rollback.
- 실제 COMMIT 응답 유실 뒤 원래 결과 보존·무변경 재실행.
- COMMIT 후 S3 권한 기록 교체 시 성공 보고 거절·격리 유지·원래 증거 복귀 후 재검증.
- 패키징된 조회 전용 API의 결과 조회·관리 쓰기403.

원시 보고서는 `.tools/recovery-kubernetes-results-current.json`과
`docs/evidence/runs/20261004T221111Z-8496de2b/`다. 현재 production JAR은
`6d8e60f54575ae6cc91efd1eeb0c6a9fc9d125bd1a6fd91d8421aee8cd2fc175`이며 이 변경에서
Java/migration을 수정하지 않았다. 새 Python 복구 경로와 실제 API가 같은 JAR로 검사됐다.

초기 `20261004T220812Z-d4b03f1b`는12개 후 시험의 조회 응답 `id` 경로가 틀려 FAIL했다.
실제 응답은 `run.id`다. 해당 시험을 수정한 위20개가 전체 통과했으며 초기 자원도 정리됐다.

공유 BATCH transaction SQL의 기존 Remote 결과15개
`20261004T221124Z-a42d00d4`, 실패15개 `20261004T221659Z-66ca50d8` PASS/소유 정리.
앞선 실패 회귀 `20261004T221124Z-c5610b52`는 업무 case0에서 원본 Remote fence CLI exit2로
끝났다. 해당 최초 실패의 상세 원인은 확보되지 않았으며 수정됐다고 주장하지 않는다.
임시 fixture 정리 전에 비밀값 없는 상태/카운터를 오류에 보존하도록 시험 진단을 추가했다.
재실행은 통과했지만 최초 일시 실패의 원인 규명은 남는다.

기존 실제 API 시작 기록 복구91개도 `20261004T221700Z-599e0412` PASS다. 변경한 TLS API
시험 helper의 기존 전환 경로, Java 작업 digest17개, Remote/VD/미claim Job 조정과 원래
claim/Result·타43테이블 보존을 재검증했다. 소유 namespace/DB/API/제공자/MinIO 정리를 확인했다.
Remote fixture 자체12개 `20261004T222124Z-3847b258`도 PASS다. Python/shell 문법·CI YAML,
명령/보고서 연결·문서 링크·기존 JAR 동일성·최종 보고서 범위는
`20261004T222243Z-67e14a52`에서 확인했다.

CI kind에 이20개 명령과 보고서 artifact를 추가했다. 선행 CI는55분 job 제한으로 취소됐으며
[관측과 설정 변경](m9-ci-duration-limit.md)을 별도 기록했다. 새 원격 CI·배포 성공과
STREAM/VD 권한 소비·전역 writer/API 차단·전체 재활성화·실제 모델/외부 계약은 아직 미완료다.
