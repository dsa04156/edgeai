# M7 배포 TLS 전환과 다중 장치 데모

2026-10-04 KST. [ADR0050](../adr/0050-deployed-multidevice-demo.md).

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 렌더링 MinIO TLS·임시 data·S3 왕복256KiB·익명403 | 20261003T152357Z-0ec0de3d | PASS/0 |
| base에서 독립 렌더링한 같은 MinIO 컴포넌트 회귀 | 20261003T153600Z-037f0410 | PASS/0, dev 활성화 후 중복 component 추가를 방지 |
| 공용 driver 기본5개 실제 Kubernetes 회귀 | 20261003T153007Z-d4d6355e | Pod17개/S3파일12개·그룹/최종 처리 복구·API 교체10.913초·정리 PASS/0 |
| TLS 전환 전 기존 저장소 기준·HTTP probe | 20261003T153453Z-497dd3e1 | 기존 고정 파일10개 bytes/SHA256, 두PVC UID·메타데이터 기록, 기존 HTTP probe PASS |
| HTTPS533d850 실제 이미지 배포 | 20261003T153845Z-a6edca31 | 정확한3imageID/Ready/PVCBound/ArgoSynced, GitOps0472650 |

배포 전 read-only 조회에서 workflow Run·Runtime·VD의 활성 상태가 모두0이었다. API/MinIO/broker
TLS 활성화 뒤 보존 검사와 실제 운영 데모는 실행 결과를 아래에 추가한다. 이 표만으로 운영
다중 장치 데모나 새 kind CI를 통과했다고 판정하지 않는다.

운영 인증서·파생 키·기존 데이터는 새 fixture 데이터로 바꾸지 않는다. 새 MinIO 기동 시험만
임시 emptyDir/자격을 사용했으며 제품의 기존 PostgreSQL/MinIO PVC를 수정·교체하지 않았다.
