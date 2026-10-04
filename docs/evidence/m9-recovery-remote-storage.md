# M9 Remote 복구 파일의 S3 고정 버전 등록 검증

2026-10-04, ADR0077. 최종 실제 TLS MinIO12개 `20261004T102527Z-eead97ca` PASS/0,
`.tools/recovery-remote-storage-final.json`. 선행11개는 `20261004T102306Z-32c74d00` PASS/0다.
입력은 실제 PG16/Java API/Remote TLS/pg_dump·restore의17개
`20261004T101935Z-11915ad3`가 생성한 private bundle이다. 파일은1개/79bytes다.
최종 private 경로 검사를 포함한 준비17개도 `20261004T102946Z-909eeee6` PASS/0이며,
새 별도 경로로 검증된 bundle을 내보냈다. 기존 입력 bundle은 그대로 보존했다.

후속 cd1424a의 CI37196161670은5jobs/원시29개 모두 PASS/0다
(`20261004T114232Z-47e37693`). Compose17의 Remote17개/실제 회수 파일1개·79bytes가
S3 publication12개로 전달됐고 같은 MinIO binary·기존3버전 보존·동시 등록1version을 확인했다.
GitOps `760908baf39e78b47ad9ac12b4dcf827e59097d0`의 실제 API/dashboard/MinIO imageID,
Ready/PVC Bound·Argo Synced는 `20261004T114725Z-53fcdb3b` PASS다.
그 뒤 원래10파일/두 PVC UID·TLS256KiB/익명403·probe 정리도
`20261004T114806Z-497eac42` PASS다. 공유 Ingress aggregate health는 Progressing이다.
먼저 실행한 `114233Z-e8e53575`는 로컬의 이전 image pin을 읽는 보조 검사 때문에 FAIL이었다.
원격 GitOps pin으로 검사 대상을 고쳐 위 성공을 확인했으며 배포 실패로 판정하지 않는다.
ADR0078–0080의 후속 변경은 이 CI에 포함되지 않는다.

원본 MinIO와 별도 대상 MinIO 사이에3개 버전(같은 key의 두 버전·빈 파일)을 실제 복제한다.
원본을 종료한 뒤 Remote bundle의 파일을 같은 artifact 버킷/Java와 같은 object key 규칙으로
등록한다. DB/Remote 자격 없이 동작하며 새 파일 bytes와 반환한 version을 실제 GET으로 대조한다.

| 시험 | 실제 결과 |
|---|---|
| 등록·재실행 | 새 version1개, 같은 입력은 동일 version 재사용·추가 PUT 없음 |
| 응답 유실 | 실제 PUT200 뒤 호출 경계에서 OSError 주입, 개인 intent 유지·재실행은 기존 version 재사용 |
| 동시 등록 | 두 실제 HEAD가404인 지점에서 barrier 후 동시 조건부 PUT, 새 version1개·두 receipt 같은 version |
| 대상 거절 | 잘못된 실제 pin·CA·다른 MinIO 설치는 객체 추가 없이 거절 |
| 충돌 보존 | 같은 key에 다른 latest bytes를 넣으면 publish 거절·모든 version 유지, 기존 고정 receipt는 성공 |
| versioning | Suspended이면 거절, Enabled 복원 뒤 고정 version 검증 성공 |
| 재시작 | 대상 SIGKILL 후 재시작에서 설치 ID·고정 신규 version·기존3개 version 유지 |
| receipt | task/provider/recovery ID·개수 변경은 거절, 원본 bundle은 변경되지 않음 |
| 로컬 파일 | 별도 복사본의 실제 파일 손상은 저장소 변경 전에 거절 |
| 고정 version 유실 | 새 version을 영구 삭제하면 다른 latest가 있어도 검증 실패, 원래3개 version은 유지 |

직접 장애/경합 주입은 소유한 TLS 프로세스/파일/버킷에만 적용한다. 원본 Remote bundle은
변경하지 않으며 모든 소유 MinIO 프로세스를 종료했다. 개인 데이터는 `.tools`에 보관하고
공개 보고서에는 집계·정리·MinIO binary SHA만 기록한다. 실제 DB Result commit 시험은 아니다.

CI는 Remote retirement17개와 publication12개를 같은 storage job에서 순서대로 실행한다.
retirement가 생성한 실제 private bundle을 그대로 전달하고 같은 CI MinIO binary를 사용한다.
새 CI/배포, Result·Task·Run 결과 확정, journal·종합 활성화와 M9 전체 수용은 후속이다.

선행 코드 bf1ba48의 CI37192473886은5jobs 모두 성공했다. 다운로드한 원시27개 전체
검사는 `20261004T103252Z-838f3a6f` PASS/0: 단위122·PG230·Runner111·MQTT95,
새 Remote inventory10/TLS9·S3 차단/소진25·STREAM24개/Node43·VD33Pods/S354를 포함한다.
이 선행 CI에는 ADR0075–0077의 새 코드가 포함되지 않는다.
GitOps `354c94e`의 실제 API/dashboard/MinIO imageID·Ready·PVC Bound·Argo Synced는
`20261004T103223Z-dd541b3a` PASS다. 새 MinIO 기동 뒤 기존10개 고정 version의 bytes/SHA,
두 PVC UID, TLS256KiB PUT/stat/GET·익명403·소유 probe 정리도
`20261004T103449Z-2a468828` PASS다. 기존 공유 Ingress의 aggregate health는 Progressing이다.
