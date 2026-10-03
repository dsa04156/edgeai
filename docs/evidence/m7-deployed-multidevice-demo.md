# M7 배포 TLS 전환과 다중 장치 데모

2026-10-04 KST. [ADR0050](../adr/0050-deployed-multidevice-demo.md).

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 렌더링 MinIO TLS·임시 data·S3 왕복256KiB·익명403 | 20261003T152357Z-0ec0de3d | PASS/0 |
| base에서 독립 렌더링한 같은 MinIO 컴포넌트 회귀 | 20261003T153600Z-037f0410 | PASS/0, dev 활성화 후 중복 component 추가를 방지 |
| 공용 driver 기본5개 실제 Kubernetes 회귀 | 20261003T153007Z-d4d6355e | Pod17개/S3파일12개·그룹/최종 처리 복구·API 교체10.913초·정리 PASS/0 |
| TLS 전환 전 기존 저장소 기준·HTTP probe | 20261003T153453Z-497dd3e1 | 기존 고정 파일10개 bytes/SHA256, 두PVC UID·메타데이터 기록, 기존 HTTP probe PASS |
| HTTPS533d850 실제 이미지 배포 | 20261003T153845Z-a6edca31 | 정확한3imageID/Ready/PVCBound/ArgoSynced, GitOps0472650 |
| dev TLS 컴포넌트 GitOps 반영 | 20261003T154220Z-538f1656 | source533d850 이미지 유지, commit8239265 Synced·API/MinIO/broker Ready |
| TLS 전환 후 기존 저장소 보존 | 20261003T154316Z-439de77f | 기존10개 고정파일·bytes/SHA256·메타데이터·두PVC UID 보존 |
| 실제 배포 다중 장치 데모 | 20261003T154323Z-e1620e87 | AUTO/NODE/cancel3개, Runner Pod8개·checkpoint 장벽5개·S3 결과6개 PASS/0 |
| 데모 후 기존 저장소 재확인 | 20261003T154637Z-83f2c304 | 기존10개 및 두PVC 보존, 인증 S3 왕복·익명403 PASS/0 |

배포 직전 read-only 조회에서 workflow Run·Runtime·VD의 활성 상태가 모두0이었다. API/MinIO/broker
TLS 활성화 뒤에도 기존 PostgreSQL·MinIO PVC UID와 파일10개를 보존했고 공개 API/DeviceRunSource
SDK의 실제 배포 데모도 통과했다. S3 JSON은 root14/sink23/BATCH37이며 실제8개 Pod의 고정이미지·
producer UID·NODE 노드UID를 대조했다. 실제 DB Run 상태는 AUTO/NODE=SUCCEEDED, cancel=CANCELLED다.
데모의 Pod/ConfigMap과 세 Run의 실행 리소스가 모두 사라진 것을 Kubernetes에서 다시 확인했다.
데모 이력과 결과 파일은 보존한다. 원시 결과는 해당 실행 폴더의 `demo.json`이다.

533d850 CI37131722732는5jobs·원시JSON17개 모두PASS/0이다. 실제 포장 API의 추가 HTTPS를
사용한 kind5개/17Pods/12S3·API 교체11.171초, PG190개 오류/실패/skip0, Runner111/MQTT87을
직접 확인했다. 153845Z-a6edca31 폴더에 CI의 `kind-stream.json`을 함께 보존했다.
aggregate Argo health는 공유 Ingress의 상태 때문에Progressing이며 실제 서비스 Ready/Synced와 구분한다.

영속 기반269c548과 컴포넌트/데모82392650d2be147e6e4df03cdae2a740c6bc3ed6은 main에 푸시했다.
추가한 신원·영속 broker·MinIO TLS·배포 데모의 전체 kind 게이트는 CI37134164748에서 진행 중이다.
실제 로컬 클러스터 검증을 새 CI 성공으로 대체하지 않는다. M5 잔여/M7–M10 전체 수용은 미완료다.

운영 인증서·파생 키·기존 데이터는 새 fixture 데이터로 바꾸지 않는다. 새 MinIO 기동 시험만
임시 emptyDir/자격을 사용했으며 제품의 기존 PostgreSQL/MinIO PVC를 수정·교체하지 않았다.
