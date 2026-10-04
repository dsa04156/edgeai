# M9 원본 S3 root 자격 차단 검증

2026-10-04, ADR0071. `20261004T082045Z-c2caa5f1`의 실제 격리 TLS MinIO18개가 PASS다.
합성 파일2버전, 자체 CA, 고정 SHA mc, 독립 MinIO 프로세스를 사용했다.

| 검증 | 실제 확인 |
|---|---|
| 기존 접근 | 원래 root 및 차단 전 발급한 PUT/GET URL 정상 동작 |
| 대상 거절2개 | 다른 leaf pin·deployment UUID에서 root 접근과 원본 버전 보존 |
| 기존 설정 보존4개 | 외부 IAM 사용자·빈 group·root service account·익명 bucket 정책을 변경하지 않고 거절 |
| 다른 복구 | 다른 UUID의 marker 정책을 덮어쓰지 않음 |
| 사용자 생성 뒤 중단 | 실제 IAM 사용자를 생성한 직후 응답 유실을 주입, root 유지·0600 상태 보존 |
| 차단 뒤 중단 | 같은 상태로 정책 연결 후 실제 root 차단, 응답 유실에도 원복하지 않음 |
| 재개 | 같은 UUID로 재개해 root 거절·복구 관리자 조회와 원래2버전 ID/bytes/SHA 확인 |
| 기존 URL | 차단 전 발급한 PUT/GET 각각 실제403/InvalidAccessKeyId |
| mc 응답 | 실제 admin info의 exit0/status:error를 명령 성공으로 취급하지 않음 |
| 소유권 | 다른 UUID가 기존 state 또는 새 state로 차단된 설치를 인수하지 못함 |
| 강제 재시작 | SIGKILL 후 같은 deployment·정책·차단·고정 버전 보존 |
| 외부 재개 | 확인된 차단을 fixture가 해제한 뒤 도구는 상태 변화를 숨기지 않고 거절 |
| 환경 우선순위 | 실제 MINIO_API_ROOT_ACCESS=on에서 config 저장만으로 성공하지 않음 |
| 환경 제거 후 | 같은 state로 재확인, private 파일0600과 원래 모든 버전 유지 |

최종 보고서의 `versionCount=2`, `oldPresignedPutRejected`, `oldPresignedGetRejected`,
`restartPreserved`, `versionsPreserved`, `ownedProcessesStopped`를 확인했다. 생성한 MinIO
프로세스는 모두 종료했다. 원문 mc 로그·새 비밀번호·CA 개인 키·파일은 `.tools`에만 보관한다.
요약은 해당 evidence 폴더의 `recovery-storage-fence-report.json`으로 복사했다.

첫 구현 `081614Z-54560e01`은 MinIO의 IAM Resource 정규화가 예상과 달라 재개 대조에서
실패했다. 명시적인 S3 ARN으로 수정한 `081711Z-4f904fd5`는 실제 mc 오류의 error 필드가
객체가 아닌 문자열인 경우를 발견했다. 처리 후 `081838Z-3a1c2e9b`의16개가 PASS다.
group/service account를 추가한 `081946Z-1680fa6e`는 fixture 명령의 필수 ACCOUNT 누락으로
실패했다. 실제 CLI 계약대로 수정한 위18개가 최종 결과다. 실패에서도 소유 프로세스는 정리했다.

CI storage job에 빌드된 MinIO binary를 사용하는 동일18개 게이트를 추가했다.
이 신규 게이트의 CI/배포 확인은 후속이며 공유 저장소의 root를 차단한 것은 아니다.
원래 API JAR SHA256은3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7로
기존 검증본과 동일하다. REST API/DB migration/UI 변경은 없다.

진행 중 업로드의 완전 종료·multipart commit 경쟁·외부 IdP/STS·분산 노드 전체·전원/디스크 유실과
여러 운영자의 동시 변경은 미검증이다. root 인증 경계의 확인이며 M9 전체 복원 활성화나
RPO/RTO 수용은 아니다.
