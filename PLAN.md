# 개발 단계와 다음 게이트

현재 작업 범위는 센서 관리, SERVICE 워크플로와 중앙 eKuiper 기반 연속 가상 센서입니다.
구형 VD BATCH·supervisor는 종료됐습니다. M0–M10의 과거 완료 기록을 현재 지원 기능으로 해석하지 않으며 최신 배포 상태는 [PROGRESS.md](PROGRESS.md)를 확인합니다.

## 우선순위

1. 센서·가상 센서·실행 모듈·워크플로의 등록과 관측 화면을 유지합니다. 등록은 모달이며 측정 항목별 센서 행·CRD를 보존합니다.
2. 연속 처리의 운영 배포를 준비합니다. 현재 flag는 false이고 eKuiper 설치만으로 입력·규칙·결과 저장을 활성화하지 않습니다.
3. 실제 센서에서 중앙 계산과 출력 이력까지의 경로를 확인한 뒤 운영 활성화를 진행합니다.
4. Runner/일반 STREAM 고도화, 실모델·외부 연동·성능·종합 복구 수용은 별도 게이트로 유지합니다.

## 연속 처리 다음 게이트

| 단계 | 확인할 결과 |
|---|---|
| Mapper 입력 | 실제 source identity·설정 digest·단조 세션 순번을 갖는 qualified observation 수신 |
| 배포 준비 | processing Agent 이미지 digest, 기존 eKuiper Helm/PVC 소유, TLS·credential·RBAC·NetworkPolicy, 전용 history와 용량 확인 |
| 비활성 배포 | 기존 센서·broker·일반 서비스 유지, flag=false를 활성화 완료로 표시하지 않음 |
| 활성화 | flag=true 이후 실제 엔진 lease·규칙·입력 수신·출력 generation과 저장 확인 |
| 실제 센서 수용 | 새 센서/여러 프로토콜, stale 입력·설정 변경·재시작·중복/옛 세션 거절, 결과별 행·그래프·누락 구간 확인 |
| 운영 수용 | 24시간 실제 soak, 결과 전달 p95≤2초·정상 입력 손실/중복·queue/메모리·실제 저장량 확인 |
| 되돌리기 | flag=false 이후 lease 만료·소유 규칙 정리, 센서 CRD·등록·history/PVC 보존 |

구체적인 명령과 조건은 [연속 센서 가이드](docs/guides/vd-processing.md)를 따릅니다. 원본 MQTT 이력·재전송과 계산 결과 이력은 다른 저장 경로입니다.

## 별도 잔여 범위

- 실제 GPU/NPU 학습 모델·외부 Remote/2세부 API 계약과 종단 추론 수용
- 상태형 checkpoint 복원, 일반 STREAM 전체 조합과 새 Mapper 입력의 자동 연결
- 사용자별 identity/RBAC, 외부 감사·보관, 전체 서비스 재가동과 합의 성능 기준

설계·과거 수용 조건은 [구현 계약](docs/architecture/implementation-contract.md), [검증 기준](docs/testing/verification-matrix.md), [운영·복구 요구사항](docs/requirements/m9-requirements.md)에서 해당 날짜와 지원 범위를 확인합니다.
