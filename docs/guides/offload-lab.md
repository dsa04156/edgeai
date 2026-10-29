# 오프로딩 테스트 화면

콘솔 **오프로딩 테스트**(`/ui/main/offload-lab`)는 edge 과부하 → worker 이동 → 부하 해제 후 원래 edge 복귀를
실제 Pod로 보여주는 시험용 화면입니다. 새 배치 엔진이 아니라 [노드 기반 전환](node-placement-offload.md)의
`returnToEdge` 정책과 기존 Run·전환 경로를 그대로 사용합니다.

이 문서는 코드·격리 DB 시험 기준입니다. 실제 클러스터 왕복은 배포 후 별도로 확인합니다.

## 무엇이 실제로 실행되나

| 구성 | 내용 |
|---|---|
| 모델 | `runner/examples/offload_lab_model.py`. UCI 손글씨 숫자(scikit-learn `load_digits`, CC BY 4.0)로 학습한 MLP 64-32-10을 CPU 순수 Python으로 추론합니다. 가중치·검증 표본은 `runner/examples/models/digits-mlp-v1.json`(sha256 `7a88a173…f5469`, 학습 시 검증 정확도 0.969)이며 실행 시 digest를 확인합니다. 초당 약 20회 추론하고 1회 지연(μs)을 Runner 측정으로 보고하며, 종료 시 추론 수·검증 표본 정확도를 결과로 저장합니다. GPU 추론·성능 측정이 아닙니다. |
| 학습 | `runner/examples/models/train_digits_mlp.py`(scikit-learn 1.5.2, random_state=0, 80/20 층화 분할)로 재현합니다. 이미지 안에서는 학습하지 않습니다. |
| 부하 | `runner/examples/offload_lab_load.py`. 세션 edge 하나에 고정 프로그램 Job(프로세스 1–16개, 30–1800초)을 만들어 실제 노드 CPU를 사용합니다. 화면은 이미지·명령을 보내지 않습니다. |
| 판단 | Prometheus 노드 CPU 관측(약 30초 수집)과 노드 inventory. 관측이 없거나 오래되면 판단하지 않습니다. |

## 사용 순서

1. edge 노드와 시험값(과부하 CPU %, 복귀 CPU %, worker 상한, 연속 표본, 대기 시간, 최대 전환 횟수)을 확인합니다.
   기본값은 시험용(과부하 50%, 복귀 20%, 연속 2개, 시작 후 20초·전환 후 30초 대기, 최대 2회, 부하 3프로세스·300초)이며
   운영 정책 기본값이 아닙니다.
2. **모델 실행**: 선택한 edge에 일반 Run을 만듭니다. Pod가 Running으로 관측되어야 배치에 “모델 실행 중”이 보입니다.
3. **부하 시작**: 같은 edge에 부하 Job을 만듭니다. CPU가 연속 표본 동안 과부하 이상이면 Run이 worker로 이동합니다.
   화면은 기존 Pod의 “종료 중”과 새 Pod 생성을 Kubernetes 관측으로 보여주고, Attempt 표에 Pod UID·노드 기록을 남깁니다.
4. **부하 중지**(또는 부하 시간 만료): edge CPU가 연속 표본 동안 복귀 기준 미만이면 원래 edge로 돌아갑니다.
5. **모델 중지**: Run을 취소하고(진행 중 전환 포함) 부하도 중지합니다. 모델·부하 Pod가 모두 사라진 것이 관측되면 종료됨입니다.

서버 전체에서 열린 시험은 1개입니다. 새로고침·다른 브라우저는 같은 시험을 다시 보여줍니다. 같은 버튼 재시도는
같은 Idempotency-Key로 재전송되어 두 번째 Run·부하를 만들지 않고, 실행 중 부하가 있으면 새 부하 시작은 409입니다.

## 정리 보장

- 부하 Job은 프로그램 자체 종료(부하 시간) + `activeDeadlineSeconds`(부하 시간 + 30초) + `ttlSecondsAfterFinished=600`으로 제한됩니다.
- API의 조정 루프(기본 3초)가 만료 부하·중지된 시험·API 재시작으로 남은 소유 Job(`app.kubernetes.io/managed-by=edgeai-offload-lab`)을 삭제합니다.
  다른 라벨의 객체는 조회·삭제하지 않으며 삭제에는 UID 조건을 붙입니다.
- 모델 Run은 SERVICE `timeoutSeconds`(1800초)와 모델 `durationSeconds`(1500초)로 제한됩니다.

## 배포 구성

1. 이미지: `10.254.192.47:5000/edgeai/offload-lab@sha256:94a0fc502c99dd45f47bae3e4c0ac69dbd698b8b404c2c80c411e934317a48ef`
   (linux/amd64·arm64). `runner/Dockerfile.offload-lab`로 CI가 만든 multi-arch Runner index
   (`sha256:ed9a4c7dd286…`) 위에 모델·부하 스크립트와 모델 파일만 COPY했습니다(RUN 없음).
   빌드 입력 4개 파일의 sha256은 저장소 원본과 같습니다.
2. 워크플로 발행: `deploy/offload-lab/offload-lab.json`(profile image가 위 digest)으로
   `EDGEAI_API_URL=... EDGEAI_API_USER=... EDGEAI_API_PASSWORD=... python3 -I hawkeye-runtime/workflow/publish.py deploy/offload-lab/offload-lab.json`.
3. API 설정: `overlays/dev`가 `components/hawkeye-video` 뒤에 `components/offload-lab`을 포함합니다.
   `EDGEAI_RUNTIME_WORKFLOW_KEYS`는 `hawkeye-video-safety,offload-lab`, `EDGEAI_OFFLOAD_LAB_LOAD_IMAGE`는 같은 digest입니다.
   실행 기능·Prometheus·Kubernetes 연결이 필요하며 부하 Job·Pod 조회는 기존 runtime 네임스페이스 RBAC(jobs·pods get/list/create/delete)를 사용합니다.
4. 설정 값: `EDGEAI_OFFLOAD_LAB_LOAD_IMAGE`(digest 고정 필수, 없으면 부하·시험 비활성), `EDGEAI_OFFLOAD_LAB_WORKFLOW_KEY`(기본 `offload-lab`),
   `EDGEAI_OFFLOAD_LAB_POLL_MS`(기본 3000).

## API

`GET /api/v1/offload-lab`, `POST /api/v1/offload-lab/sessions`(Idempotency-Key), `POST .../sessions/{id}/stop`,
`POST .../sessions/{id}/load`(Idempotency-Key), `POST .../sessions/{id}/load/stop`. 계약은 OpenAPI `OffloadLab` 태그를 따릅니다.
