# Kubernetes 결과 복구까지의 CI·실제 배포 검증

소스 `077d1398d5cbadf2bba9b0d8af9c8df2067b0c5a`의
[GitHub Actions37239863156](https://github.com/dsa04156/edgeai/actions/runs/37239863156)은
scaffold/storage/native amd64/native arm64/runner/images/gitops7개 job 모두 성공했다.
ADR0103의 Kubernetes 결과 기록과 ADR0104의 복원을 포함한다.
이후 VD 시작/결과 기록 ADR0105–0106의 CI 증거는 아니다.

최종 원시 감사 `20261004T234433Z-9addfe67`은 아래를 직접 확인했다.

- 원시 결과40개 모두 PASS/exit0. 단위122개·실제 PG232개·runtime/storage 연동62개.
- native amd64/arm64 각각 Runner111개·TLS MQTT97개 및 게시된 index/platform 신원.
- 실제 kind의 일반 실행22개·출력20개, VD5개·출력8개, 혼합 Remote3개·출력5개.
- STREAM24개·Node Pod43개·VD Pod33개·고정 출력54개, Kubernetes 결과 기록27개·발행 대기0.
- DB 백업13개/44테이블, 저장소 백업11개·참조9개·원래 producer/권한 종료와 Device/STREAM 복구.
- 실제 API 시작 기록을 포함한 Kubernetes 복구91개, 확정 결과 복원20개,
  STREAM 복구44개, Remote 결과15개/실패15개 및 각 소유 자원 정리.
- 배포 이미지의 공개 다중 장치 데모3개·Pod8개·고정 출력6개와 fixture 정리.
- API JAR SHA256 `6d8e60f54575ae6cc91efd1eeb0c6a9fc9d125bd1a6fd91d8421aee8cd2fc175`.

처음 전체 artifact 다운로드는 부가 Docker build record ZIP 해석에 실패했다.
검증에 필요한6개 artifact를 명시해 모두 내려받았다. 첫 감사 `20261004T234314Z-197c02d8`은
로컬 도구의 workflow 보존 테이블 기대값39 때문에 실패했다. 실제 V35의44테이블 중
변경 허용4개를 뺀40개와 원시 시험의 전체 행 fingerprint 검사를 확인해 기대값을 수정했다.
제품/CI 시험 결과를 변경하지 않았으며 수정한 감사가 위에서 통과했다.

GitOps `c6d12ce2241caacf4476e986021f8389c738c3a1`의 실제 배포 검사
`20261004T234236Z-b594c163`은 API/dashboard/MinIO의 정확한 imageID·Ready,
원래 저장소 PVC Bound·VD 실행 활성화·Argo Synced와 해당 Git revision을 확인했다.
공유 Ingress가 포함된 Argo aggregate health는 Progressing이며 Healthy로 보고하지 않는다.

기존 baseline을 다시 만들지 않고 수행한 `20261004T234236Z-e6ab6964`는 원래10개 출력의
고정 version/bytes/SHA, 원래 PostgreSQL/MinIO PVC UID를 대조했다. 신뢰 TLS의256KiB
업로드/다운로드·익명403 및 probe가 만든 객체/버킷만 정리한 것도 확인했다.

현재 소스의 성공 근거다. 이전 CI 시간 초과나 로컬 간헐 실패의 모든 원인이 해결됐다는
주장은 하지 않는다. M5 잔여·M7–M10 전체 수용, 전역 writer/API 차단과 종합 활성화는 남는다.
