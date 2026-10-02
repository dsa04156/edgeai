# EdgeAI Kubernetes 배포

`base`는 API·Dashboard·PostgreSQL, `overlays/dev`는 namespace·Ingress·이미지 digest를 정의한다.
ArgoCD는 `overlays/dev`를 감시한다. 초기 연결·검증·롤백은 [CI/CD 문서](../../docs/cicd.md)를 따른다.

```bash
kubectl kustomize deploy/kubernetes/overlays/dev
python3 scripts/bootstrap-gitops.py --context <대상-context>
kubectl --context <대상-context> apply --dry-run=server -k deploy/kubernetes/overlays/dev
kubectl --context <대상-context> apply -f deploy/argocd/project.yaml
kubectl --context <대상-context> apply -f deploy/argocd/application.yaml
```

Application은 Actions가 최초 이미지를 발행하고 `bootstrap` 태그를 digest로 바꾼 뒤 등록한다.
비밀정보는 Git에 넣지 않는다. bootstrap은 `edgeai-runtime`을 생성하고
`.tools/kubernetes/edgeai-runtime.env`에 mode 600으로 보관한다. 기존 Secret과 DB 비밀번호는 유지한다.

현재 개발 배포는 amd64 서버 노드에 단일 API·Dashboard·PostgreSQL을 배치한다.
PVC 5 GiB는 기본 StorageClass를 사용한다. 자동 prune과 cascade deletion은 사용하지 않는다.
MinIO·MQTT는 현재 M3 관리/실행 요청 저장 경로에 필요하지 않아 이 배포에 포함하지 않는다.

M2 API는 전용 `edgeai-control-plane` ServiceAccount와 마운트된 CA/토큰으로
Kubernetes Node API를 읽는다. bootstrap은 소유 label을 확인하고 `edgeai-node-reader`
ClusterRole/Binding을 준비한다. 권한은 core/v1 nodes의 get/list뿐이며 다른 리소스를 읽거나
클러스터 자원을 수정할 수 없다. ArgoCD AppProject의 clusterResourceWhitelist는 계속 비어 있다.
Bootstrap RBAC는 `bootstrap/node-reader.json`, namespace ServiceAccount는 GitOps로 관리한다.
# M4 runtime 준비

`python3 scripts/bootstrap-runtime.py --context <명시적-context>`는 소유 labels를 확인한 뒤
`edgeai-runtimes`와 Runner SA, 제어 서버의 namespace 제한 Job/Pod/Secret 권한과 TokenReview
권한을 준비한다. 기존 제어 서버 namespace/SA가 필요하며 공유 리소스를 인수하지 않는다.
Runner SA에는 리소스 접근 권한을 추가하지 않는다. 기본 API token 자동 마운트는 꺼져 있고
Pod 신원 증명용 audience=edgeai-runner projected token만 Job에 포함한다.

`bash scripts/test-runtime-kubernetes.sh <명시적-context>`는 제한된 제어 서버 SA/TLS로
실제 AUTO/NODE·TokenReview·watch·삭제를 시험한다. 해당 Attempt의 리소스만 정리한다.
대기용 컨테이너 시험이며 Runner/MinIO 전체 실행 증거와 구분한다.

배포 실행은 아직 비활성이다. 활성화에는 CI 검증 Runner/MinIO digest, 버전 관리가 켜진
전용 bucket, API에서만 읽는 영속 HMAC 키(64 hex, 파일 mode600), 저장소 비밀정보가 필요하다.
키는 API 재시작 때 변경하지 않는다. `.env.example`의 EDGEAI_RUNTIME_* / EDGEAI_STORAGE_*
설정을 따르며 자격 증명은 Git에 넣지 않는다. 기존 M3 Run은 활성화 후에도 자동 실행하지 않는다.
