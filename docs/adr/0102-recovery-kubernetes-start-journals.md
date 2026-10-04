# ADR 0102: 원래 Kubernetes 시작 기록으로 복원된 BATCH 전환을 조정한다

상태: 채택, 최초 결합90개·실제 API 확장91개·기존 Remote 결과15개/실패15개 PASS. 2026-10-05.

ADR0101의 기록은 최초 실행 허가를 증명한다. 백업 DB에는 아직 claim과 전환 성공이
없을 수 있으므로, 복원된 STARTING 상태나 현재의 기한 만료만으로 시작 실패를 단정할 수 없다.
별도 MinIO에 버전을 보존한 백업, 원래 Job과 실제 보존 Pod의 종료 증거를 함께 대조한다.

`recovery-kubernetes-workflows.sh`에 `--runtime-start-backup`, `--runtime-start-bucket`,
`--runtime-start-certificate-sha256`을 함께 지정한다. 기존 복원 DB 신원·전체 행 guard·
실제 quota/Pod 종료 증거는 유지한다. S3 접속은 기존 `EDGEAI_BACKUP_STORAGE_*`와 CA 설정을
사용하며 TLS leaf pin·별도 deployment UUID·버전 관리 상태를 확인한다.

각 runtime의 고정 key에 백업 버전이 정확히 하나여야 한다. 현재 HEAD와 캡처된 version,
GET의 media type·길이·SHA를 대조하고 정확한17개 필드·중복 JSON 거절을 적용한다.
원래 Run/Task/Attempt/epoch·Job UID·실제 Pod/controller·노드 UID/이름·고정 배치를 확인한다.
복원 claim이 있으면 일치해야 하며, 없다면 관측만으로 claim을 채우지 않는다.

작업 digest는 복원 DB의 불변 SERVICE spec, Task와 Run의 병합 파라미터, 모든 BATCH 입력의
고정 version/bytes/SHA/media type으로 재계산한다. JSON 수는 Decimal로 보존하고 Java의
UTF-16 키 정렬·숫자 정규화·문자 escaping·깊이 제한을 따른다. 패키징된 Java JsonDocuments와
별도 비교 시험으로 정밀도와 유니코드 차이를 검증한다.

허가 시각은 runtime/Attempt 및 전환 생성 이후이고 원래 lease와 시작 기한 이전이어야 한다.
STARTING 갱신 이후인지, 실제 Runner 컨테이너 수명 안인지도 확인한다. UTC 나노초를 보존하고
Kubernetes 초 단위 종료 시각은 해당1초 구간으로 비교한다. 관측 끝에 모든 HEAD와 실제 종료
증거를 다시 읽고, DB 반영 직전/직후에도 같은 증거와 전체 행 guard를 재검증한다.

`--offloads`에서 최신 BATCH target의 유효한 기록과 실제 종료가 증명되면 전환만 SUCCEEDED로
조정한다. source OFFLOADED, 원래 배치·기한·Attempt·claim·Result를 보존한다. Task/Run의
취소·실패·재시도·최신 시도 충돌은 성공으로 덮지 않는다. `offloadsCompleted`가 반영 수다.
최초 허가 기록이 증명하는 것은 실행 허가이며 HTTP 응답 도달·업무 성공·Result는 아니다.
이에 따라 작업 결과는 미해결로 남고 격리 및 `activated=false`를 유지한다.

백업에 기록이 없고 실제 HEAD404여도 과거 실행 부재의 증거는 아니다. 새 옵션을 사용하는
STARTING target은 `KUBERNETES_START_AUTHORITY_NOT_PROVEN`으로 남기고 시작 만료를 만들지
않는다. 캡처 기록이 삭제됐거나 교체된 경우는 거절한다. 옵션 없는 기존 기한 복구 동작은
유지되므로 ADR0101 배포 이후 복구에서는 시작 기록 백업을 함께 지정해야 한다.

DB/S3/클러스터/API 시계와 관리자 권한은 신뢰 경계다. 전역 원본 writer 차단·서비스 재활성화,
VD 독립 시작 기록, STREAM 그룹 및 Kubernetes Result 복구는 이 단계의 증거가 아니다.
최초90개는 실제 컨테이너·PG dump/restore·TLS S3 복제에 명시적 DB/시작 허가 fixture를
결합했다. 후속91개는 명시적 DB binding을 유지하고 실제 TLS API·Pod-bound TokenReview로
시작 기록을 생성해 복구 CLI에 연결했다. 시험 클라이언트의 claim이며 실제 Runner 업무
프로세스의 계산·Result·종합 복구는 별도다. ADR0101의 실제 정상 Runner claim 시험도 유지한다.
[검증 근거](../evidence/m9-recovery-kubernetes-start-journals.md)를 따른다. 새 CI/배포는 후속이다.
