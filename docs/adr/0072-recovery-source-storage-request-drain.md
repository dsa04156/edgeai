# ADR0072 — root 차단 후 원본 S3 요청 소진 확인

상태: 채택, 2026-10-04. ADR0071의 자격 차단은 이전에 인증을 통과한 요청을 종료하지 않는다.
실제 TLS PUT 두 개를 `100 Continue` 직후 body 전송 없이 유지한 결과, root 차단 뒤에도
한 요청은 나머지 body를 보내 파일 저장을 완료했다. 따라서 root 차단 증거와 요청 소진 증거를
분리하고, 종합 복구 절차는 양쪽을 요구해야 한다.

## 관측 대상

`verify-recovery-storage-drain.sh`는 ADR0071의 비공개 state·confirmed 기록과 원래 비밀번호
fingerprint를 대조한다. 저장된 endpoint/인증서/deployment/복구 UUID로 같은 MinIO의 새 복구
사용자와 marker 정책을 확인한다. 별도 사용자/group/service account·익명 정책은 기존 인벤토리
규칙으로 거절한다. 명령은 서버 설정·파일·정책을 변경하거나 프로세스를 재시작하지 않는다.

현재 배포와 같은 **단일 MinIO 서버**만 지원한다. admin info의 online 서버가 정확히 하나여야
하며 metrics의 server label도 하나이고 관측 중 바뀌지 않아야 한다. 여러 서버 중 하나의
응답을 전체 설치의 종료로 해석하지 않는다. 여러 주소를 임의로 전환하는 외부 프록시/로드밸런서는
이 직접 endpoint 검증의 수용 범위가 아니다.

## 종료 판단

고정 MinIO의 `s3APIMiddleware`는 `collectAPIStats`로 S3 handler를 감싼다. 진행 수는 handler
실행 전 증가하고 종료 후 감소하며 PUT 권한 확인과 body 저장은 이 범위 안에서 수행된다.
v3 `/api/requests`의 HTTP loader는 해당 상태를 직접 읽는다. 값0의 family/sample은
`MetricValues.Set`에서 생략되므로 응답 일부가 없다는 사실만으로 종료를 판단할 수 없다.

1. 기존 root로 실제 GET `/`가 인증 거절되는지 확인한다.
2. `mc admin prometheus metrics ... api --api-version v3`에서 완료 누적수, 진행 수, 대기 수를
   구조·타입·server label·중복·유한한 정수 범위까지 검증한다.
3. 같은 root 거절 요청을 다시 완료하고 `ListBuckets` 완료 누적수가 증가한 새 표본을 요구한다.
   새 표본에서 전체 S3 진행 수와 대기 수가 모두0인 관측이 연속2회여야 한다.
4. deployment/서버 신원·복구 정책·원래 root 거절을 마지막으로 대조한다.

이름이 `incoming_total`인 값은 수집 때 `atomic.SwapUint64(..., 0)`으로 초기화되는 gauge다.
신선도 검증에는 이 값을 사용하지 않고 누적 `minio_api_requests_total{name="ListBuckets"}`를
사용한다. 갱신되지 않은0, 누락된 완료 counter, 잘못된 수치·server 혼합, HTTP/TLS/조회 실패는
성공 근거가 아니다. 요청이 남거나 deadline이 끝나면 BLOCKED를 반환하고 root 차단을 유지한다.
마감 직전의 subprocess timeout도 동일한 확인 불가로 처리한다.

## 결과와 범위

성공은 `SOURCE_STORAGE_REQUESTS_DRAINED`, `inFlightRequestsDrained=true`다.
root 차단 도구의 원래 보고서는 계속false이며 이 별도 검증 결과로 대체 해석하지 않는다.
원래 요청이 성공했는지 실패했는지는 별도 파일/버전 검사로 판단한다. 요청을 강제로 끊거나
관측 전 발생한 쓰기를 되돌리지 않는다. 실제 시험은 늦게 완료된 파일1개와 연결 종료로 중단된
요청1개를 구분하고 과거2버전의 ID/내용을 그대로 유지한다.

MinIO 내부 복제·lifecycle·외부 IdP/STS·관리자의 동시 권한 변경, 외부 producer 및 장치 journal,
복원 DB 상태와 참조의 일치·운영 활성화는 별도다. `globalQuiescenceProven=false`,
`activated=false`를 유지한다. 외부에서 권한/설정을 변경한 뒤 과거 보고서를 재사용하면 안 된다.

[운영 명령](../operations/recovery/recovery-storage-drain.md), [실제 검증](../evidence/m9-recovery-storage-drain.md).
