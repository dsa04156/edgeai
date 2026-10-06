# M6 VD 완료 감사

2026-10-03 KST. [M6 요구사항](../requirements/m6-requirements.md)과 ADR0013–0020의 STATELESS VD
범위에 대해 구현·검증 완료로 판정한다. 전체 플랫폼은 PARTIAL이며 M5 잔여·M7–M10은 남는다.

| 요구사항 | 구현·검증 근거 |
|---|---|
| Device/Node/Pod와 별도 영속 VD, 원본 변경·해제 이력 | [등록](m6-vd-registry.md): V13·Profile 호환성·revision·멱등성·DB 경합·공개5 API/UI |
| source/runtime binding 분리, 세대·Operation·Ready | [수명](m6-vd-lifecycle.md), [gateway](m6-vd-gateway.md), [poll](m6-vd-poll.md), [공개 실행](m6-vd-public-execution.md): V14–V15·실제 Pod 신원/관측·감독 프로세스 poll |
| VD 정책으로 활성 runtime의 실제 Task 실행·결과 | [작업 실행](m6-vd-task-execution.md): V16–V18·공유 VD Pod의 자식 Runner·Task HMAC/Pod-bound 신원·실제 S3 bytes/version 검증 |
| 배정 중복 방지·이전 생산자 차단·slot·취소·재시도 | 실제 PostgreSQL136개, Python/HTTP/MinIO/DB16개, 실제 kind·배포 데모의 개별 취소/실패/재시도 |
| 같은 vdId의 Ready→교체→drain·실제 종료·API 재시작 | 격리 Kubernetes 195417Z-ea2a6d9b 및 CI의 VD4조건·Task Run4개/S3결과5개·API 재시작·시험 자원0개 |
| Swagger·실제 화면 | 한국어 Swagger39·실제 API/DB 브라우저10개·배포된 실제 결과 PC/모바일 205319Z-ea6cf390 |
| 새 이미지 CI·GitOps·실제 배포 | CI37059110890 5jobs/결과JSON15개 PASS; c2862a7의 imageID/Ready/PVC/Argo revision/VD 활성화와 실제 VD 데모 PASS |

최종 실행 ID의 날짜 접두사는 모두20261002T다. 소스는2ec9462052c3e9fbdd043cf3c29a05f3a63668ed,
이미지 pin은6ee527d, 명시적 VD 활성화 배포는c2862a7이다.
[CI 전체 결과](https://github.com/dsa04156/edgeai/actions/runs/37059110890)와
[최종 배포·화면 실행 ID](m6-vd-task-execution.md#실제-gitops-배포와-결과-화면)를 대조했다.
문서 갱신은 이 검증의 런타임 입력을 변경하지 않는다.

## 범위와 남은 게이트

- 이 판정은 STATELESS 작업의 실행·교체다. checkpoint 기반 상태형 복원과 실제 외부
  Remote 계약 수용은 M5 잔여다. 처음부터 재시작을 상태 복원으로 간주하지 않는다.
- source binding은 관리 기능이다. 여러 물리 Device의 센서 payload·브로커·STREAM 전달과
  DataRoute/세대·backpressure·재연결은 M7이며 현재 공개 STREAM 실행은501이다.
- 합성 계산의 S3 결과 검증이며 실제 장비·모델·GPU/NPU·성능 기준 수용은 M10이다.
- 배포 Argo는 Synced지만 공유 Ingress 상태 때문에 aggregate health는 Progressing이다.
  운영 identity/RBAC·TLS·backup/restore·종합 장애 수용과 M8 부하는 별도 단계다.
- 로컬 VD 실행 기본값은 false이고 개발 Kubernetes overlay에서 명시적으로 활성화했다.
  적용된 Flyway V1–V18은 변경하지 않는다.
