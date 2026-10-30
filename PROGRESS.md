# 현재 상태

**확인 기준: 2026-10-30.** 코드·배포·실장비 시험과 후속 개발을 구분합니다.
이 환경의 제품 소스는 `.tools/kestra-publish` 작업 트리이며, 부모 checkout의 미커밋 변경을 배포 완료로 취급하지 않습니다.
아래 상태는 실제 Kubernetes·Argo CD·오프로딩 API를 읽어 확인했습니다. 문서 갱신 중 서비스를 재배포하거나 부하 시험을 다시 실행하지 않았습니다.

## 배포

- 콘솔: `http://10.254.192.47:13080/ui/main/offload-lab`. 13080은 Kubernetes `edgeai-dashboard`의 hostPort, 13083은 별도 개발 서버입니다.
- API·콘솔: 각각 1/1 Ready. Argo CD `edgeai-dev`: main `42dc498`, Synced/Healthy(이 문서 수정 전 조회값).
- 통합 소스 `1b8fb80`, 배포 설정 `fa5daa9`에 GPU 시험과 기존 AI 모델 관리 기능을 함께 반영했습니다. 이미지 기준은 [release.json](deploy/kubernetes/overlays/dev/release.json)입니다.
- API imageID: `10.254.192.47:5000/edgeai-api@sha256:2850765450c4bc657dc724c7df762518d8f8cffd1fada3a6dce3471a4f98f889`.
- 콘솔 imageID: `10.254.192.47:5000/edgeai-dashboard@sha256:bd2fdf3f6651a487522561d5de030e4ca20715c4837c601911b899a89fc35a0e`.
- Runtime은 활성화돼 있고 실행 허용 워크플로는 `hawkeye-video-safety,offload-lab`입니다. 모든 워크플로를 무제한 실행하도록 연 것은 아닙니다.
- GPU 객체 검출·GPU 이미지 비교·hawkeye-motion 런타임은 사용자 요청대로 replicas=0입니다. Hailo NPU 이미지 비교 런타임은 1/1 Ready입니다. 이미지 비교 전체가 현재 실행 가능하다는 뜻은 아닙니다.

## 최근 구현·검증 범위

| 기능 | 현재 상태 | 근거·남은 범위 |
|---|---|---|
| CPU/GPU 오프로딩 시험 | 웹·API·부하 Job 배포, 실제 edge→worker→edge 왕복 성공 | [가이드](docs/guides/offload-lab.md). GPU 시험은 99.1%·99.9% 연속 표본으로 `NODE_GPU`, 부하 중지 후 0%·0%로 `NODE_RETURN` 성공. 각 위치의 모델 Pod UID가 다름 |
| 시험 모델 | UCI digits 손글씨 숫자 분류 MLP 64-32-10, CPU 추론 | 별도 CUDA Job이 GPU 압력을 만듦. GPU 모델 추론·상태 보존 이전·GPU 성능 평가를 입증한 시험은 아님 |
| AI 모델 관리 | Registry 이미지 digest·플랫폼 검증, 불변 모델 등록·목록·상세, 자원 요구량 저장 배포 | [가이드](docs/guides/ai-model-catalog.md). SERVICE 자동 발행·워크플로 실행 연결·자원 예약은 후속 |
| 서비스 자원 프로파일 | 3-3 ② 웹/API·V70·Prometheus·KRR bridge 구현·배포 | [가이드](docs/guides/resource-profiling.md), [기존 배포 근거](docs/evidence/resource-profiling-20261030.md). 자원 분할·보장·자동 증강은 별도 |
| NPU | Hailo 자원·추론 경로, Intel·Mobilint 사용률 수집 존재 | NPU 부하 버튼·`NPU_USAGE` 전환/복귀 정책은 미구현. Hailo 사용률 계측도 별도 필요 |

GPU 시험의 정책은 시작 전에 선택한 값으로 Run에 고정되며 새로고침 후에도 유지됩니다.
기본 시험값은 과부하 50%, 복귀 20%, 연속 2개, 최소 실행 20초, 전환 후 대기 30초, 최대 전환 2회입니다.
운영 정책 기본값과 구분합니다. 상세 실장비 기록은 가이드에 있고, 해당 검증 세션은 모델·부하를 모두 중지하고 Job·Pod 정리를 확인했습니다.

## 검증과 한계

- GPU 기능의 기존 검증: Runner 12/12, gateway 5/5, 격리 DB 통합 11/11, UI 24/24, typecheck·lint·계약·문서 검사 통과.
- AI 모델 관리와 병합한 최종 소스: gateway 시험·bootJar·native typecheck·OpenAPI 생성물 일치·콘솔 release 빌드 통과. 변경하지 않은 통합 시험은 기존 근거를 재사용했습니다.
- 실제 웹에서 GPU 선택·새로고침 유지·부하 시작/중지·모델 왕복·최종 중지 확인, 브라우저 오류 0건. 이는 지정 장비의 기능 시험이며 전체 모델·장치·규모·장기 안정성 수용이 아닙니다.
- 별도 Astra 리뷰 워커는 `agent-update-prompt`로 실행되지 않았습니다. 프로젝트 지침에 따라 조정자가 실제 diff·시험 결과를 검토했고, 이전 CPU 시험이 GPU로 잘못 표시되던 결함은 Claude가 수정했습니다.
- ALL_OBSERVATIONS와 기존 센서/VD·STREAM의 이전 통합 기록은 기능별 문서와 Git 이력을 따릅니다. 이번 CPU/GPU 시험을 해당 경로의 전체 운영 수용 근거로 확대하지 않습니다.

## 다음 개발

[PLAN.md](PLAN.md)의 2-1·3-3 범위에 따라 NPU 계측·부하·전환, 모델 예약/우선순위/이상 롤백, 등록 모델의 실행 연결을 각각 다룹니다.
UI 디자인·구현 담당은 전역 `~/.codex/AGENTS.md`의 Claude Code / Opus이며 Codex는 조정·검증·배포를 맡습니다.

과거 날짜별 작업 내역은 [이전 PROGRESS의 Git 이력](https://github.com/dsa04156/edgeai/blob/42dc4988b63ad34e431c4f4b5e86f48cc1a2e227/PROGRESS.md)에서 확인합니다.
과거 digest·장비 수·활성화 상태를 현재 상태로 해석하지 않습니다.
