# M9 복원 STREAM 그룹 업무 상태 검증

2026-10-05, ADR0092. `20261004T160510Z-580c6a40` 실제18개 PASS/exit0.
원시 개인 보고서는 `.tools/recovery-stream-workflows-final.json`이다.
선행9개 `155102Z-67434b99`, 확장17개 `155909Z-4e0ff77d` 뒤 기존 BATCH 명령의
Device-only STREAM 구분 오류를 재현하고 수정한 최종 실행이다.

공개 API로 같은 Device→STREAM Task2→BATCH child를 생성했다. 실제 Kubernetes 컨테이너
부모/자식2쌍의 Job/Pod/node UID를 DB에 명시적 fixture로 연결했다. 합성 업무이며 실제
Runner claim handshake·모델 실행 수용을 주장하지 않는다. runtime/Attempt/retry 상태는
fixture이고 모든 DB 제약과 불변 trigger는 활성화했다. PostgreSQL16에5개를 복원한 뒤
원본 DB를 삭제하고 실제 부모/자식 종료·TLS broker 기존 연결 회수를 수행했다.

소비자 Journal에서 DATA/END를 처리한 실제 checkpoint2개를 versioned TLS MinIO에 저장했다.
별도 MinIO로 고정 version을 복제한 뒤 원본 저장소를 종료했다. 복원 DB의 checkpoint receipt는
명시적 SQL fixture이며 최종에 replica의 원래2개 version·bytes·SHA를 다시 확인했다.

최종18개가 확인한 경계:

- 같은 Device fanout을 한 그룹으로 계산하고 가장 이른 시작 기반의 공유 cutoff와 peer의
  STREAM_GROUP_RESTART를 보존했다. 기한 전 두 retry queue는 변경하지 않았다.
- 일부 producer 미확인·열린 generation·granted finalization·활성 offload에서는 그룹을
  분할 처리하지 않았다. BATCH offload 분류도 Device 경로를 보고 미해결로 남겼다.
- 잘못된 개별 cutoff/backoff, plan 이후 실제 DB 변경, 최종 관측 이후 SQL 직전 쓰기,
  실제 DB marker 교체를 거절했다. 별도 psql이 completion 테이블을 잠그면5초 안에 실패했다.
- 전체 갱신 후 실제 SQL 오류를 주입하자 만료 처리와 후손 변경이 함께 rollback됐다.
- 기한이 지난 그룹2개 Task를 FAILED로 만들고 BATCH child1개를 SKIPPED, Run을 FAILED로
  조정했다. 반복 실행에서는0변경이며 새 Attempt를 만들지 않았다.
- 기록된 취소2개를 확정하면서 원래 FAILED Attempt·취소 이유를 보존했다. 실제 COMMIT
  이후 응답 유실에서도 재실행이 기존 상태를 보존했다.
- DB의 경로 종료 기록이 있어도 원본 broker를 재활성화하면 사전/사후 검사가 성공을
  반환하지 않았다. 같은 복구 UUID의 차단을 복구하고 다시 관측할 수 있었다.
- 각 상태 변경에서 다른40개 테이블과 실제 checkpoint2개·고정 S3 version2개를 보존했다.
  소유 namespace·DB·API·MinIO·broker·MQTT client·잠금 프로세스 정리를 확인했다.

기존 BATCH 명령 오류의 실제 재현은 `20261004T160309Z-dbd25b95` FAIL이다.
STREAM task_dependency가 없는 공개 Device fanout에서 경로를 닫기 전 Task2개를 취소했다
(`databaseModified=true`, 미해결0). 원인은 dependency만 검사하던 STREAM 판별이다.
Kubernetes/Remote 업무 복구와 offload 판별에 data_route를 추가하고 write guard·잠금에도
포함했다. 최종18개에서 같은 명령은 변경0/취소0/미해결2를 반환했다.

영향받는 실제 Remote Result15개 `160510Z-69a92427`, Remote 실패15개 `160510Z-d2fec081`도
PASS다. Kubernetes/VD/Remote 혼합69개도 `161320Z-48dd1604` PASS다.

혼합69개 최초 회귀 `160510Z-95bbd2e6`는 unclaimed target 부모/자식 준비 단계에서 실패했다.
진단을 보강한 `160944Z-5dbe85b5`도 같은 단계에서 실패했고 실행 중 실제 Pod의
`Failed/Evicted`와 DiskPressure를 관측했다. 당시 노드는 Ready=True와 DiskPressure=True를
동시에 반환했다. 소유 자원 정리는 두 실행 모두 확인했다. 개인 관측은
`.tools/recovery-stream-regression-node-pressure.json`에 보존했다.

시험의 명시적 NODE 대상은 Ready 외에도 Disk/Memory/PID pressure=False와 scheduling
taint/unschedulable 조건을 확인하고, 건강 상태를 더 오래 유지한 노드를 우선하도록 수정했다.
노드·taint·공유 서비스 설정은 변경하지 않았다. 실패 시 소유 namespace의 UID/label 확인 뒤
제한된 Pod phase/reason/condition/container 종료 코드만 보존하며 로그·메시지·환경변수는
노출하지 않는다. 보완 후 전체 혼합69개 `161320Z-48dd1604`가 PASS했다. 실제 부모/자식5쌍,
복원DB6개·양방향 Remote 전환·원래 재시도 정책·고정 S3 결과·미기록 시작 권한 차단과
namespace/DB/API/Remote/결과 저장소의 소유 자원 정리를 확인했다.

JAR SHA256 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7` 및
V1–V34·Runner 코드는 불변이다. 기존 Runner111/MQTT97 근거를 재사용한다.
CI kind에18개 게이트를 추가하고 해당 CI의 정확한 Runner digest/API JAR/검증 MinIO를
입력하도록 했다. Compose PostgreSQL17·packaged 이미지의 새 CI/배포 검증은 후속이다.

기존 c133315 CI37213721652의 완료3jobs 원시32개·PG230·Runner111/MQTT97·Device11/
결합47개 부분 감사 `160737Z-4f2c931b`는 PASS이며 신규18개 구현을 포함하지 않는다.
원본 모든 writer/시작 권한 회수·새 Secret/grant·STREAM offload/finalization·종합 복구 활성화와
M5 잔여/M7–M10 전체 수용은 남는다. activated/globalQuiescenceProven은 false다.
