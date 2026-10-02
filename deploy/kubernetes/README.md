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
