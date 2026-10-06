# ADR0070 — 원본 브로커 관리 자격 회수와 실행 계정 차단

상태: 채택, 2026-10-04. ADR0069의 DB 연결 차단과 ADR0064의 Kubernetes 실행 중지에
원본 MQTT 권한 차단을 추가한다. 기존 API의 관리자 자격이 살아 있으면 장치 계정만
비활성화해도 다시 발급할 수 있으므로 관리 자격을 먼저 회수한다.

## 대상과 사전 검사

명시적 TLS endpoint·신뢰 CA·leaf 인증서 SHA256·broker 설정 digest·복구 UUID를 받는다.
TLS/hostname과 인증서 pin을 확인하고 `edgeai-admin`을 사용한다. 기본 ACL4개가 모두
deny이며 group이 없는 전용 브로커만 대상으로 한다. 최대10,000개 client를32개씩 페이지
조회하며 totalCount 변경·중복·누락은 거절한다.

관리자 외의 계정은 ADR0024의 Device/Task 이름·client ID·`edgeai-stream-v1` metadata와
동일 broker digest/파생 키 fingerprint 형식이어야 한다. 무관한 client/group·기본 allow
정책은 변경 전에 거절한다. 기존 세대 역할과 ACL은 보존한다.

## 차단 순서와 재개

1. 새 관리자 비밀번호와 대상 신원을 소유자 전용 `recovery.json`에 기록하고 file/directory를
   fsync한다. 운영 API의 Secret이나 원래 비밀번호 파일은 수정하지 않는다.
2. 빈 ACL의 고정 `edgeai-recovery-fence-v1` 역할에 복구 UUID/broker digest marker를 남긴다.
   다른 marker는 덮어쓰지 않는다. 동일 state directory는 로컬 file lock으로 직렬화한다.
3. `setClientPassword`로 관리 비밀번호를 교체한다. Mosquitto2.0.18의
   [구현](https://github.com/eclipse-mosquitto/mosquitto/blob/v2.0.18/plugins/dynamic-security/clients.c)은
   해당 사용자 연결을 모두 끊는다. 응답 유실만으로 성공을 판단하지 않고 보관한 새 비밀번호로
   재접속해 marker를 확인하고 이전 비밀번호의 실제 인증 거절을 검사한다.
4. 전체 계정을 다시 조회하고 Device/Task를 `disableClient`로 비활성화한다.
   [Dynamic Security 계약](https://github.com/eclipse-mosquitto/mosquitto/blob/v2.0.18/plugins/dynamic-security/README.md)에
   따라 기존 연결도 끊는다. 마지막 조회의 계정 집합·disabled 상태·marker·이전 관리자 자격
   거절을 모두 확인해야 성공한다. 새 연결·명령은 공통 deadline으로 제한한다.

성공은 `SOURCE_BROKER_CLIENTS_DISABLED`다. `activated=false`,
`globalQuiescenceProven=false`이며 브로커의 기존 접근 경계만 증명한다. 중간 실패·timeout에도
비밀번호/marker/계정 차단을 원복하지 않는다. 같은 복구 UUID와 state directory로 재개하며,
새 비밀번호를 먼저 시도하여 교체 응답 유실도 복구한다. 일반 보고서에는 비밀번호·관리 응답·
원본 예외 메시지를 넣지 않는다.

## 운영 연결과 남은 경계

원래 자격으로 실행 중인 API는 이후 관리 명령을 보낼 수 없다. 새 비밀번호는 **복구 도구의
비공개 상태**에만 남기며 아직 API Secret에 적용하지 않는다. 이 파일은 기존 암호화 파일
백업 절차의 관리 대상이다. 외부 관리자의 동시 변경·별도 인증 plugin은 직렬화하지 않는다.

역할 tombstone·client·journal·대기/retained 메시지를 삭제하지 않는다. 검증은 권한 파일의
프로세스 재시작 보존이며 전원/디스크 유실이나 모든 payload의 복원 검증은 아니다.
원본 DB/Kubernetes·Remote·장치·S3 writer 중지, 키/journal 대조와 실행 상태 조정,
새 자격을 사용하는 서비스 재개는 종합 복구 단계에 남는다. 임의 계정 재활성화나 초기
Dynamic Security 파일 덮어쓰기 절차는 제공하지 않는다.

[운영 명령](../operations/recovery/recovery-mqtt-fence.md), [실제 TLS 검증](../evidence/m9-recovery-mqtt-fence.md).
