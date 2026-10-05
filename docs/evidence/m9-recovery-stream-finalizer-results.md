# 상속된 최종 저장 결과의 복원 검증

2026-10-05. [ADR0109](../adr/0109-restored-stream-results.md)의 상속 finalizer 규칙을
실제 원래 API 기록에서 독립 백업·복원 CLI까지 연결하는 시험이다.

## 검증 경로

공개 API로 STREAM 부모와 BATCH 자식, 최초 실행과 재시도 정책을 만든다. 실제 TLS
Runner claim/Pod-bound TokenReview와 완료 API로 원래 checkpoint의 완료 허가를 받는다.
첫 두 실행은 실제 `/fail`에 `STORAGE_FAILED`를 보고한다. 각 Pod의 부모 프로세스가
실제 자식을 종료하고 `CHILD_REAPED`를 남긴 것을 확인한 뒤 DB의 종료 이력을 반영한다.
원래 MQTT principal은 실제 broker에서 차단하고 경로는 닫는다.

원래 API가 만든 재시도 대기·실패 사유·backoff·기한을 확인하고, DB 상속 제약을 켠 채
두 후속 Attempt와 runtime을 명시적인 fixture로 만든다. VD 경로는 같은 VD의 새
supervisor 세대와 별도 세션/배정을 사용한다. 후속 두 실행의 실제 claim과
`streams/execution`은 모두 FINALIZE를 반환하며, `streams/checkpoints/finalized`로
처음 checkpoint의 고정 bytes/SHA를 내려받는다. 세 번째 실행만 결과를 확정한다.

원본 DB와 원본 S3 데이터를 제거하고 별도 백업에서 DB6개를 복원한다. 세 Pod의 실제
종료와 원래 broker 차단을 확인한 뒤 결과 복원 CLI를 실행한다. 결과는 세 번째 Attempt에
속하지만 완료 허가와 checkpoint는 첫 번째 Attempt에 속한다. 원래 결과 ID/시각,
두 실패 Attempt와 producer 이력, 한 개의 grant/checkpoint/generation을 보존해야 한다.
재실행·경쟁·잠금·후속 실패 원복·실제 COMMIT 응답 유실과 commit 후 S3 교체도 검사한다.

상속 누락·중간 연결 누락·다른 grant·건너뛴 predecessor·순환·다른 Task·성공한 predecessor·
OFFLOAD/REMOTE·epoch/번호·시각 모순12종은 관측값 주입으로 검사한다. 실제 복원 DB의
재시도 횟수·backoff·최대 경과 시간·허용 실패 정책을 변경하는4종은 전체 CLI로 거절을
확인한다. 원래 DB 값과 모든 테이블 지문을 매번 복원한다.

재시도 배정/DB 종료 반영, Device END·route 활성화·checkpoint DB metadata와 VD
readiness/allocation은 명시적 fixture다. 실제 모델 계산이나 자동 스케줄러부터의 종단
수용을 뜻하지 않는다. 실제 API/Pod/TLS S3/MQTT 경계와 fixture 범위를 구분한다.

## 실행 결과

VD39개 `20261005T021504Z-64750523`는 PASS/exit0이며 소유 namespace/DB/API/S3/MQTT
정리를 확인했다. 원시 보고서는 `.tools/stream-result-finalizer-vd-diagnostic.json`이다.
Kubernetes35개 `20261005T021900Z-a9a73602`도 PASS/exit0와 같은 소유 정리다.
원시 보고서는 `.tools/stream-result-finalizer-kubernetes-final.json`이다. 두 경로 모두
별도 백업에서 원래 시작 기록3개·결과 기록1개와 실제 종료 Pod3개를 확인했다.
기존 경로도 같은 소스에서 회귀 PASS/소유 정리다:

- 직접 STREAM Kubernetes30개 `20261005T022250Z-4e059ee9`
- 직접 STREAM VD34개 `20261005T022727Z-eaaf5554`
- BATCH Kubernetes20개 `20261005T023045Z-ec3822e2`
- BATCH VD24개 `20261005T023229Z-3280b129`

최종 감사 `20261005T023758Z-5ef4cd70`는 전체182개·원래 시작/결과 version 수·실제 종료
Pod 수·소유 정리와 검증 전후 소스 해시를 대조했다. `.tools/finalizer-result-final-audit.json`에
보고서/소스 SHA를 남겼다. Java와 migration은 변경하지 않았고 JAR SHA는
`ac681d4ca1bcf588f0fbd3b8a4fb17876097567c0ff1557def6f88029cd9a453`, V36 SHA는
`ed078e8517e85c403dac9df8a8cd5ea777cb3497cf0bf408495e6c44bdb6fb2e`다.
새 두 경로를 kind CI와 별도 JSON artifact에 연결했다. 새 코드의 원격 검증은 후속이다.

초기 실패는 다음과 같이 보존한다.

- `20261005T020659Z-0023cbb4`: 10개 후 시험 주입 코드가 일반 스키마 조회에도
  `stream` 필드가 있다고 가정해 KeyError였다. STREAM 카탈로그만 변경하도록 수정했다.
- `20261005T021113Z-9224e04e`: VD 후속 runtime fixture에 Job 이름을 넣어
  `runtime_producer_kind` 제약에 거절됐다. VD의 Job 이름을 NULL로 유지하도록 수정했다.
- `20261005T021232Z-8086aef3`: 재시도 생성 전 최초 실제 claim이 HTTP503이었다.
  당시 오류 본문·경계 관측이 없어 원인은 미확정이다. 후속 시험에서 예상 밖 응답의
  status·허용된 오류 코드·응답 길이와 안전한 Pod 상태를 정리 전에 기록한다.
  토큰·응답 전문·임의 서버 메시지는 기록하지 않으며 자동 재시도로 실패를 숨기지 않는다.

위 실패3개의 보고서에서 소유 namespace/DB/API/S3/MQTT 정리를 확인했다.
`20261005T022032Z-05dafb5e` 감사도 원본 DB와 namespace의 실제 부재를 재확인했다.
`.tools/finalizer-result-failures-audit.json`에 범위를 남긴다. 재실행 통과가 최초503
원인 해결을 의미하지는 않는다.

## 남은 범위

혼합 다중 member 완료 그룹의 전체 CLI 수용, 백업에 없는 완료 허가·알려지지 않은 실행,
전역 writer/API 차단과 종합 재활성화, 실제 모델·외부 계약 및 M0–M10 전체 완료는 남는다.
새 원격 CI·배포는 로컬 검증과 별도로 확인한다.
