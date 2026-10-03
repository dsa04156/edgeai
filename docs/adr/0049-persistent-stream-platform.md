# ADR 0049: 영속 TLS 스트리밍 기반과 재실행 가능한 신원 준비

2026-10-04 KST. ADR0048의 추가 HTTPS를 운영 연결에 사용할 수 있도록 전용 브로커·신원과
Kustomize 컴포넌트를 준비한다. 배포 API의 STREAM 활성화와 다중 장치 데모는 후속 게이트다.

후속 [ADR0050](0050-deployed-multidevice-demo.md)에서 dev 활성화·기존 데이터 보존과 실제 배포
데모를 검증했다. 아래의 활성화 전 설명은 이 결정 당시의 준비 경계다.

## 신원과 복구

`scripts/bootstrap-stream-secrets.py --context <context>`는 기존 소유 namespace `edgeai`와
`edgeai-runtimes`의 UID를 확인하고 고정 v1 Secret4개·공개 ConfigMap3개를 준비한다.
실행 전 모든 기존 객체의 소유 label·불변 설정·전체 데이터를 비교한다. 다른 값은 덮어쓰지 않으며
부분 생성 실패는 같은 recovery bundle로 재실행한다. 서로 다른 설치에는 별도 state directory가 필요하다.

- `edgeai-stream-ca-v1`: CA 인증서·개인 키·표준 JDK CA를 보존한 공개 PKCS12 trust store.
  이 Secret은 어떤 workload에도 마운트하지 않는다. 설치 복구용이며 자동 CA 교체는 제공하지 않는다.
- `edgeai-api-stream-identity-v1`: API 전용 TLS 키/인증서, broker 관리 비밀번호, 별도 principal/device 파생 키.
- `edgeai-mqtt-identity-v1`: broker 전용 TLS 키/인증서와 최초 default-deny Dynamic Security 문서.
- `edgeai-minio-identity-v1`: MinIO 전용 TLS 키/인증서.
- `edgeai-runtime-ca-v1`: 각 namespace에 공개 CA를 배포한다. 제어 namespace에만 공개 Java trust store도 둔다.
- `edgeai-stream-config-v1`: endpoint·파일 참조·신뢰 이름·기능 설정이다. 비밀 값은 없다.

CA와 서비스3개의 키는 서로 다르며, leaf 인증서는 내부 Service DNS와 localhost 검사 주소를
포함한다. 최초 생성뿐 아니라 복구 시에도 인증서 유효기간·개인 키 일치·서명/hostname을 확인한다.
leaf는365일, CA는3650일이며 자동 갱신 완료를 주장하지 않는다. 만료 전 별도 버전 교체가 필요하다.
API의 Java trust store에는 선택한 JDK의 기존 공인 CA도 보존해 기존 HTTPS Remote 신뢰를 유지한다.

복구 파일은 `.tools/kubernetes/stream-v1/recovery.json`(0600), 디렉터리는0700이며 자격을 출력하지 않는다.
로컬 잠금·임시 파일 fsync/rename 후 Kubernetes 생성을 시작한다. 모든 Secret이 남으면 별도
비공개 디렉터리에 원본을 복원할 수 있다. 일부만 남고 로컬 복구본이 없으면 새 신원을 만들지 않는다.
이 파일과 CA Secret은 민감한 운영 자산이며, 외부 백업·복구/인증서 교체 수용은 M9에 남는다.

## 영속 브로커와 활성화 경계

`deploy/kubernetes/components/stream`은 digest 고정 Mosquitto·PVC와 API/MinIO TLS 패치를 제공한다.
브로커는 ClusterIP8883만 열며 namespace를 명시한다. Dynamic Security 파일은 PVC에 처음 한 번
준비하고 이후 재시작에서 초기 문서를 복사하지 않는다. 기존 초기화 표시/메시지 DB가 있는데
권한 파일이 없으면 시작하지 않는다. 역할 회수 이력은 ADR0024대로 보존하며 임의 GC하지 않는다.

[Mosquitto 설정](https://mosquitto.org/man/mosquitto-conf-5.html)과
[Dynamic Security](https://mosquitto.org/documentation/dynamic-security/)를 사용하며, default ACL4개는
모두 deny다. 실제 broker에 TLS로 접속해 관리 권한·기본 정책·Pod 교체 뒤 역할 보존을 검증한다.
이 시험은 정상 Pod 종료/재생성 범위이며 전원 손실·스토리지 손상·모든 메시지 전달 보장은 별도다.

API만 본인 Secret을 비공개 일반 파일로 복사해 기존0400/0600 일반 파일 검사를 만족한다.
Runtime에는 공개 CA만 마운트하며 기존 VD의 고정 신뢰 설정은 ADR0042를 따른다. MinIO의 기존
data PVC는 유지하고 TLS 인증서 마운트와 probe scheme만 변경한다. 원격 장치의 외부 라우팅은
이 내부 ClusterIP 준비만으로 완료했다고 판단하지 않는다.

컴포넌트는 아직 dev overlay에 연결하지 않았다. 검증된 HTTPS 지원 API 이미지가 반영된 후
활성 실행/VD를 확인하고 연결하며, 기존 데이터 보존·API/MinIO TLS·실제 다중 장치 데모를 검증한다.
현재 영속 브로커와 신원 준비는 해당 API 활성화를 대신하지 않는다.

[검증 결과와 작업 중 오류](../evidence/m7-persistent-stream-platform.md).
