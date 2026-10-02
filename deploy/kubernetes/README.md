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

배포 config는 runtime 실행을 활성화한다. 적용 전에 CI 검증 Runner/MinIO digest, 버전 관리가 켜진
전용 bucket, API에서만 읽는 영속 HMAC 키(64 hex, 파일 mode600), 저장소 비밀정보가 필요하다.
키는 API 재시작 때 변경하지 않는다. `.env.example`의 EDGEAI_RUNTIME_* / EDGEAI_STORAGE_*
설정을 따르며 자격 증명은 Git에 넣지 않는다. 기존 M3 Run은 활성화 후에도 자동 실행하지 않는다.

`python3 scripts/bootstrap-runtime-secrets.py --context <context>`는 전용 signing/storage Secret을
생성하고 `.tools/kubernetes/`에 mode600 복구 파일을 유지한다. 재실행해도 키를 교체하지 않는다.
MinIO는 전용5Gi PVC를 사용하며 외부 Ingress에 노출하지 않는다. CI 검증 digest로 실행한 후
로컬 port-forward와 비공개 storage 환경 파일을 사용해 `node scripts/bootstrap-artifact-bucket.mjs`를
실행한다. 기존 버킷은 소유 태그를 검사하고 비공개·versioning을 확인하며 object를 삭제하지 않는다.

실행 활성화 후 CRUD 회귀는 `EDGEAI_SMOKE_RUNTIME_ENABLED=true`로 `scripts/smoke-deployment.py`를 실행한다.
전체 Runner 시험은 `scripts/smoke-runtime.py --context <context>`이며 `EDGEAI_SMOKE_API_URL`,
API 인증 환경 변수와 `EDGEAI_STORAGE_URL`/저장소 인증이 필요하다. API는 공개 Result 조회를 지원해야 한다.
AUTO/NODE BATCH·실제 파일 체크섬/계산값·실행 중 취소·불가능한 affinity를 시험하고 해당 Run 리소스를 정리한다.
이 명령은 현재 준비 단계이며 실제 실행 결과는 evidence에 기록한다. 실제 kind 게이트의 대체는 아니다.
