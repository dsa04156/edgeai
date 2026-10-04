# M10 클러스터 등록 자원 관측

2026-10-05 KST. 현재 선택한 `kubernetes-admin@kubernetes` context에서 Node API를
읽기 전용으로 조회했다. private 원시 선별 결과는 `.tools/hardware-inventory-20261005.json`이다.
노드/클러스터 설정이나 workload는 변경하지 않았다.

| 항목 | 관측 |
|---|---|
| 등록 노드 | 10개, amd64/arm64 |
| Ready=True | 4개, amd64/arm64 |
| GPU 자원 등록 | 5개 노드, 그중 Ready 3개 |
| NPU 자원 등록 | 1개 노드, Ready |
| KubeEdge/edge 역할 label | 6개 노드, 모두 Ready=True 아님 |
| Ready 노드의 NoSchedule/NoExecute taint | 1개 |

GPU resource key는 `nvidia.com/gpu.shared`, `nvidia.com/gpu`, NPU는
`mobilint.com/npu`였다. 할당 가능 자원의 등록은 실제 장치 접근·드라이버·모델 실행 성공
증거가 아니다. KubeEdge label도 실제 edgecore/연결 정상 상태를 대신하지 않는다.
노드 압박·taint·기존 사용량·이미지 architecture와 소유 namespace 제한을 새로 확인한 뒤
실행 후보를 선택해야 한다. 공유 node taint/설정은 임의로 변경하지 않는다.

ARM/GPU/NPU 후보가 전혀 없다는 가정은 하지 않는다. 다음 수용은 준비된 후보에서
architecture/실제 장치 접근과 소유 workload의 실행·정리부터 검증하고, 실제 모델/외부 계약을
연결하는 것이다. Ready가 아닌6개 edge 노드의 상태 원인 및 복구는 별도 확인한다.
`test-hardware.sh`의 전체 M10 gate는 계속 BLOCKED/exit2이며 이 조회로 PASS로 바꾸지 않았다.
