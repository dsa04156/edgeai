# ADR0071 — 원본 MinIO root 자격의 복구 차단

상태: 채택, 2026-10-04. 현재 API의 S3 자격과 presigned URL은 MinIO root 자격을 사용한다.
ADR0069의 DB 연결 차단과 ADR0070의 MQTT 권한 회수에 원본 저장소의 root 접근 차단을 더한다.
서비스 활성화와 모든 writer의 종료는 별도 복구 단계다.

## 대상과 소유권

명시적 HTTPS origin, 신뢰 CA, leaf 인증서 SHA256, MinIO deployment UUID, 원래 root 이름과
0600 비밀번호 파일, 복구 UUID를 받는다. 일반 TLS/hostname 검증과 각 관리 명령 전 인증서
pin 조회를 사용한다. 실제 S3 거절 검사는 같은 TLS 연결에서 pin을 확인한 후 서명 요청을 보낸다.
mc 자체의 TLS 연결은 CA/hostname으로 검증한다. 여러 주소의 로드밸런서나 분산 설치 전체의
동시 차단은 이 단일 endpoint 시험으로 증명하지 않는다.

임의 IAM 사용자를 변경하지 않는다. 원래 root와 이번 복구 사용자만 있는 전용 설치를 대상으로
하고, 다른 IAM 사용자·group·service account, site replication, 익명 bucket 정책은 거절한다.
복구 사용자의 비어 있거나 정확히 지정된 정책만 허용한다. bucket 수는 최대1,000개다.
외부 IdP/STS, 진행 중 업로드, 복제·lifecycle 등 내부 writer의 전체 인벤토리를 증명하지 않는다.

## 순서와 중단 처리

1. 대상 신원·원래 비밀번호 fingerprint·복구 사용자 이름과 임의 새 비밀번호를 소유자 전용
   `recovery.json`에 저장하고 file/directory를 fsync한다. 같은 상태 폴더는 flock으로 직렬화한다.
2. 원래 root로 deployment와 인벤토리를 확인한다. 고정 IAM 정책
   `edgeai-recovery-root-fence-v1`의 Sid에 복구 UUID와 deployment UUID를 기록한다.
   다른 marker 정책은 덮어쓰지 않는다.
3. 해당 복구 사용자만 생성하고 marker 정책을 붙인다. 비밀번호는 mc의 stdin으로 전달한다.
   정책은 `admin:*`와 S3 Get/List다. 관리 권한으로 정책을 변경할 수 있으므로 새 자격은
   고권한 복구 자격이며 일반 API에 전달하지 않는다.
4. 새 사용자로 deployment·정책·인벤토리를 다시 검증한 후 `api root_access=off`를 저장한다.
   저장 응답만 믿지 않고 원래 root로 실제 서명 S3 요청의403/`InvalidAccessKeyId`를 확인한다.
   `MINIO_API_ROOT_ACCESS=on` 환경변수가 설정을 덮으면 성공할 수 없다.
5. 새 사용자로 조회·정책과 원래 root 거절을 마지막으로 재확인한다. `confirmed.json`을 fsync한
   뒤 `SOURCE_STORAGE_ROOT_DISABLED`를 반환한다.

사용자 생성 후, 정책 연결 후, 설정 적용 후 응답 유실에도 같은 UUID와 비공개 상태로 재개한다.
이미 확인된 차단이 외부에서 해제되면 재개를 거절하고 그 변경을 숨기지 않는다. 다른 복구 UUID나
새 상태 폴더는 기존 차단을 인수할 수 없다. 실패해도 사용자/정책을 지우거나 root를 재활성화하지 않는다.
서로 다른 폴더·외부 관리자의 동시 변경은 직렬화하지 않으므로 복구 관리자는 단일 소유자로 실행한다.

## 근거와 남은 경계

고정 mc의 `admin info`는 JSON `status:error`에도 종료 코드0을 반환한다. 명령의 종료 코드와
JSON 상태를 함께 검사하고 인증 거절은 S3 프로토콜로 확인한다. MinIO는 IAM Resource `*`를
다르게 직렬화하므로 S3 ARN을 명시하며 실제 보관 정책과 대조한다.

실제 로컬 TLS MinIO18개 시험에서 기존 PUT/GET URL 거절·고정2버전/bytes 보존·중단/재개·
SIGKILL 재시작·환경변수 우선순위를 확인했다. 현재 배포 MinIO를 이 시험으로 차단하지 않았다.
CI는 빌드된 MinIO binary로 동일 게이트를 실행한다. 새 게이트의 원격 통과는 별도 확인한다.

인증을 통과한 진행 중 업로드나 이미 시작한 내부 작업이 종료됐다는 증거는 아니다.
`inFlightRequestsDrained=false`, `globalQuiescenceProven=false`, `activated=false`를 유지한다.
파일·버전·IAM 이력을 삭제하지 않으며 운영 Secret/StatefulSet도 수정하지 않는다. Remote와
장치 producer·journal, 전체 쓰기 종료 확인, 복원 DB/파일/키 대조 및 재활성화는 계속 남는다.

[운영 명령](../operations/recovery/recovery-storage-fence.md), [실제 검증](../evidence/m9-recovery-storage-fence.md).
