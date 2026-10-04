# M9 — 복원 DB와 고정 S3 참조 대조 검증

2026-10-04 KST. [ADR0061](../adr/0061-restored-database-storage-reference-gate.md)의 대조 명령을
실제 PostgreSQL16·패키징 API·TLS MinIO 두 개로 검증했다. 합성 데이터이며 Pod claim과
broker 활성화는 명시적 SQL fixture다. DB의 FK/check/trigger는 그대로 적용했다.

최종 `20261004T014850Z-ae31f51c`의 `scripts/test-recovery-references.sh`가9개 사례
exit0/PASS다. 결과2개(빈 파일·한글 경로의 이전 버전), Runner SDK로 생성한 과거/최신
체크포인트2개를 사용했다. 백업에는 같은 key의 새 버전까지5개가 있으며, 복원 DB의 전체
고정 참조4개/1,287bytes를 실제 읽었다. 원본 DB는 삭제했고 원본 MinIO 프로세스는 종료했으며
검증 명령에는 source MinIO 자격 증명을 전달하지 않았다.

| 경계 | 실제 확인 |
|---|---|
| 전체 참조 | 새 DB를 실제 pg_restore하고 결과2개·과거/최신 checkpoint2개의 version/bytes/SHA 일치 |
| 기존 보고서 | 기존 output 디렉터리를 거절하고 원래 성공 보고서와 파일 목록을 보존 |
| 과거 체크포인트 | manifest에서 과거 checkpoint만 누락해도 실패, 최신 버전으로 대신하지 않음 |
| 결과 버전 | 같은 경로의 새 version이 있어도 DB가 참조한 이전 version 누락은 실패 |
| DB/manifest 불일치 | manifest의 SHA 또는 길이가 DB와 다르면 실패 |
| 복원 신원 | 다른 DB OID와 다른 임의 restoreIdentity를 각각 거절 |
| 저장소 신원 | manifest의 다른 MinIO deployment ID를 거절 |
| 미래 schema | 실제 migration 이력에 추가 버전을 넣으면 exit2/BLOCKED, 제거 후 정상 대조 |
| 실제 파일 누락 | manifest/DB는 유지하고 replica의 과거 checkpoint version만 영구 삭제하면 실패 |

실패 때 성공 보고서가 없고, 모든 대조 이후 복원 DB의 두 참조 테이블 전체 내용이 그대로임을
확인했다. 소유 API·MinIO 프로세스 종료와 소유 DB 제거도 확인했다. fixture의 live runtime
metadata를 실제 worker에 연결하거나 외부 producer를 재조정한 시험은 아니다.

초기8개 `20261004T014603Z-38fa832e` 이후 기존 보고서 보존 및 임의 복원 식별자 거절까지
포함한 위9개를 확인했다. DB 복원 식별자 추가 후 기존 PostgreSQL 백업·복원10개도
`20261004T014814Z-0a42874e` PASS다(41테이블·Flyway34행·패키징 API·실제 오류 정리).
두 실행의 요약 JSON은 각 evidence 폴더에 복사했다. archive·원문 파일·TLS 개인 키·계정·
클라이언트 로그는 비공개 `.tools`에만 보관한다.

현재 JAR SHA-256은 `33ba4881ea9982ac2ab210f15663dc708dfc81d8959065bad6a4ffe14650c023`,
MinIO binary SHA-256은 `a18c259d800694d3d48b5d4d25091b053359be11d8e8c834b8481e445ad52c48`다.
CI storage job에 Compose PostgreSQL17과 같은 job의 MinIO binary로 이 시험을 추가했으며,
신규 코드의 원격 통과 판정은 별도다.

ADR0062 격리 기동/조회 모드가 들어간 JAR
`1b7b33f6a797c11a49e07b4127bcee33293ed5ec3d2eb2fe38e0dd2792e50b96`으로도 동일9개를
`20261004T021137Z-d86797ad`에서 재검증해 PASS했다.4고정버전/1,287bytes·원본 유실·
거절·소유 DB/프로세스 정리를 유지했다. [격리 검증](m9-recovery-quarantine.md).

이 결과는 DB snapshot과 고정 S3 참조의 대조다. 운영 Secret/CA·broker/device journal·
원래 producer 권한 회수·중복 실행 방지·서비스 활성화·RPO/RTO를 포함한 전체 M9 수용은 남는다.
