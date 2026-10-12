# 센서·처리 선택 등록 검증 — 2026-10-12

요청: 공통 실행 이미지를 준비하고 사용자가 센서·측정 항목·처리 기능을 선택하여 가상 장치를 등록한다. 명세는 `docs/requirements/vd-processing-registration.md`, 사용자 안내는 `docs/guides/vd-processing-registration.md`다.

## 제공 결과

- 기존 개발 URL `http://192.168.0.56:13080/virtual-devices?create=1`에 기본 등록을 반영했다. 기존 API18080을 새 코드로 재시작했고 readiness와 Dashboard proxy의 processing-catalog가 모두200, `ready:true`, `architectures:[amd64]`였다.
- 센서와 숫자 항목을 선택하고 필터·이동평균·RMS·FFT·임계값을 순서와 파라미터로 조합한다. SERVICE/VD 프로필과 이미지는 자동 결정한다. 고급 템플릿 등록은 유지한다.
- immutable 요청 digest를 V53에 저장하고 키별 PostgreSQL 트랜잭션 잠금 후 replay를 확인한다. 센서 소실·해제·이미지 변경·서로 다른 이미지 설정 서버의 동시 호출에도 완료된 동일 요청은 같은 VD를 반환한다.
- 작업 브랜치 `feat/vd-processing`의 코드 검토 기준은 `8763316...f56558f`다. 기존 dirty 개발 작업공간에는 변경76개와 필요한 이전 센서 BATCH 의존 파일20개만 적용하고 파일 원본과 개발 DB dump를 작업 소유의 ignored output에 보관했다. Git reset·main commit/merge·push를 하지 않았다.

## 실제 실행 이미지

```text
192.168.0.56:5000/edgeai/processing-runner@sha256:4223426bcfa7f37e1d23e8bbb6d8f48da962f76869cc9d261230a85eea4b4619
```

- Linux amd64에서 rootless Docker로 runner/Dockerfile을 빌드했다. runner 소스 기준8763316이며 이후 커밋은 등록 API/UI다.
- 실제 이미지 configuration은 `sha256:f29f2c1bd2f4100560691b25afdc07adb36d0a99f02cfbdb1f215d57dce9f9dd`다. 컨테이너에서 `edgeai_runner.processing` import를 확인했다.
- Runner unittest121개가123.622초에 통과했다. Docker Fixture 실행을 활성화했으며 readonly/cap-drop/no-new-privileges/tmpfs 격리 및 자원 측정을 포함한다. 전체121개가 모두 컨테이너 시험이라는 뜻은 아니다.
- 검증한 이미지를 LAN registry에 발행하고 레지스트리에서 manifest bytes의 SHA256와 image configuration의 linux/amd64를 다시 확인했다. local `.env`에는 이 digest와 `amd64`만 설정했다.

## 통합 후 확인

| 확인 | 결과 |
| --- | --- |
| backend 전체 unit |193 passed, 0 failure/error/skipped |
| isolated PostgreSQL integration |259 passed, 0 failure/error/skipped, 55초 |
| 신규 등록 API integration |6 passed; 전체259개에 포함 |
| OpenAPI 생성 일치·Swagger 패키징·계약 controller |pass |
| Dashboard typecheck/lint/build |pass; 기존 작업공간 build에201개 문서 패키징 |
| Desktop/mobile Playwright |18 passed, 21.2초 |
| 기존 개발 API/readiness/catalog |200, UP, ready:true |
| 실제 LAN UI 등록 |Sense HAT/temp_humidity/threshold →201, 상세와 단일 원본 바인딩 확인 |
| 실제 생성된 SERVICE |발행 digest, python3 -m edgeai_runner.processing, architectures:[amd64] 일치 |
| 실제 mobile |movingAverage/표본3/창3 선택, viewport390px=document390px |

실제 UI 검증 등록 ID `e1e43e5e-6cb3-4992-aa2f-e9711b196317`은 해제200 후 등록 삭제204로 정리했다. 원본 센서/Mapper는 변경하지 않았다. 공통 content-addressed SERVICE/VD 프로필은 정상 재사용 가능한 상태로 남는다. 이 확인은 등록 검증이며 처리 Pod를 실행하거나 센서값을 처리한 실클러스터 검증은 아니다.

TDD: 다중 파이프라인 삭제→추가의 중복 이름, catalog 연결 실패 상태/재시도, 이미지 교체 동시 등록의 VD_CONFLICT를 실패 상태로 확인한 뒤 수정했다. null6필드, 잘못된 측정 항목/창의 전체 트랜잭션 rollback, CSRF, replay, alias 불변성·삭제 cascade 회귀도 포함한다.

재현 명령은 `rtk proxy`로 실행했다. backend integration은 기존 `.env`를 읽은 다음 작업 브랜치의 `scripts/test/isolated-db.sh`로 disposable DB를 만들었다. Docker tests는 작업 소유 DOCKER_HOST와 rootless network namespace를 사용했다. 원시 수치와 스크린샷은 ignored `output/processing`에 있다.

## Standards

`e81b141...f56558f` 읽기 전용 검토에서 **새 문서 규칙 위반이나 차단할 정확성 문제는 발견하지 못했습니다.**

- 처리 키 잠금을 재전송 조회 전에 획득하고, 요청 신원 저장까지 동일 트랜잭션에서 유지합니다. 서로 다른 이미지 설정을 가진 서버의 동시 요청도 첫 생성의 VD를 재사용하므로 ADR0013의 멱등성 규칙을 지킵니다.
- PostgreSQL 잠금 구현은 adapters, 저장소 계약은 domain, 트랜잭션 조합은 app에 있어 계층 규칙을 충족합니다.
- 동시성 회귀는 실제 트랜잭션 두 개에서 동일 VD, 한 번의 신규 생성, 단일 원본 바인딩을 확인합니다.

이전 재전송·이름 중복·카탈로그 오류 구분·null 입력 지적은 해결 상태를 유지합니다. Map 중심 구성은 비차단 유지보수 판단이며 추가 추상화를 요구하지 않습니다. 전달받은 실행 검증 수치는 재실행하지 않았습니다. 이번 판정은 코드와 회귀 시험 검토에 근거합니다.

## Spec

**현재 f56558f에서 남은 코드 지적은 없습니다.** 이전 지적은 모두 해결됐습니다.

- **재전송 정책:** 불변 요청 digest와 선행 replay가 센서 삭제·이미지 교체 뒤에도 기존 등록을 반환합니다.
- **동시 이미지 교체 경합:** 키별 PostgreSQL 트랜잭션 잠금이 조회부터 프로필 결정·VD 생성·요청 기록까지 보호합니다. 서로 다른 이미지 설정의 동시 호출을 검증하는 테스트가 추가됐습니다.
- **다중 처리 편집:** 파이프라인 삭제 후 추가해도 고유 이름을 생성합니다.

명세의 API 원자성, 숫자 항목 제한, 프로필 재사용, 실패 시 선택 유지, 저장 성공과 조회 실패 구분, 준비 상태와 연결 오류 구분은 구현과 일치합니다. 범위 확대도 찾지 못했습니다.

리뷰 당시 개발 통합의 컴파일·재시작·실화면 연결은 pending이었다. 이후 루트가 위 통합 후 확인 표의 검증을 완료했다. 두 리뷰어는 시험 수치를 독립 재실행하지 않았다.

리뷰 요약: Standards 잔여 필수 지적0, 비차단 Map 유지보수 판단1. Spec 잔여 지적0.

## 남은 실행 범위

현재 공통 이미지는 native amd64만 제공한다. ARM 센서를 원본으로 사용하고 호환 amd64 노드에서 처리할 수 있지만, ARM 실행 이미지의 native 검증은 별도다. 기존 개발 환경의 `edgeai.vd.enabled`/runtime/Kubernetes 실행 플래그는 유지했다. 클러스터의 처리 Pod 실행, 새 Mapper 이미지 배포, canonical GHCR multiarch release, 운영 GitOps 변경은 수행하지 않았다.
