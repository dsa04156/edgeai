# DDS 서비스 구성과 GitOps 배포

**서비스 워크플로 → DDS · GitOps 배포** 화면(`/workflows?mode=dds`)에서 DDS 서비스의 Kubernetes 배포 구성을 작성합니다.
Platform-Service의 편집기 → FastAPI → Gitea → Argo CD 구조를 사용합니다.
Spring DAG 실행은 [별도 가이드](dag-execution.md)를 따릅니다.

## 준비 사항

- 실행 중인 Platform-Service API와 Dashboard의 서버 간 연결 설정
- 쓰기 가능한 Gitea 저장소와 이를 읽을 수 있는 Argo CD
- 선택한 이미지와 호환되는 Kubernetes 노드
- 이미지 빌드를 사용할 경우 Docker 접근, Buildx builder, 레지스트리 push 권한

서버 주소·토큰·빌드 문맥 설정은 [Platform-Service 실행 안내](../../platform-service/README.md)를 확인합니다.
`EDGEAI_WORKFLOW_ENABLED=true`는 화면을 열지만 외부 서비스를 자동으로 준비하지는 않습니다.

## 1. 배포 구성 작성

템플릿 목록에서 Sensor Pub, Camera, Discovery 등 필요한 노드를 캔버스에 추가합니다.
노드를 더블클릭하여 이미지, 환경 변수, DDS 토픽, Discovery 주소와 배치 조건을 확인합니다.
대상 클러스터의 노드 라벨과 이미지 아키텍처에 맞게 설정합니다.

캔버스 연결선은 편집 정보입니다. 선을 잇는 것만으로 실행 의존성이나 DDS 통신이 생성되지 않습니다.
Sensor Pub의 기본 템플릿은 시험 데이터를 발행하며 EdgeX 물리 센서를 자동으로 연결하지 않습니다.

## 2. 이미지 빌드 선택

**배포 전 Buildx 빌드·푸시**를 선택하면 서버에 고정한 Dockerfile/context로 이미지를 빌드합니다.
성공한 이미지 digest를 해당 DDS 이미지에 반영하고 다음 배포 단계로 진행합니다.
직접 지정한 다른 저장소의 이미지는 유지합니다. 빌드 실패 시 Git 저장을 시작하지 않습니다.

## 3. GitOps로 배포

**Deploy to GitOps**를 실행하면 다음 순서로 처리합니다.

1. 로컬 작업 폴더에 `deployment.yaml`을 저장합니다.
2. Gitea의 `<app>/deployment.yaml`을 생성하거나 갱신합니다.
3. Argo Application을 생성하거나 기존 Application을 refresh합니다.

Git 저장 후 Argo 단계가 실패할 수 있습니다. 이때 Git에 남은 변경과 Argo 상태를 먼저 확인하고
다시 요청합니다. 화면 연결이 끊겨도 이미 시작한 서버 작업은 계속될 수 있습니다.

## 4. 결과 확인

화면에서 Argo의 sync/health를 확인하고 실제 Pod·이미지·통신을 점검합니다.
API 수락은 배포 완료가 아니며 Argo 동기화도 애플리케이션 데이터 검증을 대신하지 않습니다.
Discovery 포트 충돌과 센서 입력·출력은 해당 서비스에서 확인합니다.

## 배포 삭제

**Delete App**은 확인 후 Argo cascade 삭제, Gitea YAML 삭제, 로컬 파일 삭제 순서로 처리합니다.
실제 클러스터 리소스에 영향을 주므로 대상 앱을 확인합니다. 삭제 접수 뒤 리소스 종료 상태를 확인하세요.

## 관련 문서

- [두 워크플로 경로의 차이](../concepts/workflows.md)
- [플랫폼 자체의 CI/CD](../operations/cicd.md): 위 서비스 배포와 다른 파이프라인
- [문제 해결](../operations/troubleshooting.md)
