# M5 Remote 플랫폼 연결 — 영속 상태와 결과의 로컬 검증

범위는 ADR0011/V10–V11, RemoteRepository, RuntimeLifecycleService의 Remote 경로,
ArtifactCommitService와 결과 API/OpenAPI/화면이다. M0–M4 완료 판정은 유지하며 M5는 진행 중이다.
이 기록의 source6009136에서 공개 Run/Offload API의 실행 선택은 AUTO/NODE다. REMOTE fixture는 내부 저장소로 생성하고
시험이 명시적으로 제공자 호출을 수행한다. 자동 Remote worker나 실제 외부 시스템 수용시험은 아니다.
후속 자동 worker·공개 REMOTE 선택은 [ADR0012 검증](m5-remote-worker.md)에 따로 기록한다.

## 직접 확인한 증거

| testRunId (2026-10-02) | 범위와 결과 |
|---|---|
| 20261002T122419Z-28045200 | 실제 PostgreSQL80개, Remote11개 포함. PASS/0, failures/errors/skipped0 |
| 20261002T122941Z-b5593b6c | 실제 MinIO+PostgreSQL3개. 기존 Kubernetes 결과/취소2개와 참조 Remote BATCH1개. PASS/0 |
| 20261002T123458Z-c7b84c50 | 단위/MVC58개, 새 Remote 결과 DTO 포함. PASS/0, failures/errors/skipped0 |
| 20261002T122608Z-67793d94 | OpenAPI 타입 생성·MVC19·패키징 계약 일치. PASS/0 |
| 20261002T122326Z-bda6bf99 | Dashboard lint/typecheck/build·PC/모바일22개. PASS/0 |
| 20261002T122608Z-7f9fd9ed | 결과 상세 펼침·Remote ID 가시성 보강 후 PC/모바일2개. PASS/0 |
| 20261002T123523Z-2f3da4ae | 실제 API/DB PC·모바일8개, Swagger, DB 중단503와 동일 API/UI 프로세스 복구. PASS/0 |

결과 화면의 PC/모바일 스크린샷에서 참조 계산 표시와 RemoteAllocation ID를 직접 확인했다.
ID·체크섬·파일 버전의 긴 값은 모바일에서 줄바꿈하며 가로 넘침 시험도 통과했다.
실제 MinIO 시험은 프로젝트의 소스 빌드 바이너리를 소유한 loopback 임시 서버로 실행했다.
시험 종료 후 해당 서버와 임시 저장소를 제거했으며 프로젝트 PostgreSQL은 유지했다.
재현 명령은 `scripts/test/test-integration.sh`, `test-runtime-results.sh`, `test-unit.sh`,
`test-contract.sh`, `test-ui.sh`, `test-profiles-stack.sh local`이다. MinIO/DB가 필요한 시험은 실제 저장소를 요구한다.

## 상태·신원·경쟁 조건

- RemoteAllocation과 Runtime은1:1이며 제공자 key/설정 digest/sourceMode/요청/마감은 불변이다.
  동시8개 계획은 같은 Runtime·할당·CREATE로 수렴한다. Remote는 Job/Pod/Node UID를 갖지 않는다.
  Kubernetes와 Remote의 활성 runtime·명령 lease 조회를 분리하고 다른 종류의 claim/종료 요청을 차단한다.
- 관측은 allocation/run/task/attempt/epoch와 고정 요청 digest·마감·sourceMode를 대조한다.
  역순 revision은 무시하며 같은 revision의 다른 내용, 상태 역행, terminal 변경과 다른 실행 신원을 거절한다.
  실제 Python 참조 제공자의 reserve digest와 플랫폼의 고정 요청 digest도 일치한다.
- 제공자의 SUCCEEDED만으로 Result를 만들지 않는다. SERVICE 출력 포트·크기·형식과 관측 SHA를 확인하고,
  실제 S3 내용 검증 뒤 Run 잠금에서 현재 producer/Attempt/epoch/lease/취소를 다시 검사한다.
  동시8개 commit은 하나의 봉인된 Result와 하위 Attempt만 만든다.
- 저장소 검증을 대기시켜도 취소는 진행된다. 취소 뒤 돌아온 검증과 이전 Attempt의 유효했던 commit
  허가는 새 Attempt 시작 이후 거절된다. 하위 작업도 잘못 해제하지 않는다.
- reserve 이전 취소는 영속 tombstone 관측으로 종료한다. 이미 관측한 예약이 사라졌다는 null-digest
  tombstone은 거절한다. 재시도는 실제 종료와 미완료 CREATE 처리가 끝날 때까지 기다린다.
- 제공자 재시작 실패는 RUNTIME_LOST 재시도로 연결하고 새 Attempt/epoch/할당을 만든다.
  제공자 binding은 이전 할당에서 유지한다. 마감 만료는 결과를 차단하고 실제 terminal 관측을 기다린다.
- DB 제약은 Pod와 Remote producer를 섞은 결과, 가짜 Pod의 Remote 결과, 불변 할당 변경을 거절한다.
  현재 결과 조회는 RemoteAllocation ID와 SYNTHETIC/EXTERNAL 구분을 반환한다.

## 실제 파일의 Remote BATCH

별도 Python/SQLite 제공자가 features=[2,1], weights=[2,3], bias=1을 계산해 score8을 생성한다.
HTTP로 내려받은 실제 출력의 SHA를 검증하고 서명된 PUT으로 실제 MinIO에 저장한다. S3 adapter가
내용/버전을 다시 확인한 뒤 PostgreSQL Result를 확정한다. 다음 작업은 고정 object version을 실제
GET으로 받아 Remote에 전송한다. child parameters의 [99,99]보다 선행 입력이 우선해 다시 score8이며,
출력 SHA도 일치한다. 두 Result 모두 Pod UID는 null이고 Run은 SUCCEEDED가 된다.

이 과정은 실제 HTTP/S3/DB 데이터 경로다. 시험이 호출 순서를 직접 수행하므로 자동 명령 처리,
worker 재시작 복구, 공개 API로 시작한 Remote 실행까지 검증했다고 해석하지 않는다.

## 발견한 회귀와 수정

V10의 저장 생성 컬럼을 추가하자 기존 V5 BEFORE UPDATE 봉인 trigger가 기존 Kubernetes 결과도
거절했다(20261002T121720Z-8bbdbc75, PostgreSQL77개 중8개 실패). PostgreSQL은 저장 생성 컬럼을
BEFORE trigger 뒤에 계산하므로 해당 시점의 NEW.producer_kind를 기존 값과 비교할 수 없다.
[PostgreSQL16 생성 컬럼 문서](https://www.postgresql.org/docs/16/ddl-generated-columns.html)를 확인했다.

이미 적용한 V10을 보존하고 V11에서 봉인 trigger가 생성 컬럼과 전환 대상 committed를 제외한 모든
원본 컬럼을 비교하게 했다. 신원·manifest 등 원본은 여전히 불변이고 검증 artifact가 있어야 봉인된다.
수정 후77개(20261002T121911Z-a3877c2a), 추가 늦은 결과/lease 시험 포함80개 모두 통과했다.
후속 fixture 컴파일 실패(20261002T122326Z-6042bf48)는 결과 서비스와 지역변수의 이름 충돌을 수정했다.

## 최초 검증 시 남은 연결

1. Remote worker의 CREATE/DELETE lease, 관측/재조정, 고정 입력 전송·출력 저장, 재시작 복구.
2. 최초 공개 Run/Offload 요청부터 하위 작업·재시도·전환까지 유지하는 제공자 선택과 설정 digest 검증.
3. 공개 REMOTE 선택 API/화면, Kubernetes↔Remote 전환·취소·장애의 자동 종단 시험과 CI/실제 배포 확인.
4. 실제 외부 endpoint/auth/계약 정합화, 상태형 복원과 실장비/모델 수용. SYNTHETIC 계산은 대체 증거가 아니다.

1–3의 worker·공개 선택·binding 및 로컬 자동 실행은 후속 ADR0012에 구현했다. 실제 kind Remote 종단과
외부 수용은 남아 있다. 이전 참조 adapter0143094의 검증은 `m5-remote-adapter.md`에 기록한다.

## 후속 CI·실제 배포 확인

source `6009136ab70ffc58a3d11e6cab559ae5f79dff11`의
[Actions37008176219](https://github.com/dsa04156/edgeai/actions/runs/37008176219)는
scaffold/storage/runner/images/gitops 5 jobs 모두 success다. 내려받은 네 artifact 그룹의
result.json15개 모두 PASS/exit0이며 실제 Kubernetes Runner/recovery 게이트도 통과했다.

`20261002T130758Z-54ea0071`은 실제 API/Dashboard/MinIO imageID가 GitOps75c6632에 기록한
6009136 이미지 digest와 일치함을 확인했다. Ready/PVCBound/ArgoSynced도 PASS다.
이는 신규 Remote worker 이전 영속 연결 버전의 배포 확인이다. Argo aggregate health는 기존
공유 Traefik/Ingress status 공백으로 Progressing이며 공유 설정은 변경하지 않았다.
전체 플랫폼 LOCAL_VERIFIED/FULL_ACCEPTANCE로 판정하지 않는다.
