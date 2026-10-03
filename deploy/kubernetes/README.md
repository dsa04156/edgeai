# EdgeAI Kubernetes 배포

`base`는 API·Dashboard·PostgreSQL·MinIO, `overlays/dev`는 namespace·Ingress·이미지 digest를 정의한다.
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
API upgrade는 Recreate로 이전 API 종료 후 새 버전을 시작한다. V7의 새 필수 Attempt 필드와
OFFLOADING 상태를 모르는 구버전 writer가 겹치지 않도록 하며, 새 API 준비 전까지 접속 중단이 있다.
V7 적용 후에는 V7 미지원 이미지로 단순 롤백하지 않는다. 무중단/다중 replica는 M9 검증 범위다.
PVC 5 GiB는 기본 StorageClass를 사용한다. 자동 prune과 cascade deletion은 사용하지 않는다.
MinIO는 M4 결과 파일 저장에 사용한다. MQTT/STREAM 데이터 경로는 후속 M7 범위다.

M2 API는 전용 `edgeai-control-plane` ServiceAccount와 마운트된 CA/토큰으로
Kubernetes Node API를 읽는다. bootstrap은 소유 label을 확인하고 `edgeai-node-reader`
ClusterRole/Binding을 준비한다. 권한은 core/v1 nodes의 get/list뿐이며 다른 리소스를 읽거나
클러스터 자원을 수정할 수 없다. ArgoCD AppProject의 clusterResourceWhitelist는 계속 비어 있다.
Bootstrap RBAC는 `bootstrap/node-reader.json`, namespace ServiceAccount는 GitOps로 관리한다.
# M4 runtime 준비

dev overlay는 CI의 실제 VD Task 수용까지 통과한 이미지에서 `EDGEAI_VD_ENABLED=true`를
API 컨테이너에 명시한다. 로컬/공통 애플리케이션 기본값은 false다. 추가 권한 확대 없이 기존
전용 namespace의 VD Pod·Secret 권한을 사용한다. 실제 상태는 VD execution/Operation으로 확인한다.

`python3 scripts/bootstrap-runtime.py --context <명시적-context>`는 소유 labels를 확인한 뒤
`edgeai-runtimes`와 Runner SA, 제어 서버의 namespace 제한 Job/Pod/Secret 권한과 TokenReview
권한을 준비한다. 기존 제어 서버 namespace/SA가 필요하며 공유 리소스를 인수하지 않는다.
Runner SA에는 리소스 접근 권한을 추가하지 않는다. 기본 API token 자동 마운트는 꺼져 있고
Pod 신원 증명용 audience=edgeai-runner projected token만 Job에 포함한다.

내부 API·S3가 사설 CA의 HTTPS를 사용하면 runtime namespace에 공개 CA 번들의 `ca.crt`를 담은
불변 ConfigMap을 준비하고 API에 `EDGEAI_RUNTIME_CA_CONFIG_MAP=<이름>`을 설정한다.
Runner/VD는 이를 읽기 전용으로 마운트하고 인증서 검증을 유지한다. 빈 기본값은 시스템 신뢰 번들을
사용한다. CA 교체는 새 이름으로 준비하며 기존 runtime이 종료될 때까지 기존 번들을 유지한다.
VD는 이름을 실행 설정에 고정한다. 상세: [ADR0042](../../docs/adr/0042-runtime-tls-trust.md).

같은 API에 추가 native HTTPS 포트를 열려면 `EDGEAI_API_TLS_ENABLED=true`, 별도 포트
`EDGEAI_API_TLS_PORT`(기본18443), PEM 절대 경로 `EDGEAI_API_TLS_CERTIFICATE_FILE` 및
`EDGEAI_API_TLS_PRIVATE_KEY_FILE`을 설정한다. 개인 키는 API만 읽게 마운트하고 클라이언트에는
공개 CA를 전달한다. 기본은 비활성이며 현재 dev overlay의 자동 TLS/STREAM 활성화 설정은 아니다.
인증/CSRF는 기본 API와 같고 인증서 변경은 Pod 재기동으로 반영한다.
[설계·검증 범위](../../docs/adr/0048-native-api-tls-connector.md).

영속 STREAM 신원은 `python3 scripts/bootstrap-stream-secrets.py --context <context>`로 준비한다.
OpenSSL·JDK keytool·mosquitto_ctrl이 필요하며 기존 소유 namespace/RBAC를 먼저 준비해야 한다.
다른 설치는 `--state-dir <별도-비공개-디렉터리>`를 사용한다. 기본 복구본은
`.tools/kubernetes/stream-v1/recovery.json`(0600)이며 Git에 넣거나 출력하지 않는다. 기존 값과
충돌하면 중단하며 키·CA를 자동 교체하지 않는다. CA Secret은 workload에 마운트하지 않는다.

선택 컴포넌트 `deploy/kubernetes/components/stream`은 영속 TLS broker와 API/MinIO TLS 설정을
제공한다. 아직 dev overlay에는 활성화하지 않았다. 검증된 HTTPS 지원 이미지·신원·공개 CA와
기존 활성 실행/VD를 확인한 뒤 overlay의 `components`에 `../../components/stream`을 연결한다.
기존 MinIO data PVC는 유지한다. 내부 ClusterIP/DNS 연결이며 외부 장치 라우팅은 별도다.
신원 검사는 `python3 scripts/test-stream-bootstrap.py --context <context>`, 준비된 broker 검사는
Paho가 설치된 Python으로 `scripts/test-stream-platform-broker.py --context <context>`를 실행한다.
후자는 실제 broker Pod를 한 번 교체하므로 실행 중인 스트림이 없는 전용 검증 시점에 사용한다.
[신원·영속성 계약](../../docs/adr/0049-persistent-stream-platform.md),
[검증 범위](../../docs/evidence/m7-persistent-stream-platform.md).

`bash scripts/test-stream-kubernetes.sh <context>`는 별도 TLS API/DB/MinIO/MQTT 환경에서 현재 JAR와
검증 Runner 이미지를 연결한다. 실제 통과 여부와 전체 M7 잔여 범위는 [검증 기록](../../docs/evidence/m7-kubernetes-stream.md)을 따른다.
빌드 이미지 자체는 `python3 scripts/test-stream-kubernetes.py --context <context> --api-image <API digest 또는 소스 commit tag>
--api-source <40자리 commit> --runner-image <Runner digest> --runner-source <40자리 commit>`로 검증한다.
이 모드는 이미지의 `/app/app.jar`를 사용하고 API Pod의 imageID와 JAR SHA를 확인한다.
`--minio-image <MinIO digest>`와 `--report <JSON 경로>`도 지정할 수 있다. 예시의 image 값은 모두
`ghcr.io/dsa04156/edgeai-<component>` 전체 참조여야 하며 임의 tag는 받지 않는다.

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
같은 시험은 `bash scripts/demo-workflow.sh <context>`로 실행할 수 있다. 실제 실행 결과는 evidence에
기록한다. 실제 kind 게이트의 대체는 아니다. bootstrap의 `--state-dir`로 클러스터별 비밀정보 복구
디렉터리를 분리하며, 기존 배포의 복구 파일을 다른 클러스터에 재사용하지 않는다.
