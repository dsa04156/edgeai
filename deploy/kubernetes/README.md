# EdgeAI Kubernetes 배포

`base`는 API·Dashboard·PostgreSQL·MinIO, `overlays/dev`는 namespace·Ingress·이미지 digest를 정의한다.
ArgoCD는 `overlays/dev`를 감시한다. 초기 연결·검증·롤백은 [CI/CD 문서](../../docs/operations/cicd.md)를 따른다.

```bash
kubectl kustomize deploy/kubernetes/overlays/dev
python3 scripts/internal/bootstrap-gitops.py --context <대상-context>
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
base의 PostgreSQL PVC 5 GiB는 기본 StorageClass를 사용한다. 현재 overlay의 운영 DB는 아래 HDD 구성으로 대체한다.
자동 prune과 cascade deletion은 사용하지 않는다.
MinIO는 M4 결과 파일 저장에 사용한다. MQTT/STREAM 데이터 경로는 후속 M7 범위다.

M2 API는 전용 `edgeai-control-plane` ServiceAccount와 마운트된 CA/토큰으로
Kubernetes Node API를 읽는다. bootstrap은 소유 label을 확인하고 `edgeai-node-reader`
ClusterRole/Binding을 준비한다. 권한은 core/v1 nodes의 get/list뿐이며 다른 리소스를 읽거나
클러스터 자원을 수정할 수 없다. ArgoCD AppProject의 clusterResourceWhitelist는 계속 비어 있다.
Bootstrap RBAC는 `bootstrap/node-reader.json`, namespace ServiceAccount는 GitOps로 관리한다.

## HDD 운영 데이터베이스

현재 `overlays/dev`에는 `components/hdd-data-store`가 연결되어 있다. PostgreSQL 16.15와
InfluxDB OSS 2.9.1을 각각 단일 StatefulSet으로 실행하며, 기존 `edgeai-postgres`는 replicas=0으로
보존한다. 새 데이터의 기준은 현재 웹이 사용하던 PostgreSQL 16.15 / InfluxDB 데이터다.
기존 클러스터 PostgreSQL 17 데이터는 별도 백업·PVC로 보존하며 16으로 직접 복원하지 않는다.
물리 센서 원본·선택된 VD 결과는 InfluxDB, 정의·SQL·버전·실행 이력은 PostgreSQL에 저장한다.

| PVC | 노드의 HDD 경로 | 선언 용량 |
| --- | --- | --- |
| edgeai-postgres-hdd | /mnt/data3tb/edgeai/databases/postgres | 50Gi |
| edgeai-influxdb-hdd | /mnt/data3tb/edgeai/databases/influxdb | 400Gi |
| edgeai-postgres-backup-hdd | /mnt/data3tb/edgeai/backups/postgres | 100Gi |
| edgeai-influxdb-backup-hdd | /mnt/data3tb/edgeai/backups/influxdb | 1200Gi |

전용 `edgeai-hdd` StorageClass와 static local PV를 사용한다. 위 용량은 디렉터리의 실제 quota나
공간 예약이 아니다. `df -h /mnt/data3tb`와 데이터·백업 증가량을 확인해야 한다. 네 볼륨은
`etri-ser0001-cg0msb`의 `/dev/sdb5` ext4에 있으며 PV nodeAffinity가 노드를 고정한다.
Retain 및 ArgoCD Prune=false/Delete=false로 데이터 리소스의 자동 삭제를 막는다.
Pod 교체에는 데이터가 유지되지만 해당 노드·HDD 장애 시 다른 노드에서 자동 복구되지 않는다.
같은 HDD의 백업은 논리적 복원용이다. 디스크 장애에 대비한 별도 장치·외부 백업은 추가로 필요하다.

클러스터 범위 StorageClass/PV는 GitOps AppProject 권한을 확대하지 않고 별도 bootstrap으로
준비한다. 대상 context·노드·HDD mount·기존 리소스 소유 label과 경로를 먼저 확인한다.
`/mnt/data3tb`가 마운트되지 않았으면 적용하지 않는다. `bootstrap/hdd-storage.yaml` 적용 전에
네 디렉터리를 준비하고 실제 HDD임을 `findmnt -T <경로>`로 확인한다. 같은 이름의 다른 설치
리소스를 인수하지 않는다. 이후 PVC·DB·백업 CronJob·NetworkPolicy는 GitOps가 관리한다.

```bash
kubectl --context <대상-context> apply --dry-run=server -f deploy/kubernetes/bootstrap/hdd-storage.yaml
kubectl --context <대상-context> apply -f deploy/kubernetes/bootstrap/hdd-storage.yaml
```

비밀번호·토큰은 Git 밖에서 `edgeai-postgres-hdd/password`, `edgeai-influxdb-api/token`,
`edgeai-influxdb-admin/token` Secret으로 준비한다. API에는 bucket read/write 토큰만 마운트한다.
관리 토큰은 InfluxDB 백업 Job에서만 사용한다. DB는 ClusterIP로만 연결하고 NetworkPolicy는
edgeai-api·전용 백업/복원 Pod와 현재 웹 API 호스트(192.168.0.56)만 허용한다.
호스트 API는 해당 Service의 ClusterIP를 사용한다. Service를 재생성하면 IP와 호스트 설정을
함께 갱신한다. 현재 웹 API가 유일한 원본 수집기이고 클러스터 API의 RAW_HISTORY_ENABLED는
false다. 수집기를 이전할 때 기존 수집기를 종료한 후 새 수집기를 활성화해 MQTT client 충돌을 막는다.
VD 처리 엔진의 활성화 여부는 이번 DB 이전과 별개로 유지한다.

매일 한국 시간 03:00 PostgreSQL, 03:30 InfluxDB를 백업한다. 완료 표시·체크섬을 기록한 뒤
전용 볼륨에서 최근 완료 백업 3개만 유지한다. 원본은 30일, VD 결과는 정책별 1–30일 보존한다.
초기 이전 자료와 PostgreSQL 17 백업은 `/mnt/data3tb/edgeai/backups/migration-*`에 별도 보존하며
CronJob이 정리하지 않는다. 호스트의 기존 DB 디렉터리는 오프라인 보관하며 운영 DB를 시작하는
용도로 사용하지 않는다. `scripts/internal/influx_local.py`와 로컬 PostgreSQL은 개발·시험 전용이다.

수동 백업은 `kubectl --context <context> -n edgeai create job --from=cronjob/edgeai-postgres-backup
<고유-name>` 또는 `edgeai-influxdb-backup`을 사용한다. 완료 Job·SHA256SUMS를 확인하고
PostgreSQL dump는 별도 DB에 `pg_restore --exit-on-error`로 복원한다. InfluxDB는 동일 2.9.1의
별도 HDD 디렉터리/Pod에 full restore한 후 기존 API 토큰으로 조회를 검증한다. 공유 DB를 덮어쓰지
않는다. full restore는 사용자·토큰·bucket ID를 복원하므로 별도 대상의 초기 operator token을
원본과 같게 설정하고 `INFLUX_TOKEN` 환경 변수로 전달한다. 토큰을 명령 인자에 넣지 않는다.

이전 시 웹 API writer를 중단하고 PostgreSQL의 일관된 dump를 복원한다. InfluxDB는 깨끗하게
종료한 동일 버전에서 engine(data/WAL)·bolt·sqlite 전체를 복사한다. 표별 행 수/해시·원본 기간
조회가 일치하고 실제 백업 복원·Pod 교체 후 조회가 통과한 뒤 연결을 전환한다. 원본 DB·백업을
보존하므로 검증 실패 시 기존 데이터로 복귀할 수 있다. ArgoCD 자동 동기화를 잠시 중지했다면
main 반영 후 원래 syncPolicy를 반드시 복구한다.
# M4 runtime 준비

`python3 scripts/internal/bootstrap-runtime.py --context <명시적-context>`는 소유 labels를 확인한 뒤
`edgeai-runtimes`와 Runner SA, 제어 서버의 namespace 제한 Job/Pod/Secret 권한과 TokenReview
권한을 준비한다. 기존 제어 서버 namespace/SA가 필요하며 공유 리소스를 인수하지 않는다.
Runner SA에는 리소스 접근 권한을 추가하지 않는다. 기본 API token 자동 마운트는 꺼져 있고
Pod 신원 증명용 audience=edgeai-runner projected token만 Job에 포함한다.

내부 API·S3가 사설 CA의 HTTPS를 사용하면 runtime namespace에 공개 CA 번들의 `ca.crt`를 담은
불변 ConfigMap을 준비하고 API에 `EDGEAI_RUNTIME_CA_CONFIG_MAP=<이름>`을 설정한다.
Runner는 이를 읽기 전용으로 마운트하고 인증서 검증을 유지한다. 빈 기본값은 시스템 신뢰 번들을
사용한다. CA 교체는 새 이름으로 준비하며 기존 runtime이 종료될 때까지 기존 번들을 유지한다.
 상세: [ADR0042](../../docs/adr/0042-runtime-tls-trust.md).

같은 API에 추가 native HTTPS 포트를 열려면 `EDGEAI_API_TLS_ENABLED=true`, 별도 포트
`EDGEAI_API_TLS_PORT`(기본18443), PEM 절대 경로 `EDGEAI_API_TLS_CERTIFICATE_FILE` 및
`EDGEAI_API_TLS_PRIVATE_KEY_FILE`을 설정한다. 개인 키는 API만 읽게 마운트하고 클라이언트에는
공개 CA를 전달한다. 기본은 비활성이며 현재 dev overlay의 자동 TLS/STREAM 활성화 설정은 아니다.
인증/CSRF는 기본 API와 같고 인증서 변경은 Pod 재기동으로 반영한다.
[설계·검증 범위](../../docs/adr/0048-native-api-tls-connector.md).

영속 STREAM 신원은 `python3 scripts/internal/bootstrap-stream-secrets.py --context <context>`로 준비한다.
OpenSSL·JDK keytool·mosquitto_ctrl이 필요하며 기존 소유 namespace/RBAC를 먼저 준비해야 한다.
다른 설치는 `--state-dir <별도-비공개-디렉터리>`를 사용한다. 기본 복구본은
`.tools/kubernetes/stream-v1/recovery.json`(0600)이며 Git에 넣거나 출력하지 않는다. 기존 값과
충돌하면 중단하며 키·CA를 자동 교체하지 않는다. CA Secret은 workload에 마운트하지 않는다.

선택 컴포넌트 `deploy/kubernetes/components/stream`은 영속 TLS broker와 API/MinIO TLS 설정을
제공하며 dev overlay의 `components`에 연결돼 있다. 다른 설치에서도 적용 전에 검증된 HTTPS
지원 이미지·신원·공개 CA와 기존 활성 실행를 확인한다. bootstrap 없이 이 overlay를 적용하지 않는다.
기존 MinIO data PVC는 유지한다. 내부 ClusterIP/DNS 연결이며 외부 장치 라우팅은 별도다.
신원 검사는 `python3 scripts/internal/test-stream-bootstrap.py --context <context>`, 준비된 broker 검사는
Paho가 설치된 Python으로 `scripts/internal/test-stream-platform-broker.py --context <context>`를 실행한다.
후자는 실제 broker Pod를 한 번 교체하므로 실행 중인 스트림이 없는 전용 검증 시점에 사용한다.
[신원·영속성 계약](../../docs/adr/0049-persistent-stream-platform.md),
[검증 범위](https://github.com/dsa04156/edgeai/blob/6fe4429688d2a4c79ad8697be262862b9e8701c1/docs/evidence/m7-persistent-stream-platform.md).

활성화 후 `bash scripts/demo/demo-multidevice.sh <context>`는 배포된 API/DB/MinIO/broker를 사용해
합성 장치2개 → STREAM 작업2개 → BATCH 결과의 AUTO·NODE·취소3개 시나리오를 실행한다.
별도 API/DB/broker를 만들거나 공유 서비스를 재시작하지 않는다. 실제 Runner8개·체크포인트·
S3 고정 버전6개를 확인하며, Run 생성 전에 정한 멱등 UUID로 이번 데모의 Run만 취소/정리한다.
검증 근거인 합성 Profile/Device/Workflow/Run/Result 이력은 보존하고 임시 driver/실행 리소스만 회수한다.
결과는 `.tools/multidevice-demo.json`이다. `test-stream-minio-tls.py --context <context>`는 렌더링된
MinIO TLS 설정을 임시 데이터·자격으로 검사하며 기존 데이터에는 접근하지 않는다.

`bash scripts/test/test-stream-kubernetes.sh <context>`는 별도 TLS API/DB/MinIO/MQTT 환경에서 현재 JAR와
검증 Runner 이미지를 연결한다. 실제 통과 여부와 전체 M7 잔여 범위는 [검증 기록](https://github.com/dsa04156/edgeai/blob/6fe4429688d2a4c79ad8697be262862b9e8701c1/docs/evidence/m7-kubernetes-stream.md)을 따른다.
빌드 이미지 자체는 `python3 scripts/internal/test-stream-kubernetes.py --context <context> --api-image <API digest 또는 소스 commit tag>
--api-source <40자리 commit> --runner-image <Runner digest> --runner-source <40자리 commit>`로 검증한다.
이 모드는 이미지의 `/app/app.jar`를 사용하고 API Pod의 imageID와 JAR SHA를 확인한다.
`--minio-image <MinIO digest>`와 `--report <JSON 경로>`도 지정할 수 있다. 예시의 image 값은 모두
`ghcr.io/dsa04156/edgeai-<component>` 전체 참조여야 하며 임의 tag는 받지 않는다.

`bash scripts/test/test-runtime-kubernetes.sh <명시적-context>`는 제한된 제어 서버 SA/TLS로
실제 AUTO/NODE·TokenReview·watch·삭제를 시험한다. 해당 Attempt의 리소스만 정리한다.
대기용 컨테이너 시험이며 Runner/MinIO 전체 실행 증거와 구분한다.

배포 config는 runtime 실행을 활성화한다. 적용 전에 CI 검증 Runner/MinIO digest, 버전 관리가 켜진
전용 bucket, API에서만 읽는 영속 HMAC 키(64 hex, 파일 mode600), 저장소 비밀정보가 필요하다.
키는 API 재시작 때 변경하지 않는다. `.env.example`의 EDGEAI_RUNTIME_* / EDGEAI_STORAGE_*
설정을 따르며 자격 증명은 Git에 넣지 않는다. 기존 M3 Run은 활성화 후에도 자동 실행하지 않는다.

`python3 scripts/internal/bootstrap-runtime-secrets.py --context <context>`는 전용 signing/storage Secret을
생성하고 `.tools/kubernetes/`에 mode600 복구 파일을 유지한다. 재실행해도 키를 교체하지 않는다.
MinIO는 전용5Gi PVC를 사용하며 외부 Ingress에 노출하지 않는다. CI 검증 digest로 실행한 후
로컬 port-forward와 비공개 storage 환경 파일을 사용해 `node scripts/internal/bootstrap-artifact-bucket.mjs`를
실행한다. 기존 버킷은 소유 태그를 검사하고 비공개·versioning을 확인하며 object를 삭제하지 않는다.

실행 활성화 후 CRUD 회귀는 `EDGEAI_SMOKE_RUNTIME_ENABLED=true`로 `scripts/internal/smoke-deployment.py`를 실행한다.
전체 Runner 시험은 `scripts/internal/smoke-runtime.py --context <context>`이며 `EDGEAI_SMOKE_API_URL`,
API 인증 환경 변수와 `EDGEAI_STORAGE_URL`/저장소 인증이 필요하다. API는 공개 Result 조회를 지원해야 한다.
AUTO/NODE BATCH·실제 파일 체크섬/계산값·실행 중 취소·불가능한 affinity를 시험하고 해당 Run 리소스를 정리한다.
같은 시험은 `bash scripts/demo/demo-workflow.sh <context>`로 실행할 수 있다. 실제 실행 결과는 evidence에
기록한다. 실제 kind 게이트의 대체는 아니다. bootstrap의 `--state-dir`로 클러스터별 비밀정보 복구
디렉터리를 분리하며, 기존 배포의 복구 파일을 다른 클러스터에 재사용하지 않는다.
