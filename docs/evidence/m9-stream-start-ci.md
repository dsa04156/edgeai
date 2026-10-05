# STREAM 시작 복원까지의 CI와 실제 배포 감사

2026-10-05. 소스 `d764480c20e3cb87b20e4698bd08c930ec3af249`의
[CI37250820250](https://github.com/dsa04156/edgeai/actions/runs/37250820250)는7 jobs 모두
성공이다. 후속 STREAM 결과 복원/상속 finalizer 변경이 포함된 실행은 아니다.

`20261005T023533Z-6e61b5e8` 감사에서 내려받은 원시 결과41개가 모두 PASS/exit0였다.
앞서 완료한5 jobs의 감사와 같은 artifact를 재사용했다. 단위122개, PostgreSQL232개,
Remote Python24/Java13개, native amd64/arm64 각각 Runner111/MQTT97개다.
실제 registry의 두 platform manifest는 `20261005T014516Z-29ff78cd`에서 확인한 같은
index `sha256:851e46156fc9232723aa062daae9a2d4840af093fc9c8a7c8bcb646f9f5a33c2`다.

이미지 검증의 실제 Kubernetes 결과는 producer 종료7개, runtime/시작 기록 복구91개,
Kubernetes 결과20개, VD 결과24개, STREAM 그룹/시작 복구55개 모두 PASS와 소유 정리다.
55개에는 실제 API의 NODE/VD 원래 시작 허가2개·그룹 전환1개·새 claim/Result0개와
타43테이블 보존이 포함된다. STREAM 실행24개는 고정 S3 결과54개와 미발행 결과0개다.
VD5개/혼합 Remote3개도 소유 자원0개를 확인했고 배포 데모3개는 Pod8개·고정 결과6개를
확인했다. CI 로그에서 소유 kind `edgeai-ci-8c50f60a1eb6`의 삭제를 확인했다.
원시 보고서/로그 해시는 `.tools/ci-37250820250-full.json`에 기록한다.

GitOps `5ba6ccc1f233c9be003c5ed73c32e657a05f02af`의 실제 배포는
`20261005T023304Z-fc338e78`에서 API/dashboard/MinIO의 정확한 imageID·Ready,
원래 PVC Bound·Argo Synced·VD 실행 활성화를 확인했다. Argo aggregate health는
Progressing이며 전체 Healthy로 판정하지 않는다.

배포 뒤 `20261005T023400Z-ed3013b9`는 기존10파일의 고정 version/bytes/SHA와 원래
PostgreSQL/MinIO PVC UID를 확인했다. 실제 HTTPS256KiB PUT/stat/GET·익명403과
probe 소유 파일/버킷 정리도 PASS다. 기존 `.tools/stream-storage-before.json`은 보존했다.

`20261005T023830Z-e73ac5fc`는 배포 DB를 읽기 전용으로 조회해36개 migration의 성공과
V36 checksum `-1624352915`를 확인했다. 최초 `20261005T023708Z-2e09091a`는 로컬 개발
DB의 오래된 Task 기준을 배포 DB에 적용한 감사 범위 오류로 FAIL이었다. 별도 관측에서
로컬 기준9371개는 로컬 DB에 모두 정확히 남아 있고, 배포 DB95개와 공통 ID가0개임을
확인했다. `.tools/vd-upgrade-baseline-location.json`에 대조 결과를 보존한다.
기준 파일을 덮어쓰지 않았으며 이 최초 감사 실패를 배포 데이터 유실로 해석하지 않는다.

이 근거는 ADR0108까지의 선행 소스에 한정한다. 후속 STREAM 결과 복원과 상속 finalizer의
CI/배포, 혼합 다중 member 복구·누락 권한·전역 writer/API 차단·종합 재활성화 및 전체
M0–M10 목표의 완료는 별도로 검증한다.
