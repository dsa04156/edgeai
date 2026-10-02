# kind — M4 실제 실행 수용시험

`bash scripts/test-kind.sh`는 Linux amd64 Docker 환경에서 이름이 임의 생성된 전용 클러스터를
만들고 시험 후 해당 클러스터만 삭제한다. 사용자 kubeconfig와 현재 context는 읽거나 변경하지 않는다.
Docker 권한이 없으면 exit2이며 자동으로 권한을 변경하지 않는다.

필수 환경 변수:

- `EDGEAI_API_IMAGE`, `EDGEAI_DASHBOARD_IMAGE`: 로컬에 빌드된 `ghcr.io/dsa04156/edgeai-<component>:sha-<40자리 커밋>`
- `EDGEAI_RUNNER_DIGEST`, `EDGEAI_MINIO_DIGEST`: 앞선 컨테이너/저장소 CI에서 시험하고 발행한 sha256 digest

GitHub Actions는 이미지 job에서 API/화면 발행 전에 이 시험을 수행한다. kind v0.33.0 바이너리의
SHA-256과 해당 릴리스의 Kubernetes1.35.8 node image digest를 고정한다. 노드는 control-plane1개와
worker2개이며 API/화면의 실제 빌드 이미지를 kind에 적재한다. 별도 Secret·DB·MinIO PVC·버전 bucket을
생성한다. 자격 증명은 임시 mode600 파일에만 기록하고 시험 종료 때 제거한다.

시험 범위는 실제 AUTO/NODE 배치, 2단계 BATCH의 저장된 입력 전달, 파일 version/크기/SHA와 계산값,
실행 중 API 교체 후 동일 Job/Attempt 유지, 잘못된 artifact 거절·동시 commit 단일 Result,
서로 다른 Pod 신원 거절·취소 뒤 늦은 commit 거절, 실제 실행 취소와 불가능한 affinity이다.
`--faults`는 전용 kind context·namespace 소유 label이 일치할 때만 허용한다.
원시 credentials/Pod spec/log/Secret은 artifact에 수집하지 않으며 고정 Runner 이벤트만 읽는다.

현재 실제 kind 성공 여부는 [M4 증거](../../docs/evidence/m4-runtime.md)를 따른다.
기존 실제 클러스터의 Kubernetes1.31.14 및 실장비 성능 수용시험과는 별도 환경이다.
[kind 공식 사용법](https://kind.sigs.k8s.io/docs/user/quick-start/)과
[v0.33.0 릴리스](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0)를 기준으로 구성했다.
