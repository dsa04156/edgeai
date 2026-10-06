# Kubernetes 시작 기록의 복원 전환 대조 검증

[ADR0102](../adr/0102-recovery-kubernetes-start-journals.md)의 최종 결합 시험
`20261004T205153Z-094ba230`은90개/exit0 PASS다. 기존 Remote 시작 기록79개 회귀와
Kubernetes 시작 기록11개 확장을 함께 실행했다. 시험 종료 후 소유 namespace·DB·API·
Remote 제공자와 두 종류 MinIO 시험 프로세스의 정리를 확인했다.

```bash
bash scripts/test/test-recovery-kubernetes-retire.sh \
  --context kubernetes-admin@kubernetes \
  --runner-image ghcr.io/dsa04156/edgeai-runner@sha256:8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff \
  --runner-source 60c8be3e5bd0970ea83d15941ba3ec6761b8a240 \
  --vd-tasks --workflows --offloads --unclaimed-jobs \
  --remote-offloads --remote-start-receipts --runtime-start-journals \
  --report .tools/recovery-runtime-starts.json
```

Kubernetes의 작업/claim/전환 및 시작 허가는 명시적 fixture다. 실제 Runner claim 프로토콜의
전체 복원 종단 시험이라고 주장하지 않는다. 실제 Kubernetes 컨테이너의 실행/자식 종료·
Pod/노드 신원, PostgreSQL dump/restore·transaction, HTTPS MinIO의 조건부 저장·서로 다른
설치 간 고정 version 복제·원본 제거는 실제 경로다. 정상 API claim의 S3 기록 생성은
[ADR0101 근거](m9-kubernetes-start-journal.md)가 별도로 검증한다.

- 시작 전 DB snapshot을 만들고 이후 source의 claim·전환 성공을 기록한다. 별도 DB2개를
  이전 snapshot으로 복원한 뒤 시작 기록의 원본 DB와 MinIO 데이터 디렉터리를 제거했다.
- 고정 S3 기록1개를 실제 보존 Pod/controller/노드, 복원 작업 digest·lease·전환 기한과
  대조했다. 이미 기한이 지났어도 원래 기한 안의 허가를 전환 성공1개로 조정했다.
- 신원/epoch/작업/lease/원래 전환·기한/허가 시각/schema14종 관측 변경을 거절했다.
- 실제 삭제 marker·누락 manifest에서는 시작 실패를 만들지 않았다. 동일 바이트의 새로운
  실제 S3 version도 원래 캡처 버전을 대체하지 못했다.
- 실제 DB의 기한·취소 이유·새 Attempt, 최종 관측 후 쓰기 경쟁, 실제 SQL trigger 오류를
  거절하거나 원복했다. 커밋 응답 유실 뒤 intent를 유지하고 재실행 변경0을 확인했다.
- 전환 이외42개 테이블과 원래 배치·기한·source 이력을 보존했다. 새 claim0/Result0이며
  `activated=false`다. 시작 허가가 작업 성공이나 결과 확정의 증거가 되지 않는다.

작업 해시는 현재 JAR의 실제 Java JsonDocuments와17개 입력을 비교했다. 큰 정수/정밀한
소수/지수·음수0, UTF-16 순서가 다른 키·한글/보조 문자·제어 문자, 중복 JSON/잘못된
Unicode/깊이/숫자 범위/trailing JSON을 포함한다. 독립 실행
`20261004T204456Z-c8c0c596`과 최종 결합 실행 모두 PASS다.

공통 transaction 회귀는 실제 Remote 실패15개 `20261004T205154Z-38c5569a`,
실제 Remote 결과15개 `20261004T205155Z-9a779d98` 모두 PASS/소유 정리다.
사용한 API JAR SHA256은 `85e001ab79e72f5cda2d65fb5e29e9ff0cb63dc533d17fbe122b981d201bc1f8`이다.
이번 변경은 Python 복구 경로이며 JAR/DB migration은 바꾸지 않았다.

초기 `20261004T204852Z-149edcaf`는 기존55개 후 첫 신규 대조에서 실패했다. 저장 종료 기록의
Pod name/UID를 포함한 형태와 컨테이너 종료 정보만 반환하는 함수의 형태를 그대로 비교한
오류였다. 동일한 기록 형태로 정렬하고 신원 검사를 유지한 최종90개가 통과했다.
초기 실패에서도 소유 namespace/DB/API/저장소를 정리했다.

kind CI에90개 경로를 연결했다. 새 CI/배포는 별도이며, 실제 API가 생성한 기록부터의 전체
복원 종단, Kubernetes Result 확정·VD 독립 기록·STREAM 그룹의 시작 권한, 전역 writer
차단·새 권한·종합 활성화 및 전체 M9 수용은 남는다.

## 실제 API가 만든 시작 기록

위 명령에 `--runtime-start-api`를 추가하면 S3 시작 기록을 직접 주입하지 않고 실제
패키징된 API의 HTTPS claim으로 생성한다. Kubernetes 신원 검증도 mock하지 않는다.
기존 런타임/배치 이력은 명시적 DB fixture이며 업무 계산이나 결과 생성의 증거는 아니다.

기본68개 `20261004T210516Z-76adc3e9`는 PASS/exit0이다. 실제 API·MinIO·namespace·DB를
정리했으며, 원본 API 종료·시작 기록의 실제 API 생성 및 복원 대조를 보고서로 확인했다.

- 별도 소유 시험 DB를 fixture DB에서 복제하고, claim 전의 DB snapshot을 보관한다.
  격리된 복원 DB의 활성화 설정을 해제하는 방식은 사용하지 않는다.
- bootstrapped control-plane 서비스 계정의10분 토큰과 소유 namespace 안의 Pod/Job GET
  RoleBinding을 사용한다. 클러스터 관리자 인증서를 API에 제공하지 않는다.
- 실제 실행 중인 Pod에 묶인 `edgeai-runner` audience 토큰을 발급해 실제 TokenReview와
  Pod/Job/controller/노드 조회로 검증한다. 시험 클라이언트가 직접 claim을 호출하며
  실제 Runner 업무 프로세스가 HTTP 요청을 보냈다고 주장하지 않는다.
- HTTPS API에서 잘못된 Pod proof는401이고 S3 기록이 없다. 실제 proof의 claim200 후
  DB producer/노드·전환 성공과 S3 기록을 확인한다. 두 번째 claim200은 같은 원래
  version과 바이트를 유지한다.
- API를 종료한 뒤 별도 TLS MinIO에 원래 version을 복제하고 원본 저장 디렉터리와
  source DB를 제거한다. claim 전 DB2개를 복원하고 실제 Pod 종료 후 ADR0102 경로를
  사용한다. 실제 API의 나노초 시각·Java 작업 digest·원래 기한이 복원 대조를 통과한다.

최종 Remote 포함91개 `20261004T210855Z-97f6fc1e`도 PASS/exit0이다. 정상 반영은 실제
복구 CLI로 실행했고 모든 파라미터·개인 intent/SQL/report 및 재실행 변경0을 확인했다.
고정 version의 실제 원본 바이트를 읽어 동일 바이트의 새 version을 만들어 교체 거절을
검증했다. 명시적 fixture의14종 변형, 실제 DB 경쟁·원복·커밋 응답 유실도 포함한다.
소유 namespace·DB·API·Remote/MinIO 정리 및 짧은 수명의 API 인증 파일 삭제를 확인했다.

현재 API JAR 및 production 복구 코드는 위90개 검증 때와 동일하다. 이 확장은 시험의
시작 허가 생성 경로를 실제 API로 바꾼 것이다. kind CI에도91개를 연결했다.
선행 소스8b964d6 CI37234177387의 native3jobs/원시4개·각Runner111/MQTT97과 실제
registry의 amd64/arm64 manifest를 `20261004T211207Z-b74ae060`에서 감사했다.
전체 CI·새 배포·신규91개 원격 실행은 아직 확인하지 않았다. 선행 CI 종료 전 후속 push는
대기한다. Kubernetes Result·실제 Runner 업무부터의 종합 복구·STREAM/VD·활성화는 남는다.
