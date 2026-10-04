# 백업 변경 CI 실패와 배포 데모 재확인

2026-10-04. 소스 `6617d939547fed0cf32519b72a1192179f66a4df`의 GitHub Actions
`37168798128`은 `storage`, `runner`, `scaffold` 성공, `images` 실패,
`gitops` skipped다. 다운로드한 원시 `result.json`20개 중19개 PASS/1개(kind) FAIL이다.
이 CI를 통과하거나 배포했다고 판정하지 않는다.

실패 전 실제 STREAM24개 시나리오와 고정 S3 결과54개, broker 영속/TLS 및 MinIO TLS
시험은 통과했다. 마지막 `demo-multidevice.py`의 `node-done` 이후 드라이버의
`failure.json`을 읽은 지점에서 AssertionError가 발생했다. 예외의 상세 메시지는 공개 로그에
내보내지 않았고, 별도 무작위 이름의 실패 보고서는 CI artifact 대상에서 빠졌다. 따라서
드라이버 내부 예외 유형/위치와 근본 원인은 이 실행의 보존 자료만으로 확정할 수 없다.

후속은 드라이버의 예외 **유형·코드 위치·phase·Run UUID만** 검증해 보고서에 넣고, CI가 이미
수집하는 `--report` 경로에도 실패 보고서를 저장한다. 응답 본문·예외 메시지·인증 정보는
포함하지 않는다. canary를 넣은 결정적 projection 점검에서 추가 메시지/헤더·URL/절대경로·
잘못된 식별자가 출력되지 않는 것을 확인했다. 이것은 진단 보완이며 실행 장애의 해결 선언이 아니다.

같은 AUTO/NODE/cancel 데모를 기존 Kubernetes 배포에서 다시 실행한
`20261004T023612Z-ad91d440`은 PASS다. 실제 Runner Pod8개, 결과6개의 고정 S3
version/bytes/SHA와 계산값, 전용 Pod/ConfigMap·실행 자원 정리를 확인했다.
기존 배포의 소스는035eb0e이며 백업/격리의 새 바이너리 배포 검증은 아니다.
CI의 재현 환경/부하가 같지 않으므로 간헐 실패의 원인이 해소됐다는 증거로 쓰지 않는다.

새 CI에서 보완한 실패 경계와 전체 수용 게이트를 다시 확인한다. 기존 M9 백업·참조 대조·
복원 DB 격리의 로컬 근거, ADR0063 조회 점검 근거는 각각의 검증 문서를 따른다.

## 후속 CI와 배포

소스 `aa21e46eb92c000260c11501428d22161d350afe`의 CI `37171839136`은5개 job 모두
성공했다. 완료 artifact의 원시 결과21개가 PASS/exit0이며 상세 감사 근거는
`20261004T033736Z-c4b9c68f`다. 전체 단위113·PostgreSQL226·Runner111·TLS MQTT90,
Compose PostgreSQL17 백업/복원13개·TLS S3 백업11개·복원 DB/S3 참조9개를 대조했다.
kind의 STREAM24개/Node43Pod·VD33Pod/고정 결과54개, 혼합 Remote3개, 영속 broker 교체,
MinIO TLS 및 이전 실패 구간인 배포 데모3개/8Pod/결과6개와 소유 자원 정리도 확인했다.

GitOps `fd86520092c1424374ae2d134447ca2cd85be671`을 실제 클러스터에서 확인한
`20261004T033749Z-e4f8ce6c`은 API/dashboard/MinIO의 정확한 source imageID,
Ready·PVC Bound·Argo Synced를 검증했다. 공유 Ingress의 aggregate health는
여전히 Progressing이다. 이 성공은 aa21e46의 검증이며, 앞선 간헐 실패의 근본 원인을
입증하거나 후속 ADR0064/0065의 새 CI 게이트를 검증한 것으로 확대하지 않는다.

배포 뒤 `20261004T033850Z-a2a4c713`에서 기존 고정 버전 파일10개의 bytes/SHA와
PostgreSQL/MinIO PVC2개의 원래 UID를 확인했다. 실제 TLS S3 PUT/stat/GET256KiB,
익명403과 시험 소유 객체/버킷만의 정리도 통과했다.
