# M9 복원 Remote Result·Task·Run 확정 검증

2026-10-04, ADR0078. 최종 실제 결합14개 `20261004T105412Z-07ca4178` PASS/0,
`.tools/recovery-remote-results-final.json`. 선행11개 `20261004T105047Z-d4889119`도 PASS/0다.
실제 PostgreSQL16·패키징 Java API·참조 Remote TLS·별도 TLS MinIO2개를 사용했다.

공개 API가 SERVICE·단일 작업 Run·부모/자식 BATCH DAG를 만든다. 실제 Remote의 부모 작업
2개가 성공한 뒤 pg_dump/restore로 별도 DB2개를 만들고 원본 DB를 제거한다. 원본 MinIO의
실제 고정 파일을 다른 설치로 복제하고 원본을 종료한다. Remote 차단→각 복원 DB runtime
정리→파일 회수→실제 S3 등록을 수행한 뒤 원본 Remote도 종료한다. 이후 결과 확정은 원본
DB/Remote/S3 없이 수행한다. 합성 workload이며 실제 외부 제공자 수용 시험은 아니다.

| 검증 | 결과 |
|---|---|
| 정상 확정 | Result2·실제 고정 S3 artifact2·성공 Task/Attempt2, 단일 Run1 완료 |
| 후속 DAG | 자식1을 기존 초기 Remote binding의 READY/QUEUED로 준비, 새 runtime/명령 없음 |
| 동시 복구 | 실제 두 DB 연결이 같은 이전 상태에서 경쟁, 한 transaction만 확정·다른 쓰기는 guard 충돌 rollback |
| 취소·실패·새 epoch | 늦은 취소·실패 Task·취소 중 Run·다음 attempt·미완료 기존 명령은 쓰기 없이 거절 |
| 신원·TLS | 다른 복원 DB의 bundle과 잘못된 실제 인증서 pin 거절 |
| 변경 경쟁 | 검증 뒤 실제 outbox 변경과 복원 DB marker 변경을 transaction 안에서 거절 |
| 잠금 | 실제 다른 writer의 table lock에5초 제한, 부분 반영 없음 |
| 원자성 | Result/artifact seal 이후 Task UPDATE trigger 오류 주입, 결과/파일참조/Attempt/Task/Run 전체 rollback |
| 멱등 | 같은 입력 재실행은0변경, Result ID·고정 version·timestamp·단일 후속 attempt 보존 |
| Java 계약 | 실제 패키징 JAR의 JsonDocuments로 두 S3 결과 manifest digest가 Python 복구 값과 같음 확인 |
| 기존 결과 충돌 | 같은 bytes의 실제 다른 version도 확정 Result를 대체하지 못함, 기존 고정 receipt는 latest 변경 후에도 성공 |
| 응답 유실 | 실제 COMMIT 성공 뒤 응답 예외 주입, intent 유지·새 실행은 기존 결과 확인 후0변경 |
| API 격리 | 패키징 조회 전용 API에서 두 Result·Remote 신원·SYNTHETIC·artifact 조회, 관리 POST403 유지 |
| 파일 유실 | 정확한 version 삭제 후 replay도 거절, 확정 DB 이력·원래 백업 파일 보존 |

변경 테이블은 task_result/result_artifact/task_attempt/task/workflow_run의5개뿐이다.
다른38개 테이블 전체 fingerprint와 다른 복원 DB를 보존했다. 모든 소유 DB·MinIO 프로세스를
정리했다. DB/저장소/제공자 자격과 raw bundle/SQL/receipt/파일은 개인 경로에만 남긴다.
기존 API JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`는
변경되지 않았다. DDL·일반 API 계약도 변경하지 않았다.

CI storage job에 Compose PostgreSQL과 해당 job에서 빌드한 MinIO binary를 사용하는 별도
`recovery-remote-results` 게이트를 추가했다. 새 CI/배포는 후속이다. 실패/취소 시도 재조정,
STREAM·장치 journal·다른 producer·종합 활성화·M9 전체 수용은 남는다. S3 관리자 삭제와
DB commit의 분산 원자성은 제공하지 않으며 커밋 후 검증 오류에도 격리를 유지한다.

선행 source `cd1424a`의 CI37196161670은 scaffold/storage/runner3개 job 성공을 확인했다.
다운로드한 원시27개와 PG230·Remote retirement17(Compose PG17)·S3 publication12개는
`20261004T105951Z-05a4f945` PASS다. 회수 파일1/79bytes 전달·원래3version 보존과
같은 tested MinIO binary 사용도 확인했다. images job은 진행 중이며 전체 CI/배포 판정은
아직 아니다. 이번 ADR0078 결과 확정14개는 이 선행 CI에 포함되지 않는다.
