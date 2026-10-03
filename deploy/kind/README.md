# kind — 실행·전환·VD·다중 장치 스트림 수용시험

`bash scripts/test-kind.sh`는 Linux amd64 Docker 환경에서 이름이 임의 생성된 전용 클러스터를
만들고 시험 후 해당 클러스터만 삭제한다. 사용자 kubeconfig와 현재 context는 읽거나 변경하지 않는다.
Docker 권한이 없으면 exit2이며 자동으로 권한을 변경하지 않는다.

필수 환경 변수:

- `EDGEAI_API_IMAGE`, `EDGEAI_DASHBOARD_IMAGE`: 로컬에 빌드된 `ghcr.io/dsa04156/edgeai-<component>:sha-<40자리 커밋>`
- `EDGEAI_RUNNER_DIGEST`, `EDGEAI_MINIO_DIGEST`: 앞선 컨테이너/저장소 CI에서 시험하고 발행한 sha256 digest

호스트에는 Docker·kubectl·Node·OpenSSL 외에 Java `keytool`과 `mosquitto_ctrl`이 필요하다.
Ubuntu24.04에서 `mosquitto_ctrl`은 `mosquitto` 패키지에 포함된다. GitHub images 작업은 이를 준비한다.

GitHub Actions는 이미지 job에서 API/화면 발행 전에 이 시험을 수행한다. kind v0.33.0 바이너리의
SHA-256과 해당 릴리스의 Kubernetes1.35.8 node image digest를 고정한다. 노드는 control-plane1개와
worker2개이며 API/화면의 실제 빌드 이미지를 kind에 적재한다. 별도 Secret·DB·MinIO PVC·버전 bucket을
생성한다. 자격 증명은 임시 mode600 파일에만 기록하고 시험 종료 때 제거한다.

시험 범위는 실제 AUTO/NODE 배치, 2단계 BATCH의 저장된 입력 전달, 파일 version/크기/SHA와 계산값,
실행 중 API 교체 후 동일 Job/Attempt 유지, 잘못된 artifact 거절·동시 commit 단일 Result,
서로 다른 Pod 신원 거절·취소 뒤 늦은 commit 거절, 실제 실행 취소와 불가능한 affinity/CPU 부족,
출력 누락·프로세스 실패 뒤 하위 SKIPPED와 Result 부재를 포함한다.
`--faults`는 전용 kind context·namespace 소유 label이 일치할 때만 허용한다.
원시 credentials/Pod spec/log/Secret은 artifact에 수집하지 않으며 고정 Runner 이벤트만 읽는다.

## Remote 전환 게이트

kind 전용 설정은 별도 `edgeai-remote` Pod·PVC에서 참조 제공자를 실행한다. 기존에 시험한 Runner 이미지의
Python과 ConfigMap의 참조 코드/TLS launcher를 사용한다. 시험마다 생성한 인증서의 서비스 DNS를 검증하고
API에는 CA·bearer만, 제공자에는 별도 Secret의 TLS key·bearer를 마운트한다. 기본 배포 설정은 변경하지 않는다.
제공자는 API 재시작과 독립적이며 SQLite·실제 파일은 PVC에 보관한다.

`smoke-runtime.py --faults --remote`는 소유 label이 일치하는 폐기 kind에서만 실행한다.
기존 Kubernetes 시험 뒤 다음 네 Run을 추가한다.

1. 공개 REMOTE BATCH 중 API Pod 교체: 같은 Attempt·allocation 유지, 실제 계산 횟수1, 하위 결과 확인.
2. 실제 NODE→REMOTE: 이전 Pod 종료·늦은 commit 차단, 같은 Task의 새 Attempt와 고정 선행 파일로 결과 확정.
3. 실제 REMOTE→NODE: 제공자의 CANCELLED 확인, target STARTING 중 API 교체, 동일 target Attempt 복구,
   kube-scheduler 배치와 Runner가 받은 S3 object version 대조.
4. 실행 중 Remote 취소 직후 API 교체: 실제 제공자 종료, 결과 부재와 하위 Attempt 미생성 확인.

제공자 SQLite에서 시험 Run의 신원/상태/계산 횟수/파일 metadata만 추출하며 자격·전체 요청은 기록하지 않는다.
두 실행 방식은 같은 `linear.py` 계약과 합성 입력을 사용한다. `simulationDelayMillis`는0~60000의
제어 시험 대기이며 성능 측정값이 아니다. Result의 실제 S3 version/bytes/SHA와 예상 계산값을 검사한다.
추가 게이트의 성공 여부는 [Remote kind 증거](../../docs/evidence/m5-remote-kind.md)를 따른다.

현재 실제 kind 성공 여부는 [M4 증거](../../docs/evidence/m4-runtime.md)를 따른다.
기존 실제 클러스터의 Kubernetes1.31.14 및 실장비 성능 수용시험과는 별도 환경이다.
[kind 공식 사용법](https://kind.sigs.k8s.io/docs/user/quick-start/)과
[v0.33.0 릴리스](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0)를 기준으로 구성했다.

## 다중 장치 스트림 게이트

기존 BATCH/Remote/VD 시험 뒤 `test-stream-kubernetes.py`를 같은 폐기 kind context에서 실행한다.
별도 UID 소유 TLS API/MinIO/Mosquitto/DB/source 자원을 만들며, API는 kind에 적재한 **빌드 이미지의
`/app/app.jar`**를 직접 실행한다. 로컬 JAR를 덮어쓰지 않는다. Runner/MinIO는 위 CI-tested digest다.
실제 API Pod 두 개의 imageID와 같은 JAR SHA, Runner Pod들의 신원·digest를 보고서에 보존한다.

두 합성 DeviceSource→두 STREAM Runner→BATCH report의 AUTO/NODE, API 교체 후 계속 처리,
처리 중 sink 취소, 고정 S3 파일6개의 크기·SHA·버전·14/23/37 결과와 소유 자원 정리를 검증한다.
API/S3/MQTT 모두 TLS 인증서를 검증한다. 실패하면 API/화면 이미지 발행으로 진행하지 않는다.
비밀을 포함하지 않는 `.tools/kind-stream.json`만 기존 이미지 검증 artifact에 추가한다.

이 새 게이트의 실제 kind 통과 여부는 [M7 증거](../../docs/evidence/m7-kubernetes-stream.md)를 따른다.
현재 JAR를 사용한 기존 클러스터 시험 성공이 새 빌드 이미지/CI의 완료를 대신하지 않는다.
