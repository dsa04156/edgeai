# 진행 상태

[STATUS]
상속 finalizer 결과 복원을 Kubernetes35개021900Z-a9a73602/VD39개021504Z-64750523에서
검증했다. 실제 실패2회·시작 기록3개·종료 Pod3개와 원래 grant/checkpoint1개를 연결하고,
상속12종/원래 정책4종 모순 거절·원래 결과/실패 이력·원복/응답 유실·소유 정리를 확인했다.
기존 STREAM30/34·BATCH20/24도 같은 소스에서 PASS, 최종182개 감사023758Z-5ef4cd70 PASS.
[근거](docs/evidence/m9-recovery-stream-finalizer-results.md). 재시도 배정/VD readiness는
명시적 fixture다. 최초 fixture 오류2개와 초기 claim503 원인 미확정/후속 PASS를 구분한다.
선행d764480 CI37250820250 7jobs/원시41개 감사023533Z-6e61b5e8 PASS.
GitOps5ba6ccc 실제 이미지/Ready/ArgoSynced023304Z-fc338e78·기존10파일/PVC/HTTPS 보존
023400Z-ed3013b9·배포V36 적용023830Z-e73ac5fc PASS. aggregate health는Progressing이다.
[CI/배포 근거](docs/evidence/m9-stream-start-ci.md). 새 STREAM 결과/상속 finalizer의
원격 CI·배포, 혼합그룹 전체 CLI·누락 완료 권한/실행·전역 writer/API 차단·종합 활성화와
M0–M10 전체 목표는 계속 진행한다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0109 STREAM 결과 복원 Kubernetes30개014427Z-a9a2630f/VD34개014427Z-4b52b8e4 PASS.
원래 완료 허가·고정 checkpoint bytes/실행 계약·broker/producer 차단·복원DB6/타38테이블·
원래 결과 ID/시각·후속 이력·경쟁/원복/COMMIT 응답 유실과 소유 정리를 확인했다.
실제 두 번째 member 오류에서 먼저 생성된 Attempt도 원복됐다.
[근거](docs/evidence/m9-recovery-stream-results.md). 초기 fixture 오류와 실제 Run/그룹 경계
오류를 구분해 수정했으며 Remote DROP DATABASE 지연 원인은 미확정으로 보존한다.
기존 BATCH20개014718Z-70cf4f72/VD24개015106Z-5be66d33/Remote15개015249Z-26341551도
PASS/소유 정리다. 최종123개 감사015347Z-967e0db4 PASS. 새 CI gate를 연결했으며
push는 진행 중인 선행 CI37250820250 종료 뒤 진행한다. 새 원격 CI/배포는 미검증이다.
상속 finalizer/혼합그룹 전체 CLI 수용·누락 완료 권한/실행·전역 writer/API 차단·종합 활성화와
전체 M0–M10 목표는 미완료다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0108 NODE/VD STREAM 그룹 시작 복원55개010404Z-5743138b PASS.
원래 API 시작 기록2·복원DB21·기존43테이블·원복/경쟁/COMMIT응답 유실·소유 정리를 확인했다.
모든 member의 원래 허가가 있을 때만 전환 성공을 복원하고, 누락된 허가는 실패로 추론하지 않는다.
기존 Kube 결과20개010839Z-139f2712·VD 결과24개011238Z-0d2351e5·시작 복구91개
010218Z-e0126123도 PASS. [근거](docs/evidence/m9-recovery-stream-starts.md).
시험 클라이언트 수명 오류 수정 후 실제 post-commit S3 교체/거절을 확인했다.
초기 HTTP/Pod/TLS 기동 실패 원인 미확정·선행CI37245085073 실패/gitops 생략은 보존한다.
새55개 kind gate를 연결했으며 신규 원격 CI/배포는 후속 확인한다.
STREAM 결과/finalization 권한·미기록 실행·전역 writer/API 차단·종합 활성화와 전체 단계는 미완료다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0107 VD BATCH 결과 복원24개001119Z-2e34d057 PASS. 원래 배정/세션·시작/결과·실제
종료·고정 S3 출력을 대조해 복원DB5개에서 원래 결과/시각/producer·자식 대기를 반영했다.
경쟁/원복/응답 유실·타38테이블/VD이력·소유정리를 확인했다.
[근거](docs/evidence/m9-recovery-vd-results.md). 기존Kube20개001319Z-48d6d047와
Remote15개001459Z-362633f0 PASS. 기존 시작 복구91개001546Z-842f567c도 PASS/소유 정리다.
첫 fixture lease 오류와 후속 DB정리 시간 초과를 보존하고, 정리 원인 해결을 주장하지 않는다.
선행edc9625 CI37245085073은5jobs/원시35개·native index 감사 PASS, images 진행 중이다.
새 VD복원 코드는 이 원격 CI 범위에 없으며 새24개 gate/전체 CI·배포는 후속이다.
VD 전환 시작 조정·STREAM 권한·전역 writer/API 차단·종합 활성화와 전체 단계는 미완료다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0106 VD 확정 결과를 원래 배정/세션·고정 출력과 함께 독립 S3 기록으로 보존한다.
V36 영속 발행 큐·종료 뒤 재발행·rollback/응답 유실/동시 쓰기와 실제 Python Runner를 포함한
대상20개231739Z-b6c02814 PASS. 실Kube7개233237Z-411e4f13에서VD Result16/Node2·출력18·
pending0·소유정리 PASS. [근거](docs/evidence/m9-vd-task-result-journal.md).
PG232/runtime79/저장소11개231954Z-ea0c46d7와단위122개232535Z-740799bc는개별통과다.
전체 명령은 단위 다운로드 시간 초과1개로FAIL이며 최초Kube 실패/분리재검증과 원인 미확정을 보존한다.
V36백업13개233839Z-eb4b1056·참조9개233924Z-6092f907·기존Result복원20개234127Z-1fa39195 PASS.
선행077d139 CI37239863156 7jobs/원시40개·GitOps c6d12ce 실제배포/원래데이터보존 PASS.
[CI 근거](docs/evidence/m9-kubernetes-result-recovery-ci.md). 새VD변경의CI/배포·복원 소비/STREAM권한·
전역writer/API 차단·종합 활성화와 전체 M0–M10 수용은 미완료다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0105 VD 자식의 최초 허가를 성공 응답 전에 S3에 보존한다. 같은 Pod의 별도 배정,
heartbeat/drain·동시 요청·응답 유실·취소/만료를 대상8개223308Z-da0e87ed에서 검증했다.
실 Kubernetes7개224349Z-50a87186 PASS: VD 기록27개/Node2개·고정 출력18개·소유정리.
[근거](docs/evidence/m9-vd-task-start-journal.md). 최종 각 수트 단위122/PG232/runtime70개
225433Z-3ccee0f9·저장소11개230128Z-847a8732 PASS. 전체 명령은 SQLite export timeout으로
FAIL이며 단일 전체 통과가 아니다. 초기 STREAM/DB 대기 실패·관련29개 재검증 통과와
WALSync 지연 관측을 보존하고 원인 해결을 주장하지 않는다. 선행077d139 CI37239863156은5jobs 성공/images 진행 중.
새 VD 변경은 미push이며 VD 결과/복원 권한·전역writer/종합 활성화와 전체 단계는 미완료다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0104의 Kubernetes BATCH Result 복원을 구현했다. 원래 시작/결과 기록·실제 종료·
고정 S3 파일을 대조해 원래 ID/시각/producer 이력과 자식 대기를 한 transaction에 반영한다.
새20개221111Z-8496de2b·Remote 결과15개221124Z-a42d00d4/실패15개221659Z-66ca50d8·
기존 실제 API 시작 기록 복구91개221700Z-599e0412 PASS/소유 정리.
[근거](docs/evidence/m9-recovery-kubernetes-results.md). 초기 Remote fence 일시 실패의 원인은
미확정이며 재실행 통과/진단 보완과 구분한다. STREAM/VD 권한 소비·전역 writer/API 차단·
종합 활성화·실모델/외부계약과 M0–M10 전체 목표는 유지한다.
선행8b CI37234177387은55분 제한으로 images 취소/gitops 생략. 원시38개 중37PASS,
STREAM 복구42/44 후 FAIL. [근거](docs/evidence/m9-ci-duration-limit.md). 전체 job 제한90분과
새20개 CI gate를 연결했으며 신규 원격CI/배포는 후속 확인한다.

아래는 선행 구현·검증 이력과 당시 상태다.

ADR0102 실제 API 확장: TLS claim·실제 Pod-bound TokenReview가 생성한 S3 기록을
별도 백업/원본 제거→복원DB/보존 Pod→복구 CLI에 연결했다. 기본68개210516Z-76adc3e9,
최종 혼합91개210855Z-97f6fc1e PASS/소유 정리. claim 재요청은 최초 version 보존,
복원 전환1/claim0/Result0·42테이블·경쟁/원복/응답 유실·재실행0을 확인했다.
[근거](docs/evidence/m9-recovery-kubernetes-start-journals.md). DB/배치 이력은 fixture이며
실제 Runner 계산/Result·STREAM/VD/전역writer·종합 활성화와 전체 단계는 미완료다.
선행8b964d6 CI37234177387의 native3jobs/원시4개·registry 두manifest 감사
211207Z-b74ae060 PASS. scaffold/storage는 진행 중이며 신규91개 push는 선행 CI 종료 뒤다.

아래는 선행 구현·검증 이력과 당시 상태다.
ADR0102 Kubernetes 시작 기록 복구: 별도 S3 백업의 원래 허가·작업 digest·기한·실제
보존 Pod 종료를 복원 DB와 대조해 BATCH 전환만 조정한다. 최종 결합90개
205153Z-094ba230·Java해시17입력·Remote 실패15개205154Z-38c5569a/결과15개
205155Z-9a779d98 PASS/소유 정리. [근거](docs/evidence/m9-recovery-kubernetes-start-journals.md).
시작 허가는 명시적 fixture이며 실제 정상 claim 생성은 ADR0101에서 별도 검증했다.
원래 claim/Result·42테이블을 보존한다. 실제 API부터 복원 종단·Kubernetes Result·
STREAM/VD/전역writer·종합 활성화와 전체 단계 수용은 미완료다.
선행 f66c4cd CI37228787573은7jobs/원시39개 감사204930Z-a54f4f06 PASS.
GitOps f6bed7d 실제 이미지/Ready/ArgoSynced204946Z-537c82b8·기존10파일/PVC/HTTPS
보존205024Z-b715bfc8 PASS. 새 ADR0100–0102 변경의 CI/배포와 구분한다.

아래는 선행 구현·검증 이력과 당시 상태다.
ADR0101 Kubernetes 시작 기록: 최초 허가를 S3 조건부 쓰기/고정 version 재검증 후
성공 응답하며 재요청은 원래 시각/기한을 보존한다. 실제 API/PG/S3의 응답 유실·
저장소 복귀·취소·변조 거절, 전체 회귀201849Z-7a15a836 단위122/PG232/runtime53/
storage11개 PASS/소유정리. [근거](docs/evidence/m9-kubernetes-start-journal.md).
실제 Kubernetes3개202525Z-305738ce도10Pod/시작기록10/고정S3결과6·API교체·소유정리 PASS.
계약202358Z-55730dde/패키징202504Z-9ed703bc PASS. 복원 기록 소비/종합 활성화와 전체단계 미완료.
선행 f66c4cd CI37228787573은5jobs성공/images진행 중이며 신규 변경은 미push다.

아래는 선행 구현·검증 이력이다.
ADR0100 Remote 시작 기록 복구: 실제79개195042Z-fa32af0a PASS. 복원DB6/부모자식5쌍·
기한 내 실제 접수→전환 성공1→고정S3 Result1, 최신시도/취소/기한변경/응답유실·재실행0·
42테이블/소유정리 확인. 기존 실패15개194916Z-94803bf0·결과15개195043Z-d0759979 PASS.
[근거](docs/evidence/m9-recovery-remote-start-receipts.md). kind79개 연결·새CI/배포는 후속,
Kubernetes/STREAM 권한·전역writer·종합활성화/전체단계 미완료.
선행 f66c4cd CI37228787573 진행 중이며 새 변경의 push는 해당 CI 종료 후 진행한다.
완료4jobs의 원시23개·단위122/PG230/Remote24+13/native각111+97 부분 감사
195601Z-44a7cf78 PASS. 전체CI·새API/GitOps배포 미확인.

아래는 선행 구현·검증 이력이다.
ADR0099 Remote 시작 권한 기록: API worker·참조 제공자의 원래 기한 확인과 계산 전
영속 접수 기록 구현. Python24개/Java HTTP연동13개192155Z-4c060e15 PASS.
공개 API/PG/S3 결합48개192333Z-a87e3730 PASS/소유정리. 단위122개192819Z-56af45a4·
계약192914Z-3de1b62b도 PASS,
새원격CI/복원전환반영·종합활성화 미완료.
로컬 ADR0097 최종 저장 재시도 복구를 추가, 기본26개 `184620Z-1eb595e3` PASS.
실제복원DB7/고정S32개·원래grant/실패/기한 보존·경쟁/원복/응답유실·소유정리 확인.
전환 포함44개 `185953Z-272d4e46` PASS: 복원DB18/부모자식4쌍·고정S32개·
최종처리8개/만료1/새Attempt0·실제COMMIT응답유실·소유정리를 확인했다.
ADR0098 S3 소유target 명시적resync: 느린scanner의 수정 전 timeout 재현 후
백업11개 `185831Z-0be128f4`와 위44개 결합 회귀 PASS.
새코드원격CI·종합활성화는 미완료다.
선행60c8be3 CI37223827513은5jobs성공/images실패/gitops생략으로 종료했다.
원시39개 중37개 PASS. STREAM복구case0에서 S3고정버전 복제대기120초 실패이며
소유자원/kind정리 확인. 감사193318Z-cfe2641f PASS, 전체CI성공은 아니다.
ADR0098 적용 전 소스다. [실패 근거](docs/evidence/m9-stream-recovery-ci-timeout.md).

선행main소스60c8be3 CI37223827513: native ARM/x86와index발행3jobs 성공.
`182346Z-6cf4c91c` 원시4개/각Runner111·MQTT97/두manifest 감사 PASS.
새index8a2f8067의 공개 ARM서버GPU 실행·S3결과·소유정리 PASS. 같은
`182404Z-6448ff9a`의 엣지GPU는 가용노드부재로 BLOCKED, 전체명령 exit2.
ARM 공개 STREAM AUTO/NODE/cancel `182458Z-f434c88e` 8Pod/S36개·소유정리 PASS.
엣지 연결 복귀 뒤 Jetson GPU 공개실행 `182709Z-151e67f8` 1Pod/S3결과/정리 PASS.
엣지전용 STREAM AUTO/NODE/cancel `182820Z-f547a037` Jetson8Pod/S36개/정리 PASS.
전체CI/새API·GitOps배포·장치단절복구·실제모델 수용은 아직 완료되지 않았다.
아래는 이 확인에 앞선 검증 이력이다.

ADR0096의 요청별 broker 응답 수명 수정: 기존80회 콜백NPE1개 확인 후 같은80회
`180704Z-af18f0f5` 예외0/PASS, 최종broker14개 `181144Z-36506086` PASS/정리.
최종 단위122개 `181439Z-4e038d42` PASS.
신규 main native index/CI/배포 확인은 남는다.
선행2d86e9e CI37218040065 전체5jobs/원시36개·STREAM복구29개 감사 `180330Z-08a26c13`
PASS. e93b3ad 실제이미지/Ready/ArgoSynced `180330Z-f67f9bf1`, 기존10파일/PVC/HTTPS
보존 `180845Z-e5fda2fe` PASS. 새 native 이미지·broker수정의 원격 수용과는 구분한다.
M10 [공개 API 하드웨어 실행](docs/evidence/m10-hardware-runtime.md)2개 `175547Z-fb453bb8`
PASS: 실제CUDA계산/ARIES접근·Run2/Pod2/고정S3결과2·소유runtime정리.
후속branch CI37221887410의 broker 권한 경합 REVOKED/INVALID_RESPONSE 실패는 위 수정의 출발점이다.
새 native index 발행·ARM 공개 실행·실제모델/NPU추론은 아직 완료되지 않았다.
M10 최종 전체7개 `174327Z-583d0e77` PASS. 선행 전체 재실행의 GPU 노드 DiskPressure
퇴거 실패는 보존하며, 건강 상태 변경 시각을 기준으로 안정적인 후보를 먼저 선택한다.
branch CI4개 성공/3개 생략·원시35개/PG230/단위122 감사 `174542Z-e978bc85` PASS.
architecture 선택 공개 데모의 기존amd648Pod/S36개 `174227Z-17088dc7` PASS.
native index 발행·새 배포·ARM 공개 API 데모는 남는다.
M10 [실장비 구성 요소](docs/evidence/m10-hardware-components.md)7개 PASS:
서버amd64/arm64 CPU·RTX5080/GB10 CUDA·ARIES 비루트 접근 `173534Z-ccbf4ed5`,
KubeEdge Tinker CPU/Orin CUDA `173721Z-fcb13cc9`. 고정 이미지·실제 계산·UID·소유 정리 확인.
과거 Ready4 관측은 현재 상태가 아니다. NPU 추론·공개 API 실제 모델/외부 계약·전체 수용은 남는다.
ADR0095 native amd64/arm64 각각 Runner111/MQTT97·원시 감사 `173754Z-71f9acbd` PASS.
소스6853fd87의 branch 검증이며 새 index 발행·실제 kind/배포는 후속이다.

ADR0094 기록된 target 실패 포함36개 `170123Z-963eee1a` PASS. 실제 복원DB16/부모자식4쌍·
선택/peer 실패2·원래 실패/전환/체크포인트 이력 보존·retry0·원복/응답 유실·소유 정리 확인.
불일치 실패 이력을 기본 취소로 우회하는 오류를 재현·수정했다.
[근거](docs/evidence/m9-recorded-stream-target-failures.md). 선행29개는2d86e9e로push됐고
CI37218040065가 진행 중이다. 새36개 원격 검증·미기록 실패/시작 권한/최종 처리·종합 활성화는 남는다.

선행 c133315 CI37213721652 전체5jobs/원시35개 `164536Z-08054170` PASS.
GitOps11958d6 정확한 이미지·Ready/ArgoSynced `164522Z-268fbe82`, 기존10파일/원래PVC·
HTTPS 보존 `164612Z-dbe48fdd` PASS다. 새 ADR0092–0093을 pin 위로 rebase했으며
검증한469ffeb와 pin2개 외 변경 없음. 새 STREAM 복구29개의 원격 CI/배포는 별도 확인한다.

ADR0093 전환 포함29개 `163446Z-71726657` PASS. 실제 source2→successor2 부모/자식·
복원DB13개·종료/시작 기한 만료2·취소2·새 실행/재시도 없음·원래 source Attempt/runtime,
member/checkpoint와 고정 배치/기한 보존·경쟁/원복/응답 유실·소유 정리를 확인했다.
[근거](docs/evidence/m9-recovery-stream-offloads.md). 기존18개 회귀도 포함한다.
CI kind29개 연결. VD/자동 전환의 복원 종단·기록된 target 실패/시작 권한·최종 처리·전역 writer와
종합 활성화·전체 목표는 미완료다. 새코드는 로컬 커밋 완료이며 원격 검증은 후속이다.

ADR0092 실제 STREAM 그룹 복구18개 `160510Z-580c6a40` PASS. 공개 Device fanout2/복원DB5·
실제 컨테이너 종료·TLS broker·원래 그룹 기한/취소·checkpoint2와S3고정version2 보존,
DB경쟁/잠금/원복/응답 유실을 확인했다. 기존 BATCH 명령의 Device-only STREAM 우회를
실제 재현 후 수정했다. [근거](docs/evidence/m9-recovery-stream-workflows.md).
Remote Result15개 `160510Z-69a92427`, 실패15개 `160510Z-d2fec081` 회귀 PASS.
혼합69개 `161320Z-48dd1604`도 PASS. 실제 DiskPressure 퇴거를 관측한 뒤 시험 노드 선택과
실패 관측을 보완했고 소유 자원을 정리했다. 새CI18개 gate 연결·미push,
STREAM 전환/최종 처리·종합 활성화·전체 수용은 남는다.

선행 c133315 CI37213721652 완료3jobs의 원시32개·PG230·Runner111/MQTT97·Device11/
결합47개 부분 감사 `160737Z-4f2c931b` PASS. images/실제 Kubernetes는 진행 중이며
ADR0092의 새18개나 새 배포 근거로 사용하지 않는다.

선행42c4094 CI37209512541 전체5jobs/원시34개 PASS(`153553Z-573e4203`). GitOps6d876f5의
정확한 API/dashboard/MinIO 이미지·Ready/PVC/ArgoSynced(`153534Z-5f51c056`)와 기존10파일/
원래PVC·HTTPS256KiB/익명403/소유probe정리(`153651Z-90a91c7f`)도 PASS다.
공유Ingress aggregatehealth는Progressing이다. ADR0089–0091의 새47개/MQTT97 기능과는
구분하며, pin변경 위rebase 후 테스트한 소스와pin2개만 다른 것을 확인했다.

ADR0091 실제 원본 broker 차단→복원 STREAM generation 종료 결합47개
`152904Z-e8228c9f` PASS. 새14개는 복원DB5·경로4개 종료·기존 취소 이유/시각·다른broker1개/
42개 타 테이블 보존, 실제 잠금/SQL guard 경쟁/rollback/COMMIT응답 유실·권한 재활성화·
0변경 재실행과 소유 정리를 검증했다. [근거](docs/evidence/m9-recovery-stream-retirement.md).
기존 장치33개 회귀 포함. 경로 상태만 조정하며 Task/Run·checkpoint·격리는 보존한다.
새 CI/배포·STREAM 그룹 결과/재시도/전환·종합 활성화·M5 잔여/M7–M10 전체 수용은 남는다.

ADR0090 원본 Device source 종료11개 `150639Z-87144f5a`, 실제 복원 DB4/DB·TLS S3/MQTT·
원본 journal 결합33개 `151649Z-82a21d30` PASS. Runner111 `151238Z-82901393`, MQTT97
`151238Z-d7b91f12`, 백업13 `151238Z-fca4c959`도 PASS다. 원본 마지막 프레임 보존·잠금
해제/재점유·다른 UUID·inode 교체·transaction 중단·검사 중 실제 쓰기/원본 유실·격리와
소유 자원 정리를 확인했다. [근거](docs/evidence/m9-device-source-retirement.md).
증명은 LOCAL journal owner 해제이며 물리/전역 producer 종료와 활성화는 false다.
새 CI/배포·다른 producer/API 자격·새 권한/종합 복구·M5 잔여/M7–M10 전체 수용은 남는다.

ADR0089 복원 장치 데이터·원본 MQTT 권한 결합 검증 27개 `144757Z-420384e3` PASS.
실제 공개 API/복원 DB 4개·TLS MinIO 복제/원본 종료·실제 MQTT 연결 회수·고정 checkpoint
파싱과 DB summary 대조·계정/관리자 복구·관측 경쟁·파일 삭제 거절·43개 테이블/파일 보존과
소유 프로세스/DB/client 정리를 확인했다. [근거](docs/evidence/m9-device-recovery-authority.md).
자동 재개는 하지 않으며 원본 Device 프로세스 종료·다른 producer/API 권한·새 Secret/grant·
종합 활성화와 M5 잔여/M7–M10 전체 수용은 남는다. 새 27개 CI gate/배포는 미확인이다.

ADR0088 장치 journal/DB 대조 실제 16개 `142409Z-b11d1b61`, 백업 회귀 13개
`142719Z-0da4249f` PASS. 공개 STREAM API·복원 DB 3개·원본 DB/source 삭제·43개 테이블과
파일 보존·순번 유실/세션/경로/END/전환 충돌·실제 관측 경쟁·격리 유지·소유 정리를 확인했다.
[근거](docs/evidence/m9-recovery-device-journal.md). 제품 활성화는 하지 않으며 checkpoint
바이트/원본 종료/브로커 권한의 종합 확인과 M5 잔여/M7–M10은 미완료다.

선행 fd8830d CI37204890109는 5 jobs/원시 32개 PASS (`142230Z-e8e25801`). GitOps4a13ec1의
정확한 이미지/Ready/PVC/Argo Synced (`142255Z-670ebdc4`)와 기존 파일 10개·PVC UID·HTTPS
256KiB/익명403/소유 object 정리 (`142314Z-d47d0242`)도 PASS. 공유 Ingress의 aggregate
Progressing은 유지한다. ADR0085–0088 변경의 새 CI/배포 검증과 구분한다.

ADR0087 장치 journal의 일관 snapshot·암호화·격리 복원 실제13개140112Z-41923f08 PASS.
Runner111개135456Z-4855a9b2·TLS MQTT95개140012Z-b4fa7f1d PASS. 원본 볼륨 삭제 뒤
fanout/cursor/state/END·완료 intent 보존, 별도 writer8snapshot·큰 payload·실SIGKILL·자동
송신 차단·소유 프로세스 정리 확인. [근거](docs/evidence/m9-device-journal-backup.md).
새 CI gate 연결. 새 이미지/배포·현재 DB와 journal/권한 대조·종합 활성화는 미완료다.

ADR0086 혼합69개134212Z-7fca0d0d·Result15개134221Z-9f81f337·실패15개134221Z-e1e9e102
PASS. 실제 Remote target 실패2/재시도1·원래 예산·원복/응답 유실/변경0을 확인했다.
실제 S3 성공이 STARTING 전환을 우회하는 문제를 재현 후 차단하고 전환 삽입 경쟁도 검증했다.
소유 namespace/DB/API/Remote/MinIO 정리 완료. [근거](docs/evidence/m9-recovery-remote-offload-outcomes.md).
kind69개 및 검증 이미지 MinIO 추출 연결. 새 CI/배포·시작 권한 journal·STREAM/group·
종합 복구 활성화·전체 목표는 미완료다.

ADR0085 Kubernetes/참조Remote 혼합64개132134Z-7df3eacf PASS. 복원DB6·부모자식5쌍,
실제Remote할당4/실행2종료·양방향시작기한만료2/취소1·성공1보존·22테이블경쟁/원복/
응답유실/변경0·소유정리. Remote 결과14개132150Z-a211a1f9/실패15개132149Z-1ac3b571
PASS. [근거](docs/evidence/m9-recovery-mixed-remote-offloads.md). kind64개 게이트 연결,
새CI/배포·종합결과/STREAM/group/journal/활성화·전체목표는 미완료다.

ADR0084 claim 전 Job 복구의 실제56개131023Z-96d5d08f PASS. 복원DB5/부모자식4쌍,
Job의 보존자식2개·빈Job미해결, 원래claim/node NULL·sourceOFFLOADED/배치/기한 보존,
실제STARTING 만료1·원복/응답유실/변경0·소유정리 확인. kind56개 게이트 연결.
[근거](docs/evidence/m9-recovery-unclaimed-jobs.md). 새CI/배포·종합복구·전체목표는 미완료다.

새5a313e1 CI37203679354의 storage는 정책 JSON 원문 순서 비교에서 실패했다.
격리 TLS MinIO40회 조회에서 원문2종/내용동일을 재현하고 의미 비교로 수정했다.
실제25개 전체130621Z-a3eb3fd3 PASS·소유 프로세스 정리 확인.
[근거](docs/evidence/m9-storage-policy-order.md). 새 소스 CI/배포 검증은 후속이다.

선행 be41a8b CI37200100790 전체5jobs/원시32개 PASS(125138Z-05565ce2), GitOps2f88205
실제API/dashboard/MinIO imageID·Ready/PVC/ArgoSynced(125137Z-ff9020e5), 이후 원래10파일/
PVCUID·TLS256KiB/익명403·probe정리(125229Z-73489f8b) PASS. 공유Ingress health Progressing 유지.
docs/evidence/m9-kubernetes-recovery-ci.md. ADR0081–0083의 새46개 CI/배포 근거와 구분한다.

ADR0083 BATCH offload 복구를 포함한 실제46개(124649Z-b0e9247f)와 Remote 공통 회귀15개
(124502Z-1302d13a) PASS. 취소2·drain 만료2·원본 OFFLOADED/고정 target/39테이블 보존,
source claim 거절·미기록 target/새 epoch 미해결, 경쟁/마지막 쓰기 원복/실COMMIT응답 유실·
변경0·소유 자원 정리를 확인했다. docs/evidence/m9-recovery-batch-offloads.md.
kind를46개로 확장했다. 새 CI/배포와 claim 전 target/Remote/STREAM/group/checkpoint/journal·
전역 writer·종합 활성화·M5 잔여/M7–M10 전체 수용은 남는다.

ADR0082 복원 Kubernetes/VD 작업 상태 조정37개(122910Z-58941ebc)와 공통 Remote 회귀15개
(122911Z-4233255d) PASS. 기록된 취소2·원래 기한 만료1·후손2·Run5,39테이블 보존,
결과 미기록/미배정·기존 결과/새 시도·원래 예약 보존, 실제 잠금/경쟁/원복/COMMIT응답 유실·
변경0 재실행 및 소유 자원 정리 확인. docs/evidence/m9-recovery-kubernetes-workflows.md.
kind 게이트를37개로 확장했다. 새 CI/배포·결과 회수·활성 offload·STREAM/journal·전역 writer·
종합 활성화·M5 잔여/M7–M10은 남는다. 선행 be41a8b CI37200100790의 완료3jobs/원시29개·
PG230·Remote14/15 부분 감사122426Z-83ac6073 PASS; 이번 확장은 선행 CI에 포함되지 않는다.

ADR0081 VD 내부 Task runtime/할당 정리23개(120355Z-1f71afa5), 기존16개 회귀
(115733Z-64d93a90) PASS. 복원DB4·부모/자식3쌍·할당이력6종, 실행3/할당3 정리·과거closure2/
확정Result2/37테이블·미배정 보존, allocation경쟁/잠금/원복·COMMIT응답유실·소유자원정리.
docs/evidence/m9-recovery-vd-tasks.md. 선행 be41a8b CI37200100790에는 이번 확장이 없다.
새CI/배포·작업결과/offload/STREAM/journal·전역writer·종합활성화·M5잔여/M7–M10은 남는다.

ADR0080 실제 Kubernetes 종료→복원 DB 정리16개(114126Z-5ef20866) PASS.
복원DB3·컨테이너/자식2쌍·never-bound1, runtime/VD각1·명령4·binding1,
guard/실잠금/rollback·응답 유실·미기록UID/404·38테이블 보존·소유자원 정리 확인.
docs/evidence/m9-recovery-kubernetes-retirement.md. 새kind게이트·VD Task/offload/STREAM/journal·
종합 활성화와 M5 잔여/M7–M10 전체 수용은 남는다.
선행 cd1424a CI37196161670은5jobs/원시29개 PASS(114232Z-47e37693)다.
ADR0078–0080의 새 CI/배포는 이 선행 결과와 구분한다.
GitOps760908b 실제 imageID/Ready/PVC/ArgoSynced(114725Z-53fcdb3b), 그 뒤 기존10파일/
두PVC·TLS 보존(114806Z-497eac42) PASS. 공유Ingress aggregatehealthProgressing 유지.

ADR0079 복원 Remote 실패·취소·재시도 대기의 실제15개(111817Z-b771e368) PASS다.
실패5·원래 기한 예약2·취소2·후손2·완료Run5, SQLrollback/잠금/응답 유실·새 시도/기존 사유/
38테이블 보존과 기한 만료를 확인했다. 기존 Result14개(110815Z-f311735c) 회귀도 PASS다.
docs/evidence/m9-recovery-remote-failures.md. 새 CI/배포·offload·STREAM/journal·종합 활성화는 남는다.

ADR0078 복원 Remote Result·Task·Run 확정의 실제 결합14개(105412Z-07ca4178) PASS다.
원본 DB/Remote/S3 종료 뒤 결과2·완료Run1·대기자식1, 동시 복구·잠금·rollback·커밋 응답 유실·
Java digest/기존38테이블 보존·API 조회/쓰기 차단을 확인했다. 새 runtime/명령은 만들지 않는다.
docs/evidence/m9-recovery-remote-results.md. 새 CI/배포·실패/취소 작업·STREAM/journal·종합 활성화는 남는다.
선행 cd1424a CI37196161670의3jobs/원시27개·PG230·Remote17/S312개 PASS를
확인했다(105951Z-05a4f945). images가 진행 중이므로 새 변경의 push는 완료 뒤 진행한다.

선행 bf1ba48의 CI37192473886은5jobs/원시27개 PASS(103252Z-838f3a6f)다.
GitOps354c94e 실제 imageID·Ready/PVC·Argo Synced(103223Z-dd541b3a), 새 MinIO 기동 뒤
기존10파일/두PVC·TLS 보존(103449Z-2a468828)도 PASS다. 공유 Ingress health는 Progressing이다.
ADR0075–0077의 새 CI/배포 결과는 이 선행 검증과 구분한다.

ADR0077 Remote 복구 파일의 조건부 S3 등록·고정 version 검증을 추가했다. 실제 TLS MinIO12개
(102527Z-eead97ca) PASS, 입력은 실제 PG/Remote17개(101935Z-11915ad3)의 회수 묶음이다.
응답 유실/재실행·동시 쓰기1version·기존3개 version 보존·재시작·충돌/유실·대상 거절을 확인했다.
docs/evidence/m9-recovery-remote-storage.md. 같은 CI storage job에서 묶음 생성→저장소 검증을
연결했으며 새 CI/배포·DB 결과 확정·journal·종합 활성화는 후속이다.

ADR0076 Remote 미반영 성공 파일의 실제 bytes 회수·독립 검증을 추가했다. PG16/TLS 결합17개
(100728Z-2813d1a2)·Python17/Java gateway13개(100728Z-f6d01f72) PASS다. 원본 파일 변조/경로·응답 경계·
DB 경쟁·제공자 종료/DB 자격 없는 검증·43테이블/제공자 이력 보존을 확인했다.
docs/evidence/m9-recovery-remote-outputs.md. S3 등록·고정 version·결과/Task/Run 확정과 종합
활성화는 남는다. 새 CI/배포는 후속이며 선행 실행과 구분한다.

ADR0075 복원 Remote 관측·runtime·기존 명령의 원자적 정리를 추가했다. 실제 PG16/TLS13개
(095215Z-59a4cda4), 기존 이력10개 회귀(095133Z-eaa68841) PASS다. 실제 DB 경쟁·marker 변경·
잠금 제한·중간 오류 rollback·커밋 응답 유실·0변경 재실행·43테이블/확정 결과/다른 DB 보존과
일반 API 기동 격리를 확인했다. docs/evidence/m9-recovery-remote-retirement.md.
새 Compose17 CI gate의 원격 확인, workflow 결과 확정·미반영 성공 파일·journal·종합 활성화는 남는다.

ADR0074 복원 DB/참조 Remote 전체 이력 점검을 추가했다. 실제 PG/TLS10개
(092539Z-1d04c194)·Python14/Java gateway13개(092408Z-ed5d7eed) PASS다.
백업 후 할당·양쪽 누락·binding/신원/요청/관측 충돌·페이지 누락/중복·43테이블/제공자 보존을
검증했으며 Compose17 CI 게이트를 추가했다. docs/evidence/m9-recovery-remote-inventory.md.
DB 상태 조정·장치 journal·외부 업체 종료 계약·종합 활성화는 남는다.
선행9caa7dd CI37188905983의5jobs/원시26개 PASS(092606Z-b89a28f6), GitOpsd351a3b의
정확한imageID/Ready/PVC/ArgoSynced(092608Z-eeb39b40)·기존10파일/두PVC/TLS보존
(092659Z-cff29cb6) PASS다. 공유Ingress aggregatehealthProgressing이며 새 복구 변경의 CI/배포와 구분한다.

ADR0073 참조 Remote 제공자 전체 차단·계산 종료 검증을 추가했다. 실제 TLS7개
PASS(090831Z-b7537085), Python 전체12개·Java gateway13개·별도 PG/MinIO27개도 통과했다.
별도 운영 자격/설치 UUID·늦은 인증 예약/입력 차단·실제 worker 종료·완료 파일/이력 보존·
SIGKILL 재시작·응답 유실/같은 ID 재개·기존 DB 업그레이드·timeout 차단 유지를 확인했다.
docs/evidence/m9-recovery-remote-fence.md. 기존 CI test-remote 게이트에 포함되며 새 원격
CI/배포는 후속이다. 실제 외부 계약·복원 DB 조정·장치 journal·전체 복구 활성화는 남는다.

ADR0072 원본 S3 요청 소진 검증을 추가했다. 실제 PUT2개가 root 차단 후에도 진행하며,
하나는200/64KiB 저장 완료·다른 하나는 연결 종료로 끝나는 것을 확인했다. 새 counter의0 관측
두 번만 성공으로 인정하고 오래된0/누락/혼합server/여러server/timeout을 거절한다.
기존 root차단18개 포함25개 PASS(083947Z-c4e158e0), 원래2버전과 늦은 완료1버전 보존·정리 확인.
docs/evidence/m9-recovery-storage-drain.md. 단일 S3 요청의 소진이며 전역 writer 중지·활성화는 미완료다.
선행9caa7dd CI37188905983은 scaffold/storage/runner 성공·원시24개와PG230/S3차단18/
MQTT차단15·35계정/Runner111/MQTT95를 확인했다. images 실제Kubernetes 게이트 진행 중이며
이번 새 변경의 원격 검증/배포는 후속이다. 아래는 선행 구성 요소 이력이다.

ADR0071 원본 S3 root 접근 차단18개 실제 TLS 검증 PASS(082045Z-c2caa5f1).
기존 PUT/GET URL 거절·고정2버전/bytes 보존·사용자 생성/차단 직후 중단과 같은 ID 재개·
SIGKILL 재시작·환경변수 override·무관한 IAM/정책 보존을 확인했다. 새 CI storage 게이트 추가.
docs/evidence/m9-recovery-storage-fence.md. 진행 중 업로드 종료·전역 writer 중지·복원 활성화는 남는다.
선행5ed7b03 CI37185328851은5jobs/원시24개 PASS(081857Z-83bd1448).
GitOps8cf5a23 정확한imageID·Ready/PVC·ArgoSynced(082000Z-1b9a0d9a), 기존10파일/두PVC·
TLS/S3보존(082000Z-565ea715)도 PASS다. 공유Ingress aggregatehealthProgressing.
새 MQTT15/S318개 게이트는 이 선행 CI에 포함되지 않으며 후속 확인한다. 전체goal/M5잔여/
M7/M8성능/M9전체/M10실장비수용은 미완료다. 아래는 구성 요소별 선행 이력이다.

ADR0070 원본 MQTT 차단15개 실제 TLS 회귀 PASS(075002Z-74d8ac1a).
관리자 교체/기존 연결 종료·35계정/실연결2개 차단·재접속 거절·중단/같은 ID 재개·역할 보존·
broker SIGKILL 재시작과 소유 프로세스/연결 정리를 확인했다. 새 CI gate를 연결했다.
docs/evidence/m9-recovery-mqtt-fence.md. journal·Remote/S3 writer·복원 활성화/M9전체는 남는다.
선행5ed7b03 CI37185328851은3jobs/원시22개·PG230·Runner111·MQTT95·Compose DB fence10개
PASS 확인, 이후 images/GitOps와 배포는 위에서 검증했다. 새 MQTT복구15개 CI/배포는 후속이다.

ADR0069 원본 DB 연결 차단10개 실제 PG/API 회귀 PASS(065711Z-4d27de4e).
기존 연결3개 종료·새 연결 거절·미완료 쓰기 rollback·다른 DB 보존·같은 복구 재개·DB 교체
경쟁 보존·소유 API/client/DB 정리를 확인했다. Compose CI gate를 추가했다.
docs/evidence/m9-recovery-database-fence.md. 전역 writer 회수·활성화와 전체 M9는 미완료다.

부하 실패 후에도 DB 관측값을 보존하도록 수정했다. 실제 API/PG 판정4개+큐 포화 회귀1개
062331Z-f6a52a6a PASS. 별도20ms 동시 커밋의 기본9,240건·비계측 지속19,800건은 오류/미발송0이나
진단 실행은175초 기록 종료 경계에서8건 미발송이다. 기존 개별 commit의 같은 비계측180초는
290건 미발송(063006Z-36e08240), 관측18,736행/감사20,741쌍 보존·정리 확인이다.
공유 저장장치 변동/계측 영향을 확정하지 않았고20ms 변형은 제품에 적용하지 않았다.
docs/evidence/m8-audit-storage-diagnostics.md. 선행5f80455 CI37181374516은 완료된3jobs/원시21개,
PG230/MQTT95/Runner111 PASS지만 images의 VD 전환 Attempt 검사 FAIL·gitops skipped다.
취소 전 상태 진단3개 PASS. 동일 Runner/MinIO·기존 JAR의 실제 클러스터 단일 전환은
070243Z-4715e4c0 PASS(Node1/VD3Pods·S3결과3개·정리)다. CI 실패 원인은 미확정이며
docs/evidence/m7-offload-ci-diagnostics.md에 기록했다. 새 CI/배포·M8성능 및
M5잔여/M7/M9/M10 수용은 미완료다. 아래는 선행 근거다.

ADR0067 감사 동시 커밋 실험은 단위129/PG231·실제API8·백업13 PASS지만 미채택이다. 기본 전체부하 첫실행
200건미발송 FAIL, 진단재실행은9,240건/감사12,105쌍 보존이다. 추가1,000대180초 진단은
374건미발송과JDBC commit/WAL대기를 같은구간에 기록했다. 기존 개별 감사 transaction으로
복원했으며 부하안정화·성능수용은 미완료다.
실행저장소47개 중 공유VD복구1개 MQTT거절 실패. 별도 실제브로커의 재접속상태/권한회수순서
재현 후 ADR0068 수정의 SDK19·전체MQTT95·Runner111 PASS, 프로토타입 조합의 저장소47 PASS.
최종 PG230 PASS(054856Z-986bd582). API JAR은 기존 검증본3968964d와 완전히 같으며
단위122·API/Swagger·복원 근거를 재사용한다. 최종 실행저장소47 PASS(055136Z-ca618f1f).
최종 부하 판정4개도 PASS(055524Z-55dffcf0), 각60감사쌍/117transaction·소유자원정리 확인.
docs/evidence/m9-audit-group-commit.md, docs/evidence/m7-broker-first-reconnect.md.
선행0e9d7a0 CI37177835436의5jobs/원시23개 PASS(053859Z-52c9a721), GitOpsfe1a995의
정확한imageID/Ready/PVC/ArgoSynced(053931Z-f013990c), 기존10파일/두PVC·TLS/S3보존
(054004Z-40e28b5d) PASS. 공유IngresshealthProgressing. ADR0068의 새CI·배포와 구분한다.
M5잔여/M7/M8성능/M9전체/M10실장비수용 미완료·전체goal유지. 아래는 선행 이력이다.

ADR0066/V34 관리HTTP의 영속접수·관측결과·감사조회API/화면 구현. 단위122/PG229,
실제API8·저장소47·감사포함백업13/43테이블·참조9·Kubernetes복구점검6개 PASS.
실API/Swagger12개·DB장애복구·V34의 기존Task9,371개 보존도 확인했다.
첫100→300→1,000대 부하는1,000대108건미발송 FAIL. 같은설정의진단재실행은
9,240요청/누락0·감사12,105쌍 보존,1,000대p95 228.13ms와WAL대기를 관측했다.
최초실패원인/해결과M8수용은미확정. docs/evidence/m9-management-audit.md.
선행4f408dd CI37174711098의5jobs/원시22개·키복원10·kind종료7 PASS(043600Z-294040c0).
GitOpsee91977 정확한imageID/Ready/PVC/ArgoSynced(043601Z-82aa8520), 기존10파일/두PVC
보존(043807Z-5d799a7c) PASS. 공유IngresshealthProgressing. 신규감사CI/배포는후속.
M5잔여/M7/M8성능/M9전체/M10실장비수용 미완료·전체goal유지.

ADR0065 정적 키 파일 암호화·새 경로 복원10개가 PASS(033214Z-e49bced8)다.
실제 age/OpenSSL·합성4파일/3,009bytes·원본 삭제 뒤TLS키쌍복원·손상/잘못된키/경로/링크/
덮어쓰기/변경입력 거절·native X25519/PQ를 확인했다. docs/evidence/m9-private-material.md.
새 CI 게이트 추가, 운영 Secret/CA 적용·키 회전·journal·서명·종합 복구 활성화는 남는다.
ADR0064 Kubernetes producer 중지의 판정5·실제클러스터7개 PASS(025504Z-74efb678).
전용namespace에실행한컨테이너2/자식2의종료와quota·timeout/재개·finalizer보존·소유자원정리
확인. docs/evidence/m9-recovery-producer-stop.md. 전역writer회수/해제/활성화는미완료다.
명시적CI image/source입력의7개재검증030222Z-f8fe9a12도PASS·소유namespace정리완료다.
선행aa21e46 CI37171839136의5jobs/원시21개 PASS(033736Z-c4b9c68f)를 확인했다.
단위113/PG226·DB복원13/S3백업11/참조9·STREAM24/Node43·VD33Pods/결과54개 포함.
GitOpsfd86520의 정확한imageID·Ready/PVC·ArgoSynced(033749Z-e4f8ce6c), 기존10파일/
두PVC보존(033850Z-a2a4c713) PASS. 공유Ingress healthProgressing, 새ADR0064/0065 CI는후속이다.
ADR0063 복원 DB/Kubernetes 관측은 분류7·실제PG/소유namespace6개 PASS
(023306Z-de6fe9bb):DB에 없는 실행3개/소유 충돌1개를 찾고41테이블·실제객체5개를
보존했다. 소유 DB/API/namespace 정리 확인. docs/evidence/m9-recovery-kubernetes.md.
회수/재가동은 수행하지 않는다. 6617d939 CI37168798128은19/20원시결과PASS지만
마지막 배포 demo driver에서 실패·gitops skipped다. 실제STREAM24/S354는 통과했다.
driver 실패 상세 artifact가 누락되어 근본 원인은 미확정이며 후속에 수집 경로를 보완한다.
기존 클러스터의 같은 데모3개/Pod8개/S36개는 PASS(023612Z-ad91d440)다.
CI 장애 해결/새 배포 판정은 아니다. docs/evidence/m9-backup-ci-failure.md.
현재 M7 다중 장치 DAG/스트리밍을 진행한다. M0–M4·M6 완료, M5 잔여/M8–M10 미완료다.
독립 M9 DB 백업·새 DB 복원을 ADR0059로 구현했다. PostgreSQL16/실제 패키징 API의10개
사례가 PASS(010138Z-7ebea0c3):41테이블·Flyway 이력34행·API 조회·불변 제약·백업 후 쓰기
분리·기존 DB/손상/파일 권한 거절·실제 pg_restore 오류 정리·경고 dump 미발행을 확인했다.
소유 DB/API를 정리했고 원본 archive/SQL 로그는 비공개다. Compose17 CI 게이트를 추가했으나
원격 통과는 후속 확인한다. docs/evidence/m9-postgres-backup.md, docs/m9-requirements.md.
DB만의 복원이며 아래 ADR0061의 파일 대조와 구분한다. 키/journal·외부 실행의 일치·RBAC/감사·전체 M9 수용은 남는다.
ADR0060 고정 S3 version 백업도 실제 TLS MinIO 두 개의11개 시험을 통과했다
(013635Z-8b24378d).4개 version/262,176bytes의ID/SHA, 원본 종료·replica 재시작·기존
대상/설정과 미연결 remote target 보존·실제 rule 정리/실패 manifest 미발행을 확인했다. CI storage 게이트를 추가했고,
docs/evidence/m9-storage-backup.md. 새 백업 코드의 CI/배포는 별도 후속이다.
ADR0061 DB/S3 전체 참조 대조는 실제PG16/TLS의9개 사례014850Z-ae31f51c PASS다.
원본 DB 삭제·원본 MinIO 종료 뒤2결과/2checkpoint의 고정4버전/1,287bytes를 확인하고,
과거 버전 누락·새 버전 대체·SHA/길이 불일치·다른 DB/저장소 신원·미지원 schema를 거절했다.
복원 식별자를 추가한 기존DB10개 회귀014814Z-0a42874e도 PASS다. Pod/broker 경계는 SQL fixture,
DB 제약은 유지했다. 소유 DB/프로세스 정리 확인. 새 CI·운영 Secret/journal·외부 producer
재조정·활성화와 전체 M9 수용은 남는다. docs/evidence/m9-recovery-references.md.
ADR0062 복원 DB 격리: 일반 기동·Flyway 비활성 우회를 DataSource 초기화에서 거절하고,
유효한 marker/실행 비활성 조건의 조회 모드만 허용한다. 단위113(015814Z-a341a724),
실제PG226(020031Z-c21892d0:격리3개 포함), 패키징API/백업13(015909Z-df643a3e),
계약(020353Z-baad79de) PASS다. 실제 두 PG 연결의 autocommit/transaction 쓰기25006,
조회 응답·인증/CSRF 쓰기403·41테이블 불변을 확인했다. 격리 실제 저장소47
(020809Z-89d0fd0c)·같은 새 JAR 참조 대조9(021137Z-d86797ad)도 PASS다. 새 CI/배포 및
외부 producer 회수·운영 활성화는 후속이다. docs/evidence/m9-recovery-quarantine.md.
ADR0057의 독립 M8 관리 부하 측정을 병행했다. 실제 API/전용 PG DB에서100→300→1,000대,
각60초·11/33/110RPS·9,240요청의 오류/누락0, 정확한 관측/세션·재접속·자원 정리를 확인했다
(234836Z-e9dbbb5f). 예정 시각 기준 p95는65.54/53.28/54.64ms다. 단위3개와 실제 API/DB의
측정/예산 미정/초과/소규모 통과 네 판정 회귀도 PASS(235714Z-8cb1074e)다.
후속 JFR 표본11,551개 중99.2%가 BCrypt 반복 검증이었다. ADR0058 성공 비교 캐시를 추가해
전체 단위111개와 같은 전체 규모 재측정을 통과했다(001207Z-b31c94e2). 1,000대 p95는
54.64→8.16ms, API CPU는5.15→0.164core이며 오류/누락0·정합성·실HTTP 인증/CSRF 거절을
유지했다. PG223(001717Z-5647c32b)·격리 실제 저장소47(002134Z-74fcd498)·최종 네 가지
판정 회귀(002507Z-35739268)도 PASS다. docs/evidence/m8-authentication-load.md.
035eb0e CI37165270385의5jobs/원시18개·PG223/저장소47/Runner111/MQTT90·부하 판정4개·
kind STREAM24개/Node43·VD33Pods/S354를 감사했다(012601Z-ccc48b9c). GitOpsa7702d7의
실제 imageID·Ready/PVC·ArgoSynced(012450Z-5bd5ecac)와 이후 기존10파일/두PVC 보존
(012817Z-4bf104a3)도 확인했다. 합의 성능 수용은 남는다. 계약 시험이 전체 단위 XML을 덮어쓰는 것을 확인해
전체 task 직후 counts를 보존하도록 수정했다(010848Z-a6761d92; 변경 없는111개 결과 재사용).
성능 기준은 UNSET이며 M8 완료가 아니다.
docs/evidence/m8-management-load.md.
ADR0056/V33 VD STREAM 그룹 수동 NODE 전환을 구현했다. 선택 작업은 다른 Node로 이동하고
동료 VD·최초 배치·체크포인트를 유지하며 전체 종료/회수 후 새 Attempt를 만든다.
PG223·단위105·실제 저장소47·UI46·실API/Swagger10 및 기존 Task9,371개 V33 보존 PASS.
실제 Kubernetes 전환3개/Node3Pods·VD7Pods/S3결과6개·이동 후 취소·소유 자원 정리 PASS
(230420Z-53281045). 후속 대기 중 API 교체·전환 취소2개/Node1Pod·VD5Pods/S33개도 PASS
(232628Z-8d401390). c3b7122 CI37162109091은5jobs/원시17개·PG223·실제 저장소47·
STREAM24개/Node43·VD33/S354 PASS(002842Z-60817531)다. GitOpsce9b834의 정확한 이미지·
Ready/PVC·ArgoSynced(002756Z-b7ddced1)와 배포 후 기존10파일/두PVC 보존(003127Z-fb775559)도
확인했다. 이번 M8 새 이미지의 근거와 구분한다.
선행415a1ce CI37159106124의5jobs/원시17개·STREAM20/Node40·VD24/S348 감사 PASS
(232825Z-aa072254). GitOps2227a91 실제 이미지·Ready/PVC·ArgoSynced(232742Z-d9dd8d87),
기존 파일10개와 두PVC 보존(232742Z-f9cc7914)도 PASS. 이번 V33 이미지 증거와 구분한다.
docs/evidence/m7-vd-stream-group-offload.md. 아래는 선행 VD STREAM 검증 이력이다.
후속 실제 Kubernetes의 VD 교체·Pod 유실 복구·최종 처리 복구3개/VD10Pods/S3결과9개를
통과했다(222247Z-bfb805ce). 교체 중 자식의 CANCELLED 보고를 사용자 취소로 취급하던
문제를 RUNTIME_LOST로 분류하도록 수정했다. 전체 PG220개도 PASS(222557Z-1fe8caf0)다.
소스b144c8b CI37157334661은 storage 실패, scaffold/runner 성공, images/gitops skipped다.
CI의 공유 VD 재시도 실패 원인은 미확정이며 로컬 전체 저장소45개는 PASS다. 새 수정의
이미지·CI·배포 확인은 남는다. 아래는 선행 단계별 검증 기록이다.

ADR0055/V31–V32 VD STREAM의 서버·DB·Swagger·UI를 구현했다. 자기 VD→Device→Run
잠금·다른 VD 공동 완료·같은 Pod의 Attempt/토큰 분리·그룹 용량 거절·실제 자식 종료 보고 뒤
재시도/취소와 완료 허가 뒤 VD 최종 처리 복구를 실제 PG 시험으로 확인했다.
PG219(211440Z-6754e4a7)·새 VD5개 포함 STREAM/route53·단위105·기존 저장소40·계약5/MVC26 PASS.
화면 lint/type/build·기존42개와 수정한 새 PC/모바일2개도 PASS다. 새 DB 시험의 Pod·S3·브로커
receipt는 fixture다. 후속 실제 VD supervisor/자식 Runner5개(214323Z-d9e57d1d)·전체 실제 저장소45개
(214448Z-27d21841) PASS. TLS/S3·같은/다른 VD·그룹 상태 복원·취소·결과28/37을 확인했다.
이 서버 시험의 Kubernetes 제출/Pod 신원은 fixture다. 후속 실제 Kubernetes6개/VD14Pods·
Node1Pod/S3결과15개·API 교체·자식 SIGKILL 그룹 복구·취소/자원 정리도 PASS(215558Z-8e800c29).
새 CI17개 기본 게이트·배포와 VD Pod 자체 교체/최종 처리 Kubernetes 수용은 남는다.
실제 API/DB/Swagger PC·모바일10개(212323Z-80951e84)와 로컬 V30→V32의 기존
Task9,371개 신원·최초 대상 보존(212706Z-46c55521)도 PASS다.
docs/evidence/m7-vd-stream-execution.md. M5 잔여/M7–M10 전체 미완료 유지.

선행abf6bfd CI37151914101은5jobs/원시17개·PG214·Runner111/MQTT90·VD5/S38·
STREAM11/Pod39/S324·kind22Run·영속TLS/배포데모 PASS다. GitOps720203b의 정확한imageID·
Ready/PVCBound/ArgoSynced(211258Z-1187337c)와 기존10파일/두PVC 보존(211350Z-0ef63124)을 확인했다.
공유Ingress aggregate Progressing은 유지한다. 후속 Remote 혼합3개 게이트는0d31eb8 main에
푸시했고 CI37154396986은5jobs/원시17개·혼합3/S35·기존STREAM11 PASS(220028Z-1e19bb71).
GitOps8b17e24 실제imageID/Ready/PVC/ArgoSynced(215910Z-ab971f5e)와 기존10파일/두PVC
보존(215911Z-fa4598d5)도 확인했다. 새 VD STREAM 로컬 변경의 CI/배포 증거가 아니다.

아래는 선행 BATCH 혼합 배치 구현 이력이다.
ADR0054/V30 BATCH 작업별 VD/REMOTE 혼합 배치를 공개 API·Swagger·화면에 연결했다.
대기 Task의 최초 VD/제공자를 고정하고 실제 VD producer는 자기 VD→Run 순서로 잠근다.
PG214·실제 저장소40·단위105·UI42·계약5/MVC26·실API/DB/Swagger10개 PASS.
로컬 V29→V30 업그레이드에서 기존 Task9,359개의 신원·최초 대상을 보존했다(202632Z-18d31194).
실제 Kubernetes의 서로 다른 VD→NODE→VD, API 교체8.416초·고정 S3결과3개와 기존 VD 회귀를
포함한5개/결과8개 PASS(201757Z-789c7d16). 새 혼합 시나리오는 kind CI에 연결했다.
후속 실제 Kubernetes↔Remote 최초 혼합3개/Node Pod3개/S3결과5개도 PASS(204108Z-330b498b).
NODE→REMOTE→NODE·REMOTE→AUTO·혼합 취소, 독립 TLS 제공자 계산1회·고정 입력·API 교체·
소유 자원 정리를 검증했고 새 kind 게이트와 실패 artifact 보존을 연결했다.
새 코드의 이미지/CI/공유 배포, STREAM VD/REMOTE와
외부 수용은 남는다. docs/evidence/m7-mixed-task-targets.md. M5 잔여/M7–M10 전체 미완료 유지.

선행fe32ed8 CI37149032705는5jobs/원시17개·PG212·Runner111/MQTT90·STREAM11개/
Pod39/S324·VD4·kind22Run·영속TLS/배포데모까지 PASS(202950Z-64c51a17)다.
GitOpsaabb815 실제imageID·Ready/PVCBound/ArgoSynced(202950Z-98cfa5e7)·V29 성공과
기존10파일/두PVC 보존도 확인했다. 공유Ingress aggregate Progressing은 유지한다.
선행204645f 자동 취소 driver 실패의 원인 확인과 이번 새 V30의 CI/배포는 별도다.

아래는 선행 AUTO/NODE 배치 검증 이력이다.
ADR0053/V29 작업별 최초 AUTO/NODE 배치를 공개 API·Swagger·화면에 연결했다. Run 기본값과
불변 Task 최초 위치를 분리하고 BATCH 하위·STREAM 그룹·재시도/최종 처리·offload 후 위치를 검증했다.
PG212·실제 저장소38·단위105·UI42·실제 API/DB/Swagger PC모바일10개 PASS.
실제 Kubernetes 전체11개/Runner Pod39개/고정 S3결과24개 PASS(192206Z-e77aa57a), API 교체13.332초.
다른 최초 Node·그룹 장애 복구·고정 결과·소유 자원/Job 장벽 정리를 확인했다.
기존 로컬 DB V29 성공, 이전 Task9,335개·기본 배치 불일치0(192822Z-b237dee1).
선행204645f CI37145780408은 images의 자동 전환 취소 구간 실패·gitops skipped다.
원시17개 중16PASS/1FAIL이며 상세 driver 진단이 artifact에 없어 원인은 미확정이다.
새 report에 비밀값을 제외한 실패 위치를 보존해 다음 CI에서 확인한다. 새 코드의 이미지/배포와
VD/REMOTE 혼합 배치·외부 수용은 남는다. docs/evidence/m7-task-initial-placement.md.
M5 잔여/M7–M10 전체 미완료 유지.

아래는 선행 자동 전환 검증 이력이다.
실제 Kubernetes의 메모리 부하→STREAM 그룹 자동 전환·취소·전환 한도를 검증했다.
최종9개 시나리오/Runner Pod31개/고정 S3결과18개 PASS(183338Z-5496a478).
선택 작업은 다른 노드 AUTO, 동료는 기존 NODE를 유지하고 상태9→14/23/BATCH37을 보존한다.
대기 중 API 교체·판단/수신 표본 일치·이전 producer401·동료의 재부하에도 한도 유지·자원 정리도 통과했다.
638d77d의 게시된 API/Runner 이미지도 새 자동2개/Pod7개/S33 PASS(184641Z-a6fc3e70)다.
CI37142931811의5jobs/원시JSON17개·PG206·Runner111/MQTT90·kind STREAM7/24Pods/S315,
기존22Run/VD4/영속TLS/배포데모를 확인했다(184641Z-efbfb547). GitOpsf869da5 실제3imageID·
Ready/PVCBound/ArgoSynced·V28 적용(184641Z-7ebc102f), 기존10파일/두PVC 보존(184839Z-12c00400)도 PASS다.
기존 공유Ingress aggregate Progressing은 유지한다. 새9개 CI 게이트는 후속이며 M5 잔여/M7–M10은 미완료다.
docs/evidence/m7-stream-automatic-offload.md.

아래는 선행 구성 요소 검증 이력이다.
ADR0052/V28 STREAM 자동 전환: 공개 opt-in·그룹 전체 체크포인트/대기/예산과 현재 producer
검증, 선택 작업 AUTO/방문 노드 제외·peer 배치 유지, 판단 근거와 불변 그룹 계획을 연결했다.
Runner 측정이 스트리밍 계산부터 최종 처리까지 같은 sequence로 이어진다. 최종 PG206,
Runner111·TLS MQTT90·실제 서버8/전체 저장소38·단위105·UI40·계약/MVC26 PASS. 서버 시험의 Pod 배치·신원·종료 및 지연 입력은
명시적 fixture다. 실제 Kubernetes 부하→자동 전환·새 이미지 CI/배포는 후속이다.
docs/evidence/m7-stream-automatic-offload.md. M5 잔여/M7–M10 전체 미완료 유지.

선행983ef6d CI37139978354는5jobs/원시JSON17개·STREAM7/24Pods/S315와 기존kind22Run,
VD4·영속TLS·배포데모까지 PASS(175446Z-e64ecd31). GitOps2f11f3e의 정확한3imageID,
Ready/PVCBound/ArgoSynced(175428Z-8b049df9)와 기존10파일/두PVC(175446Z-4cbb74fc) 보존도 확인했다.
Argo aggregate health의 기존 공유Ingress Progressing은 유지한다.

아래는 수동 그룹 전환 검증 이력이다.
ADR0051/V27 STREAM 그룹 NODE 전환: 전체 경로/producer 차단→전체 종료/회수→새 OFFLOAD
Attempt·checkpoint 인계, peer 기존 AUTO/NODE 유지·그룹 claim 성공을 구현했다. 실제 DB197·
독립 Runner/서버7·실제 저장소37·단위105·UI40·계약·실API/Swagger PC모바일10·DB 장애/복구 PASS.
최종 그룹 DB30도 통과했다. 실제 Kubernetes의 다른 노드 전환·대기 중 API 교체/재전송·취소·
늦은 producer401도 통과했다. 현재 JAR의 전체7개 시나리오/Runner Pod24개/고정 S3파일15개,
원래 장애 복구와 소유 자원·Job 대기 finalizer 정리 PASS(165130Z-e5016260).
소스d3797d6 CI37137184323은5jobs/원시JSON17개·영속 broker/TLS 저장소·배포 데모까지 PASS다.
GitOps4263ecb의 실제imageID/Ready/PVC/ArgoSynced·V27 적용과 기존10파일/두PVC 보존을 확인했다.
게시된 API 이미지의 새 전환/취소2개·Pod7개/S33개도171145Z-57cf39f1에서 PASS다.
새7개 기본 게이트의 CI는 후속이다. docs/evidence/m7-stream-group-offload.md.
M5 잔여/M7–M10 전체 미완료 유지.

선행8239265 CI37134164748은 runner/storage/scaffold 성공, images의 신규 영속 broker 검사 실패,
gitops skipped다. 원시JSON17개 중16PASS/1FAIL. 기존 kind BATCH/Remote/VD/STREAM5는 통과했고
새 broker Pod가 Pending일 때 검사가 끝났다. PVC 존재만 기다린 뒤 Bound를 즉시 assert하던
결함을 Bound까지 기다리도록 수정했고 후속d3797d6 CI의 새 클러스터에서 통과했다.
당시source533d850을 유지했으며 위GitOps4263ecb에서 검증된d3797d6 이미지로 갱신했다.

아래는 배포 TLS/데모 검증 이력이다.
ADR0050 dev TLS 컴포넌트8239265가 Synced이고 API/MinIO/broker가 Ready다. 실제 배포의
AUTO/NODE/cancel3개·Runner8개·고정 S3결과6개14/23/37·소유 자원 정리를 통과했다.
전환 전후 및 데모 뒤 기존파일10개와 두PVC UID/내용도 보존했다. 공용 driver5개 회귀 PASS.
코드269c548/8239265는 main에 푸시했고 새 kind 게이트 CI37134164748을 확인한다.
docs/evidence/m7-deployed-multidevice-demo.md. M5 잔여/M7–M10 전체 미완료 유지.

아래는 영속 기반 준비 이력이다.
ADR0049 운영용 불변 신원4개·공개 설정3개와 영속 TLS broker를 준비했다. 실제 설치 재실행/
복구·충돌 거절6개 및 새 Pod/동일PVC의 권한 유지·익명/오인증/신뢰 거절을 통과했다.
선택 Kustomize 컴포넌트와 kind 게이트를 추가했으며 API/MinIO TLS 활성화·데모는 다음 단계다.
docs/evidence/m7-persistent-stream-platform.md. 선행a29 CI5jobs/17JSON·배포 PASS;
HTTPS533d850은푸시했고 CI37131722732 진행 중이다. M5 잔여/M7–M10 미완료 유지.

아래는 추가 HTTPS 포트 검증 이력이다.
ADR0048 같은 API 프로세스의 선택적 native HTTPS 포트를 추가했다. 실제 HTTP/HTTPS 인증·
CSRF·신뢰/hostname2개와 전체 단위105개·PG190개 PASS. Kubernetes 스트림5개 시나리오도
이 포트로 연결해 Pod17개·S3파일12개·API 교체9.207초·그룹/최종 처리 복구·정리를 검증했다.
기본 비활성이며 운영 브로커·키/인증서·Runner 신뢰·데모 연결은 남는다.
docs/evidence/m7-native-api-tls.md. 선행 공개 retry a29f6c9는 main에 푸시했고 CI37129406733 진행 중이다.

아래는 실제 최종 처리 복구의 검증 이력이다.
완료 허가 뒤 실제 Kubernetes sink Job 유실도 검증했다. sink만 새 Attempt/Pod에서 원본
grant/checkpoint로 결과를 확정하고 이미 성공한 root Result·기존 경로/체크포인트 이력을 보존했다.
계산 중 그룹 복구·AUTO/NODE·API 교체·취소 포함5개 시나리오/Pod17개/S3파일12개 PASS.
docs/evidence/m7-finalizer-kubernetes.md. 새 공개 retry 이미지의 CI·배포는 후속 게이트다.
선행 ca56e2f CI37127032786은5jobs/17JSON·Runner111/MQTT87·완성 이미지 kind를 통과했다.
GitOps94952ff의 실제3imageID·Ready/PVC/ArgoSynced도141815Z-64242f02에서 확인했다.

아래는 공개 정책 연결의 검증 이력이다.
ADR0047 공개 STREAM retry를 기존 Run API·Swagger·화면에 연결했다. 그룹/최종 처리 시험의
직접 정책 생성 fixture를 제거하고 모두 공개 MVC 요청으로 시작한다. 공개 PostgreSQL23개,
실제 Spring/PG/S3/TLS/Runner6개, 계약5개/MVC26개, UI38개 PASS다.
전체 PostgreSQL188개도 통과했다. 실제 Kubernetes sink Job 제거→이전 Pod/권한 정리→새
Attempt2개·외부 상태9 복원·동일 장치 자동 재연결→14/23/BATCH37을 검증했다.
정상 AUTO/NODE·API 교체·취소 포함4개 시나리오, 실제 Pod13개·고정 S3 파일9개와 정리 PASS.
최종 단위101·실제 저장소36·실API/DB/Swagger PC모바일10개도 PASS, Flyway26개는 불변이다.
이 시점에는 새 CI/배포와 최종 처리 Kubernetes 장애 수용이 남았으며 후속 상태는 위 기록을 따른다.
상세: docs/evidence/m7-public-stream-retry.md.

아래는 선행 장치 재연결의 검증 이력이다.
ADR0046 DeviceRunSource 자동 재연결을 실제 SDK와 그룹 재시도에 연결했다.
고정 세션·논리 경로·LOCAL 볼륨에서 이전 transport를 닫고 새 인증 배정으로 미확인 데이터와
센서 커서를 인계한다. 실제 HTTPS/TLS MQTT/SQLite14개와 Spring/PG/S3/Runner6개 PASS.
두 Device 객체를 유지하며 서버의 새 세대를 자동 발견하고 root14/sink23/BATCH37까지 확인했다.
Runner111·최종 실제 서버6개와 신규 자동 재연결14개는 통과했다. 전체 MQTT87개는 기존 시험의
간헐 timeout/lease 만료가 남고, 별도 빠른 종료 관측 경합은 결정적 재현 후 수정했다.
새 CI/배포·공개 retry·Kubernetes 장애 수용은 남는다. docs/evidence/m7-device-reconnect.md.
선행 a0fc802의 CI37124689963은5jobs/17JSON·완성 이미지의 실제 kind를 통과했다.
GitOps77bef5b의 실제3imageID·Ready/PVC/ArgoSynced도133607Z-3eec4778에서 확인했다.

아래는 선행 최종 처리 복구의 검증 이력이다.
ADR0045/V26 완료 허가 뒤 새 Attempt의 최종 처리 복구를 구현했다. 기존 checkpoint/허가/세대는
보존하고 실패한 Task만 종료·회수 장벽 후 재시도해 실제 S3 결과14를 새 Attempt에 확정했다.
실제 저장소36·PG187·단위101·Runner111·계약 검증 PASS. 공개 retry는 아직 거절한다.
전체 MQTT73개 재검사는 PASS지만 이전 broker 재연결1개 timeout은 원인 미확정이며 진단을 추가했다.
Device 자동 재연결·Kubernetes 장애 수용·새 코드 CI/배포는 남는다. 전체 목표는 미완료다.
상세: docs/evidence/m7-finalizer-attempt-recovery.md.
선행934d003 CI37122400842 5jobs/17JSON·Device 조회를 사용한 실제 kind3개 시나리오와
GitOps17d51ad의 imageID/Ready/PVC/ArgoSynced 배포도 통과했다. 새 V26 이미지 검증과는 구분한다.

아래는 계산 중 그룹 재시도의 검증 이력이다.
ADR0044 계산 중 스트림 그룹의 원자적 재시도·전체 물리 종료/권한 회수 장벽을 구현했다.
실제 두 Runner가 새 Attempt/폴더에서 외부 checkpoint 상태9를 복원하고 Device journal을 인계해
root14/sink23/BATCH37을 확정했다. 전체 실제 저장소35·PG184·단위101 PASS다.
Run retry 정책 생성과 Pod 경계는 이 시험에서 fixture이며 공개 재시도 정책은 아직 거절한다.
공동 완료 허가 뒤 새 Attempt 복구·Device 자동 재연결·실제 Kubernetes 장애 수용은 남는다.
상세: docs/evidence/m7-group-retry.md. M5 잔여/M7–M10과 전체 목표는 미완료다.

아래는 Device 경로 조회의 검증 이력이다.
ADR0043 Device 토큰의 본인 Run 경로·최신 세대 조회와 SDK를 추가했다.
실제 HTTPS 조회 결과로 DeviceSource를 열고 송신해 독립 STREAM2개→BATCH 결과37·취소를 검증했다.
단위101·실제 PG181·Runner110·실제 Source/DAG4·API/DB PC·모바일10·Swagger 내부13개 PASS다.
새 조회 코드는 로컬 검증이며 이미지/CI/Kubernetes 수용은 별도다. 그룹 복구 자동화는 남는다.
선행 c152d4d의 새 kind STREAM 게이트는 CI37120638129 5jobs/17JSON과 실제 GitOps ab68e2a 배포를 통과했다.
상세: docs/evidence/m7-device-route-discovery.md. M5 잔여/M7–M10 전체 수용은 미완료다.

아래는 선행 TLS·Kubernetes 검증 이력이다.
ADR0042의 Runtime 공개 CA 번들 설정·VD 불변 참조와 Runner fsGroup 디렉터리 권한을 구현했다.
실제 Kubernetes에서 mkdir0700→2700 상속을 재현하고, 새 소유 폴더만 명시적으로0700으로 맞췄다.
단위101·실제 PG178·실제 저장소34·HTTPS/TLS MQTT71개를 로컬 검증했다.
MQTT 최초 전체 실행의 재연결1개 timeout은 단독/전체 재실행에서 재현되지 않았으며 원인 미확정이다.
독립 다중 Runner DAG는 실제 Spring/PG/S3/TLS MQTT에서 검증했고 source08f57d1 CI5jobs/17JSON이 통과했다.
수정 Runner e93d9e1의 CI 컨테이너108·TLS MQTT71개를 확인한 뒤 실제 Kubernetes TLS 시험을 통과했다.
AUTO/NODE DAG·API Pod 교체·취소·S3 결과6개와 값14/23/37, 실제 Runner Pod8개의 신원을 검증했다.
이후 source e93d9e1의 CI5jobs/17JSON과 GitOps ee44614 실제 배포도 확인했다.
빌드된 API 이미지 자체로 같은 Kubernetes DAG·재시작·취소·S3파일6개를114015Z-426fc3dd에서 통과했다.
새 kind STREAM 게이트도 후속 c152d4d CI에서 통과했다. 상세: docs/evidence/m7-kubernetes-stream.md.
그룹 인계·운영 배포·다중 장치 데모·M5 잔여/M7–M10 전체 수용은 남는다.

아래는 이전 단계의 검증 이력이다.
ADR0041/V25 공개 Run의 Device Session 고정·그룹 동시 배정·route generation 자동 준비를 구현했다.
공개 생성/재전송·모든 BATCH 선행 Result 장벽·그룹 실패/취소 전파·metadata GET과 PC/모바일 UI를 연결했다.
공개 MVC/실DB13개·전체 PG177개·공개 생성→실제 TLS/S3/MQTT/Runner2개·전체 저장소32개가 통과했다.
단위·계약/MVC26개·UI38개와 최종 변경4개·실제 API/UI/Swagger10개 PASS. 관리Swagger41/내부12개.
V25 프로젝트 DB 적용·기존 V1–V24 bytes 불변도 확인했다. 기본 STREAM 비활성 유지.
실제 다중 Runner·peer 인계·운영 TLS·Kubernetes 종단과 M5 잔여/M7–M10은 미완료다.
상세: docs/evidence/m7-public-stream-runs.md.
선행 e422b6a CI37111365153 5jobs/17JSON PASS와 GitOps0ed9671 실제imageID/Ready/PVCBound/ArgoSynced를 확인했다.
이번 공개 실행 변경의 새 CI·배포 판정은 선행 커밋의 검증과 구분한다.

아래는 이전 단계의 검증 이력이다.
ADR0040 현재 Runner의 공동 완료 허가 후 최종 상태 복구를 구현했다.
역사적 논리 경로·정확한 고정 S3 체크포인트·다운로드 후 허가 재검증으로 MQTT 없이 최종 파일을 만든다.
실제 Spring/PG/TLS API·MinIO·MQTT·장치 SDK·독립 Runner의 합14/Result와 취소를 검증했다.
Pod 신원/provisioning·peer 회수 시작은 fixture이며 같은 Attempt/epoch/Pod/runtime에 한정한다.
실제 STREAM claim400도 재현해 응답 record를 JSON map으로 변환하고 실제 변환기 회귀를 통과했다.
전체 Runner108·TLS MQTT70·저장소32·서버 단위98·PG164·계약/MVC26·PC/모바일10 PASS.
상세: docs/evidence/m7-finalizer-recovery.md.
선행4d2ad11 CI37108841332의5 jobs/결과JSON17개 PASS와 GitOps d4b8044의 실제 배포를 확인했다.
이번 복구 코드의 CI/배포와 공개 STREAM 활성화는 별도다. M5 잔여/M7–M10 미완료 유지.

아래는 이전 단계의 검증 이력이다.
ADR0039 DeviceSource의 서버 공동 완료 대기·영속 종료 의도·같은 볼륨 재시작을 연결했다.
실제 Spring/PG/S3/TLS MQTT에서 두 장치·독립 계산·외부 checkpoint·공동 허가·Result 저장과
경로 회수 뒤 장치 재시작2개, 전체 Runner107개·저장소32개·기존 broker23개·HTTPS/MQTT66개가 통과했다.
API/S3는 격리 loopback이며 Pod 신원/이미 시작한 runtime 경계는 fixture다.
상세: docs/evidence/m7-device-source-completion.md. 공개 STREAM501과 M5 잔여/M7–M10 미완료 유지.
선행46e24ad CI37106385090은5 jobs/결과JSON17개·실제kind PASS이며 GitOps32a01b4·
080101Z-08722655에서 정확한3개imageID·Ready·PVCBound·ArgoSynced·VD활성화를 확인했다.
aggregate health는 공유Ingress로Progressing이다. 이번 DeviceSource 코드의 CI/배포는 별도다.
다음은 허가 뒤 Runner 최종 상태 복구, 공개 route 생성·그룹 동시 시작·운영 TLS·UI·Kubernetes 수용이다.

ADR0038/V24의 서버 실행 배정·경로 고정·Task/Device 구성 요소 공동 완료 허가를 구현했다.
SERVICE live 포트와 모든 경로를 확인하며, 그룹 전체의 최신 END/처리 확인 순번이 일치해야
Result를 확정할 수 있다. peer 완료/경로 회수 뒤의 허가 재조회·동시 보고·취소·불변 이력을 검증했다.
새 실제 HTTP/PG9개, 전체 PG163개, 단위98개, 실제 SDK/HTTP/PG/S3 체크포인트14개와
기존 Result/Remote/VD 포함 저장소30개·TLS broker23개·계약·PC/모바일/Swagger10개가 통과했다.
내부 Swagger API는11개이며 V24도 프로젝트 DB 적용 후 불변으로 취급한다.
상세: docs/evidence/m7-stream-execution-completion.md. 공개 STREAM501은 유지한다.
DeviceSource 완료 대기·공개 route 생성/동시 시작·완료 허가 후 상태 복구·운영 TLS·UI·Kubernetes 수용은 남는다.
선행5f0bae5 CI37103460164는5jobs/결과JSON17개·Runner컨테이너102개·MQTT58개·실제kind PASS다.
GitOps c63dde0·071202Z-53439c81에서 정확한3개imageID·Ready·PVCBound·ArgoSynced·VD활성화를 확인했다.
aggregate health는Progressing이며 이번 ADR0038 변경의 CI/배포 완료 근거와 구분한다.

ADR0037 SERVICE stream 규격·CHECKPOINT 모드·파일/스트림 포트 분리와 Runner 실행 owner를 추가했다.
최신 외부 checkpoint의 마지막 END/ACK와 서버의 해당 checkpoint 완료 허가 후 최종 파일을 생성한다.
실제 Runner/model·HTTPS/TLS MQTT에서 결과14·볼륨 삭제 후9→14 복원·잘못된 허가/취소/실패를 검증했다.
서버 단위94·PG154·전체 Runner101·HTTPS/MQTT58 및 후속 VD/신호16개·계약 검증은 PASS다.
전체 회귀에서 드러난 signal handler의 Event 잠금 재진입을 Runner/VD에서 재현하고 수정했다.
상세: docs/evidence/m7-service-stream-runner.md. 서버 배정·완료 장벽은 fixture이며 공개 STREAM501은 유지한다.
선행98ea1dc CI37101222581은5jobs/결과JSON17개·실제kind PASS다. GitOps1bc4439와
062725Z-a19b3b3f에서 정확한3개 imageID·Ready·PVCBound·ArgoSynced·VD활성화도 확인했다.
aggregate health는Progressing이다. 이 배포 증거는 후속ADR0037 변경의 CI/배포 완료 근거가 아니다.
ADR0036 DeviceSource의 인증 송신·자동 heartbeat·동일 Device Session의 journal 인계를 구현했다.
실제 SQLite/SIGKILL7개·HTTPS/TLS MQTT7개·Spring/PG/권한 worker/broker23개를 통과했다.
전체 Runner99개·HTTPS/MQTT50개 및 후속 fanout 포함 journal8개·트랜잭션 중 취소/기한 갱신2개도 PASS다.
옛 경로 실제 ACL 회수 뒤 미확인 DATA 재전송·계산9→14·END/ACK·동일 세대 재시작을 확인했다.
선행 ba3b2ac CI37099723313은 peer 만료 감지 위치의 오류 종류 차이로 runner failure이며 배포되지 않았다.
실제 MQTT poll 내부 기한 만료로 재현하고 Session 오류를 정리한2개 시험은 PASS다.
상세: docs/evidence/m7-device-source-handover.md. Task/인접 Task 인계 orchestration과 공개 STREAM은 남는다.
ADR0035/V23 서버 검증 checkpoint 인계와 명시적 SDK/Session 복원을 연결했다.
새 Attempt·경로 세대에 state/커서/END를 보존하며 serial만 증가시킨다. 실제 Spring/PG/MinIO13개에서
독립 모델9→14·동시 인계·취소 경합·Device Session 변경 거절·DB 불변 제약을 검증했다.
단위89개·PostgreSQL153개·S3/Remote/VD29개·Runner92개·HTTPS/MQTT42개도 PASS다.
실제 Spring/PG/TLS broker22개와 PC/모바일·Swagger10개·DB 중단/복구도 통과했다.
상세는 docs/evidence/m7-stream-checkpoint-handover.md다. Device/인접 Task journal 전환과
SERVICE/Runner·공개 STREAM 종단은 남으며 M5 잔여/M7–M10 미완료를 유지한다.
ADR0034 인증 latest·고정 S3 다운로드·새 볼륨 Session 복원을 연결했다.
실제 HTTP12개·TLS MQTT 복원/소켓 정리3개·Spring/PG/MinIO7개를 검증했다.
전체 Runner91개·HTTPS/MQTT42개·Spring/PG/TLS broker22개 회귀도 PASS다.
동일 Attempt/경로에서 상태9→14와 미확인 출력/END를 복원하며 손상·기한 만료·이력 변경은 거절한다.
소스58277da의 CI37097769723 5 jobs/결과JSON17개·실제kind 및 GitOps3f94339 배포도 확인했다.
052409Z-2316f084에서 정확한3개 imageID·Ready·PVCBound·ArgoSynced·VD 활성화는 PASS이며 aggregate health는Progressing이다.
상세는 docs/evidence/m7-stream-checkpoint-recovery.md다. 새 Attempt/세대 인계와 공개 STREAM은 남는다.
ADR0033 인증 checkpoint client·Session 자동 publisher를 연결했다. 실제 HTTP/SQLite8개,
실제 HTTPS/MQTT39개·Runner87개와 실제 Spring/PG/S3/Python publisher7개를 로컬 검증했다.
실제 Spring/PG/TLS broker·Session 회귀22개도042847Z-5b966d84에서 PASS다.
응답 유실·503·같은 볼륨 재시작·확정 전 ACK/출력 보류를 확인했다.
상세는 docs/evidence/m7-stream-checkpoint-publisher.md다. 새 Attempt/세대 handover와
운영 Runner/공개 STREAM 연결·실제 Kubernetes 다중 장치 종단은 남는다.
ADR0032/V22 인증 checkpoint API·불변 DB 이력·실제 S3 내용 검증을 연결했다.
실제 HTTP/PG/S3 6개에서 동시 확정·정상 후속 갱신·변조·취소 경합·최신 고정 version을 검증했다.
단위88개·OpenAPI/패키징·기존 PostgreSQL·S3/Remote/VD 회귀를 통과했다. 상세는
docs/evidence/m7-stream-checkpoint-api.md다. 자동 Session 저장은 후속 ADR0033이며 새 Attempt/세대 인계와
운영 broker·공개 STREAM 실행·실제 Kubernetes 다중 장치 종단은 남는다.
서버 연결의 실제 TLS broker22개·DB153개·S3/Remote/VD22개·PC/모바일10개 및 DB 장애/복구도 PASS다.
서버1463117의 CI37095590064는5 jobs/결과JSON17개·실제kind를 통과했고 GitOps db21328에 고정됐다.
실제 배포044815Z-9e55517a에서 정확한3개 imageID·Ready·PVCBound·ArgoSynced·VD 활성화도 PASS다.
aggregate health는 공유 Ingress 상태로Progressing이다. 앞선 SDK a4e87c7의 CI37093274029는5 jobs/
결과JSON17개 및 실제kind를 통과했다. GitOps73b6f11·040416Z-7679f70f에서 정확한
imageID/Ready/PVCBound/ArgoSynced·VD 활성화를 확인했으며 aggregate health는Progressing이다.
ADR0031 외부 checkpoint SDK·전송 frontier를 구현했다. 실제 TLS MQTT/Session과 S3 고정 version의
볼륨 삭제/복원·9→14 재개·SIGKILL rollback을 검증했다. 현재 동일 binding 복원이며
인증된 checkpoint API/DB 확정은 후속 ADR0032로 연결했고 새 Attempt/generation 전환은 남는다.
상세: docs/evidence/m7-stream-checkpoint.md. 공개 STREAM501과 M5/M7–M10 미완료를 유지한다.
M6 VD는 실제 자식 Task/Result·수명·CI·배포·PC/모바일 결과 화면까지 검증 완료했다.
현재는 M7 다중 장치·스트리밍의 첫 구성 요소 작업 중이며 공개 STREAM 실행은 아직501이다.
frame/처리 확인·로컬 원자적 journal·MQTT 전달을 구현했고 실제 SQLite/프로세스·브로커·TLS 시험을
통과했다. ADR0023/V19 DataRoute·RouteGeneration의 내부 제어 상태도 실제 DB12개/전체148개를
통과했다. ADR0024 Mosquitto 권한 adapter도 실제 TLS broker·DB 세대 전환8개 시험을 통과했다.
ADR0025/V20 DB 권한 worker도 실제 Spring scheduler·응답 유실/새 worker·두 worker 경합을 포함한
실제 DB/TLS broker14개(20261002T230042Z-e4ac4863) 로컬 검증을 통과했다.
ADR0026 인증 배정은 실제 HTTP/DB/TLS broker의 현재 세션 토큰·Runner/Pod 경계5개와
기존 broker/worker14개를 로컬 검증했다. Pod 신원 확인은 이 시험에서 명시적 gateway fixture다.
ADR0027 SDK snapshot 검증·monotonic 기한·MQTT socket 종료/journal rollback과
ADR0028/V21 양쪽 heartbeat를 연결했다. 실제 Spring→Python→TLS MQTT에서 원래 기한을 넘긴 뒤
계산4+5·SQLite9·처리 확인을 검증했다. 한쪽만 요청하면 기한이 늘지 않고 broker 권한이 회수된다.
로컬 broker22개(010054Z-7106bd67),PG153개(010310Z-bf5a3d4d),Runner60개(005931Z-72af01a9),
SDK HTTP10개·실제 MQTT14개·단위·계약·Swagger4개 PC/모바일을 통과했다.
V1–V20 불변과 V21 적용/기존196개 보수적 window backfill도 직접 확인했다.
상세: docs/evidence/m7-stream-heartbeat.md.
인증 배정337abb3 CI37080508316과 SDK76651cd CI37082953978은 각각5 jobs/JSON17개·실제kind PASS다.
SDK의 GitOps9171827와 실제 imageID/Ready/PVCBound/ArgoSynced는011108Z-5d82c84b PASS다.
heartbeat77b687a의 CI37085573042는5 jobs/JSON17개·실제kind를 통과했고, GitOps19d7cec·
015434Z-4112045e에서 정확한 imageID/Ready/PVCBound/ArgoSynced를 확인했다. Argo health는Progressing이다.
ADR0029 지속 계산 subprocess·watchdog·Processor는 로컬 Runner69개(015358Z-5934ba4f),
실제 TLS MQTT26개(015610Z-bbcac546),Spring/DB/broker22개(015042Z-1edb329c)를 통과했다.
모델 재사용·WAIT/출력 적체·fanout·동일 볼륨 복원·잘못된 응답·부모 HTTP 대기 중 만료 종료와
VD Task session 자손 정리를 검증했다. 상세는 docs/evidence/m7-stream-workload.md다.
ADR0030 자동 Session은 Run/port별 인증 배정·서버 heartbeat 순번 재개·응답 유실/503 재시도와
계산/연결 정리를 통합했다. Runner70개022028Z-2420a08f, 실제 HTTPS/MQTT35개022149Z-856bc0f6,
Spring/DB/broker22개021811Z-db9bc151 PASS다. 상세는 docs/evidence/m7-stream-session.md다.
계산cf4876c CI37088407643은5 jobs/JSON17개·실제kind를 통과했다. GitOps7fad529·
023616Z-25449368에서 exact imageID/Ready/PVCBound/ArgoSynced·VD 활성화를 확인했다.
새 세션 변경의 CI·배포는 후속 확인 대상이다. 운영 broker·SERVICE/Runner 실행 연결·새 Pod checkpoint 복원과
공개 실행/API/UI·실제 Kubernetes 다중 장치 수용은 남았다. 공개 STREAM501은 유지한다.
최신 M6 판정은 docs/evidence/m6-completion-audit.md를 따른다. 아래는 누적 구현·검증 이력이다.
M0–M4 구현·검증 완료. M5 재시도·명시적 노드 전환은 실제 kind·CI·배포 검증 완료.
실행 측정·자동 전환·Remote 참조 adapter와 RemoteAllocation/결과 연결은 CI·배포까지 검증했다. 전체 플랫폼은 PARTIAL이다.
M5 Remote 자동 worker·공개 실행/전환 API·고정 제공자 binding은 로컬 및 CI37013658656을 통과했다. 후속 CI37016556197의 실제 kind22Run·결과20개도 통과했다. 외부 수용·상태형 복원은 남았다. 상세: docs/evidence/m5-remote-kind.md.
현재 상세: docs/evidence/m5-retry-offload.md. M4 완료: docs/evidence/m4-runtime.md. M3 완료 증거는 docs/evidence/m3-workflow.md에 보존한다.
공개 저장소: https://github.com/dsa04156/edgeai
M4 첫 구성 요소 코드 640e995의 CI 36975219681: scaffold/storage/runner/images/gitops 모두 success, 결과 JSON12개 PASS/0.
https://github.com/dsa04156/edgeai/actions/runs/36975219681
V5 코드5992cdc의 CI36978182298 5 jobs와 결과JSON13개도 통과했다. bc061e7 pin과 실제 imageID를 확인했다.

[IMPLEMENTED]
M5 재시도: V6 정책/예약, RETRY_WAIT, 동일 Task의 새 Attempt/epoch, 이전 종료 확인·취소·예산 소진과 UI.
M5 재시도 CI36990194234의 5 jobs·결과JSON14개 PASS 및 e777233 실제 이미지/Argo Synced 확인.
M5 명시적 offload: ADR0007·V7, source fence/drain, 동일 Task 새 Attempt/target claim, Operation API/화면.
오프로딩 CI36993166041의 5 jobs·결과JSON14개 PASS, kind15개 Run과 f886dd7 실제 배포를 확인했다.
V8/Runner 내부 telemetry API·cgroup 수집·서비스 지연·현재 Attempt 측정 화면을 추가했다.
측정 상세 증거는 docs/evidence/m5-runtime-telemetry.md다. ADR0009 자동 판단은 CI36999672446의
5 jobs/결과JSON14개/실제kind18Run/source951c4bd 배포까지 검증했다. 상세는 docs/evidence/m5-automatic-offload.md다.
ADR0010 Remote 참조 adapter/SQLite 시뮬레이터: 단위57(HTTP/TLS10 포함), 실제 프로세스 통합13,
3개 OpenAPI/MVC18 로컬 통과. 상세는 docs/evidence/m5-remote-adapter.md다.
참조 adapter CI37003825328 5 jobs/결과JSON15개 PASS, source0143094 실제 이미지/Ready/PVC/ArgoSynced 확인.
ADR0011/V10–V11 RemoteAllocation, Pod와 분리한 producer, 관측 revision/lease/늦은 결과 차단,
실제 S3 검증 후 결과 확정·하위 BATCH와 결과 API/화면을 추가했다. 로컬 PG80·단위58·실제 MinIO3 통과.
플랫폼 연결6009136의 CI37008176219 5 jobs/결과JSON15개 PASS, 실제 이미지·Ready/PVC/ArgoSynced 확인.
ADR0012/V12 Remote worker·직접 S3 전송·공개 REMOTE Run/Offload·불변 제공자 설정과 UI를 추가했다.
worker f6dc087 CI37013658656 5 jobs/결과JSON15개 PASS, 기존 실제 kind18Run 통과. GitOps pin은 f6c5a2d다.
실제 API/UI/MinIO imageID 일치·Ready/PVCBound/ArgoSynced도 PASS(20261002T135530Z-df0f3f31).
실제 외부 계약·상태형 복원은 남아 있다.
Profile 불변 버전, Device/Node/Session/Observation, 불변 Workflow DAG,
Idempotency-Key 기반 Run/Task/Attempt 생성·조회·취소·의존성 전파와 실제 Dashboard.
Flyway V1–V20, 계층형 Spring 패키지, 한국어 관리 Swagger40개·별도 스트림 배정2개, GitHub Actions/GHCR/ArgoCD 연결.
M6 첫 구현: 영속 VD 등록/수정/해제·원본 조건/연결 이력·Device 해제 보호 API와 `/virtual-devices` 화면.
V14는 내부 runtime/Operation·명령 lease·교체/drain 상태와 registry hook을 추가한다.
ADR0016의 실제 Pod gateway/worker·HMAC 자격, Pod-bound TokenReview·UID 소유/삭제·watch/relist를
추가했다. ADR0017/V15는 인증된 poll·순번 저장·lease/Ready·idle drain을 연결했다.
ADR0018은 공개 시작·교체·종료·실행 상태4 API, Operation 합집합 조회와 UI를 추가했다.
VD는 기본 비활성이다. 실제 Pod/poll 수용과 Run VD Task/Result 연결은 후속 ADR0020에서 검증했다.
상세: docs/evidence/m6-vd-lifecycle.md 및 docs/evidence/m6-vd-gateway.md.
poll 상세: docs/evidence/m6-vd-poll.md. 실제 Python 감독 프로세스→Spring HTTP→PostgreSQL을
검증하며 Kubernetes 신원/Ready는 fixture다. 실제 Task 배정/Result 종단은 아니다.
M3는 실행 요청 저장이며 실제 Runner 실행·검증된 Result는 M4다.
M4 실행 규격·Job compiler·S3 artifact adapter·독립 Runner를 추가했다.
V5의 RuntimeInstance·CREATE/DELETE 명령 lease·봉인된 Result/Artifact와 실행 상태 전이를 추가했다.
Run 잠금으로 claim·결과 확정·BATCH 하위 해제·취소를 직렬화하고 외부 저장소 I/O 후 producer를 다시 검사한다.
Kubernetes Job/Secret worker·watch/reconciliation·내부 claim/uploads/commit/fail HTTP를 구현했다.
Result 공개 API/화면·Swagger28개를 추가했다. 단위37·PG41·UI16·실DB브라우저8·DB장애/복구를 확인했다.
Attempt HMAC과 실제 Pod-bound TokenReview 신원을 함께 검증한다. 전용 runtime namespace/RBAC를 준비했다.
로컬 실행 기능 기본값은 비활성이다. 배포는 MinIO/PVC/버킷/영속 키와 GitOps 실행 활성화를 반영했다.
새 Run/결과 확정과 다음 실행 명령을 같은 DB 트랜잭션에 저장하며 과거 M3 Run은 자동 실행하지 않는다.

[VERIFIED]
ADR0020/V17–V18 VD Task 실행 연결: 최종 단위82(200158Z-ee581287), PG136(194641Z-b03db31a),
Runner28(193208Z-5ed6569a), 계약4/MVC25(194047Z-64c4bfc6), 실제 Python/HTTP/MinIO/DB16
(194722Z-7921adcd), UI32·추가VD UI2(200200Z-939513e3), 실제API/DB 브라우저10·Swagger39·
DB503/동일프로세스복구(195553Z-5fe1b1f1) PASS/0. 날짜는 모두20261002 UTC다.
실제Kubernetes 격리API/DB/MinIO·V1–V18·VD 수명/Task4개·고정S3파일5개·API Pod 재생성·
활성 교체·개별 취소·재시도·물리종료195417Z-ea2a6d9b PASS/0. 시험소유자원0개 별도확인.
후속 CI37059110890은5jobs/JSON15개·실제kind VD4조건/Task Run4개/S3결과5개를 통과했다.
배포204538Z-b137ac0f·기존 클러스터 VD 데모204808Z-37ef820e·실제 PC/모바일 결과 화면
205319Z-ea6cf390도 모두PASS/0이다. c2862a7 배포의 실제 imageID·Ready·VD 활성화를 확인했다.
M6의 STATELESS 실행 범위는 완료하며 상태형 복원·실제 센서 스트림·실장비 수용은 별도다.
상세: docs/evidence/m6-vd-task-execution.md. M5 잔여·M7–M10과 전체목표 active를 유지한다.
M6 등록 로컬: 단위/MVC66·실제PostgreSQL89(VD9)·계약/MVC22·UI28·실DB/브라우저10 모두 PASS.
VD 포함 DB 장애503와 같은 프로세스 복구도 PASS(20261002T144123Z-bf144394).
등록 코드0b4693c의 CI37022079299 5jobs/JSON15개 PASS, b5a9019 pin과 실제 imageID/Ready/PVCBound/ArgoSynced
검증20261002T151449Z-3a9ad7a8 PASS. docs/evidence/m6-vd-registry.md.
지속 supervisor·Pod compiler·내부 poll 계약을 후속 구성 요소로 추가했다. 실제 child Runner 시험과
Java compiler/계약 검증은 docs/evidence/m6-vd-runtime.md를 따른다. 서버·VD Task 연결 완료는 아니다.
ADR0015/V14의 실제 PostgreSQL lifecycle·경합·세대/원본 보호·명령 lease 회수 검증은
docs/evidence/m6-vd-lifecycle.md를 따른다. 실제 gateway 관측과 API 프로세스 재시작 수용은 남는다.
로컬 최종: 단위68·PostgreSQL98(lifecycle9)·계약/MVC22·실DB PC/모바일10 및 DB503/복구 PASS.
이전e442a5c CI37027216582 5jobs/JSON15개·Runner 실제컨테이너27개·기존kind22Run/S3결과20 PASS.
28a6b42 pin의 실제imageID·Ready/PVCBound/ArgoSynced155929Z-50929ad5도 PASS.
V14 b69d805 CI37031788410 5jobs/JSON15개·실제 Runner27·기존kind22Run/S3결과20 PASS.
b1e458c pin의 실제imageID·Ready/PVCBound/ArgoSynced163724Z-f4de6ab6 PASS.
ADR0016 실제 Kubernetes gateway5개(VD3+기존Job2), 단위HTTP경계 및 PostgreSQL worker 시험은
docs/evidence/m6-vd-gateway.md를 따른다. 대기 workload를 사용하므로 실제 감독/poll/Task 종단과 구분한다.
gateway cf499be CI37036686347 5jobs/JSON15개·실제Runner27·kind22Run/S3결과20 PASS.
86ff9bd pin의 실제imageID·Ready/PVCBound/ArgoSynced172048Z-00d56a91 PASS.
V15 poll 로컬: 단위79·실제PG111(poll9,실제감독프로세스/HTTP2 포함)·계약4/MVC23 PASS.
poll d89d2bb CI37040484693 5jobs/JSON15개·실제Runner27·기존kind22Run PASS.
6d46ec4 pin 실제imageID/Ready/PVCBound/ArgoSynced175035Z-94ede97d PASS.
공개 실행 ADR0018 로컬: 단위81·실제PG116(신규5)·계약4/MVC25·UI32 PASS.
실제API/DB PC모바일10·Swagger39·VD execution503/동일프로세스복구175257Z-32f133c1 PASS.
공개 실행 근거는 docs/evidence/m6-vd-public-execution.md다.
실제 Kubernetes VD 수명3개(AUTO+API Pod 재시작, NODE, Unschedulable 시작 실패)는
20261002T182533Z-cfdb17d2 PASS/0. 같은VD의 교체·이전poll차단·drain·자원0개를 확인했다.
docs/evidence/m6-vd-kubernetes.md. 새 kind CI·VD Task 연결·새 코드 CI/배포는 별도이며 미완료다.
후속00692fc CI37048443291은 신규 실제VD3개·기존kind/S3결과20·5jobs/결과JSON15 PASS.
d96ca85 pin 실제imageID·Ready/PVCBound/ArgoSynced190322Z-175c5b86 PASS. VD Task 연결은 남는다.
공개 실행8981a94 CI37044509505 5jobs/결과JSON15 PASS. pin f6a996e 실제imageID·Ready/PVCBound/ArgoSynced
183056Z-6979449b PASS. 신규 VD kind 게이트와 Task 연결 완료를 뜻하지 않는다.
ADR0019/V16 VD Task 영속 기반: PostgreSQL124(신규8)·최종 단위82·계약4/MVC25·실API/DB PC모바일10과
DB503/복구 PASS. 배정 slot·Run 잠금 순서·생산자 FK를 검증하며 관측/결과 receipt는 fixture다.
공개 VD Run·poll Task 전달·Runner/Result 연결은 남는다. docs/evidence/m6-vd-task-persistence.md.
ADR0012 로컬: 단위60·PostgreSQL80·계약/MVC19·실제 MinIO/DB/provider14·PC/모바일 UI26 PASS.
실제 Spring 스케줄러의 공개 Remote BATCH, 실제 제공자 SIGKILL/재시도, 취소와 출력 변조 차단,
설정 변경 시 전송 차단·재전송, 동시 worker의 단일 실행/결과를 검증했다. NODE 전환 부분은 fixture다.
실DB/API 브라우저8·Swagger·DB 장애503/동일 프로세스 복구도 PASS(20261002T132448Z-5fdba197).
이전6009136 배포: imageID와75c6632 pin 일치, Ready/PVCBound/ArgoSynced(20261002T130758Z-54ea0071).
기존 공유 Ingress status 제한에 따라 Argo aggregate health는 Progressing이다.
ADR0011 로컬: PostgreSQL80·단위/MVC58·계약/MVC19·실제 MinIO/DB3·PC/모바일 UI22 PASS.
실제 Remote 파일→MinIO→DB 결과→고정 입력의 Remote 하위 BATCH까지 계산값·SHA·version을 확인했다.
실DB/API PC·모바일8·Swagger·DB 장애503/동일 프로세스 복구도 PASS(20261002T123523Z-2f3da4ae).
이는 내부 lifecycle 통합 검증이며 Remote 자동 worker/공개 REMOTE 실행 요청 시험은 아니다.
M5 명시적 offload 로컬: 실제 PostgreSQL60·단위/MVC42·계약/MVC18·PC/모바일 UI18 PASS.
실DB/API PC·모바일8·한국어 Swagger30·실제 DB 장애/복구도 PASS(20261002T095243Z-b6c8245c).
실제kind 노드 전환/전환 중 API 재시작/고정 BATCH 입력/취소/시작 제한·cleanup을 통과했다.
실행 측정 로컬: PostgreSQL63·호스트 Runner12·UI20·실DB브라우저8 및 DB 장애/복구 PASS.
측정 CI36996007482 5jobs·artifact14개 PASS, kind15Run source/target 측정·late telemetry401 확인.
61b6caa 실제 배포(20261002T105511Z-be6074de) Ready/PVCBound/ArgoSynced.
자동 전환 정책 V9·API/UI·판단 이력·AUTO 제외 조건 구현. PG69·단위47·UI22·실DB브라우저8·DB장애/복구·실K8s server dry-run을 통과했다.
새 자동 전환의 실제 kind 수용은 CI 확인 대상이다. docs/evidence/m5-automatic-offload.md 참고.
M5 로컬: 단위39·실제PostgreSQL50·계약/MVC15·UI16·실DB PC/모바일8·DB 장애/복구 PASS.
단위/MVC25, 실제 PostgreSQL 통합19, 계약 생성 타입·YAML 일치,
UI lint/types/build·오프라인14·실DB PC/모바일8 및 DB 장애/복구 통과.
신규 CI와 실제 이미지 시험 통과. Kubernetes API/UI/DB Ready, PVC5Gi Bound,
Pod imageID와 CI digest 일치, Argo Synced, 실제 Ingress HTTP·Workflow PC/모바일2개 통과.
새 API의 실제 Node10개 UID/metadata 대조와 합성 장치 연결/해제 통과.
기존 Traefik/Ingress status 문제로 Argo aggregate health는 Progressing이며 공유 설정은 변경하지 않았다.
M4 구성 요소의 단위30·계약·호스트 Runner7·실제 MinIO4·Kubernetes server dry-run과
기존 PostgreSQL·실제 PC/모바일8·DB 장애 복구 회귀를 확인했다.
첫 M4 구성 요소의 CI·Runner 컨테이너·배포 이미지/Ingress를 확인했다.
V5 상태 서비스의 실제 PostgreSQL31·MinIO+DB2·PC/모바일8·계약·DB 장애 복구를 추가 검증했다.
실제3노드 kind에서 AUTO/NODE BATCH·API 재시작·artifact/producer fault·취소 시험을 통과했다.
이후 단위35·실제 PostgreSQL41·내부 API 계약 및 실제 Kubernetes gateway2개를 검증했다.
실제 gateway 시험은 AUTO/NODE 배치·Pod TokenReview·unbound token 거절·watch·UID 삭제를 확인했으며,
대기 컨테이너를 사용하므로 Runner→MinIO→Result 전체 경로 검증과 구분한다.

[EVIDENCE]
docs/evidence/m4-runtime.md, docs/evidence/m3-workflow.md와 docs/evidence/runs/<testRunId>.
M0–M2: docs/evidence/index.md 및 개별 milestone 문서.

[BLOCKED]
M4·M6 범위의 차단 없음. M5 잔여와 M7–M10은 미완료이며 전체 LOCAL_VERIFIED/FULL_ACCEPTANCE는 아니다.
로컬 Docker 권한 제한은 유지한다. 실제 kind 검증은 권한 있는 GitHub runner에서 수행한다.
외부 Remote API·실장비/모델·성능 수용 기준의 자료 위치를 요청한 상태이며 독립 구현은 계속한다.

[NEXT]
현재 다음 구현은 M7 다중 장치 BATCH/STREAM·DataRoute·전달/복구·실제 데이터 흐름이다.
M5 상태형 복원·외부 실제 계약 수용, M8 부하·M9 운영/복구/보안·M10 실장비가 남는다.
아래는 이전 단계의 후속 작업 기록이며 최신 상태는 위 STATUS/VERIFIED를 따른다.
M5 실제 NODE↔REMOTE·API 재시작/취소 kind 게이트4개 Run은 CI37016556197에서 통과했다.
기존18개 포함22Run/실제S3결과20개/원시JSON15개 PASS,861663f GitOps와 실제 imageID/Ready/ArgoSynced도 확인했다.
M6 등록·원본 연결 API/UI를 검증하며 다음은 지속 VD runtime·Operation·실제 Run VD 실행이다.
M4 완료: e4be5ff CI36986090769 5jobs/JSON14개 PASS, kind9Run·aecd457 pin/실제imageID·Ready/PVC/Argo 확인.
M5 Remote worker의 실제 kind 전환/재시작·CI·배포는 확인했다. 외부 실제 계약과 상태형 복원은
M5 완료 전 남은 게이트다. M6 등록 코드의 CI·배포는 확인했고 지속 runtime의 서버 연결을 이어간다.
다음 단계는 M6 VD → M7 다중 장치/STREAM → M8 부하 → M9 운영/복구/보안 → M10 실장비다.
아래는 M4 연결 단계의 이전 진행 기록이다.
현재 연결 코드의 CI 확인과 Runner·MinIO 검증 이미지 발행, 영속 키·bucket 설정 후 배포 실행을 활성화한다.
9d15fb1 CI36981974775 5jobs/JSON13개 통과, 배포 imageID·Ingress 회귀까지 확인했다.
Result/API 코드2cbaf36 CI36983413818 5jobs/JSON13개 통과, eb33bfd pin과 실제 API/UI/MinIO imageID 일치.
첫 실제 Runner 실행은 root Result를 만들었지만 하위 실행 또는 다음 Run의 claim에서 FENCED로 실패했다.
Pending Pod 관측 지연을 재시도하도록 수정했고 Java38·Runner8 회귀 통과. 새 CI/kind·배포 재검증이 남았다.
494ae37 CI36984655502 5jobs/JSON14개 및 실제kind 통과. 실제 root Result PC/모바일·고정S3 대조 PASS.
1c286b2의 실제 imageID/Ready/Argo 확인 후 기존클러스터 AUTO/NODE BATCH·취소·CPU/affinity 부족·
출력누락·작업실패·하위SKIPPED·파일4개 체크섬/계산값 검증 PASS. runtime=true 배포의 Swagger/인증/CRUD 회귀도 PASS.
추가실패조건3개의 다음kind CI 결과를 확인한 뒤 M4 완료 판정.
상세 구현 순서: PLAN.md. 기존 demo-workflow/test-kind의 실제 실행 기준을 유지한다.
개발 재개: bash scripts/dev-up.sh (Docker 대안: bash scripts/dev-postgres-local.sh start)
별도 터미널: bash scripts/dev-backend.sh / bash scripts/dev-dashboard.sh
Workflow UI: http://127.0.0.1:13080/workflows
배포 Workflow: http://edgeai.192.168.0.56.sslip.io/workflows
Swagger: http://edgeai.192.168.0.56.sslip.io/swagger-ui.html
재현: scripts/test-unit.sh, test-contract.sh, test-integration.sh, test-ui.sh,
test-profiles-stack.sh local (Compose는 compose). 인증은 로컬 .env/배포 .tools의 비공개 환경 파일을 사용한다.

[SWAGGER / CI-CD]
Swagger UI `/swagger-ui.html`, 계약 `/openapi.yaml` 추가. 문서 인증과 자동 CSRF 쓰기,
실제 desktop/mobile 등록·조회 검증 완료. 상세 근거: docs/swagger-ui.md.
GitHub Actions CI 연결 상태를 확인했고 Swagger 시험도 기존 CI 브라우저 경로에 포함했다.
최초 Swagger 추가 시점에는 ArgoCD/CD가 미연결이었다. 이후 진행 상태는 아래 CI/CD 기록을 따른다.

Swagger 포함 코드 fd729ca의 CI 36952012074: scaffold/storage success, 결과 JSON 8개 PASS/0.
https://github.com/dsa04156/edgeai/actions/runs/36952012074

[BACKEND PACKAGE LAYOUT — 2026-10-02]
사용자 요청에 따라 Java 소스를 역할별 계층형 패키지로 재배치했다.
app: controller/service/dto/config/exception/support, domain: profile/repository,
adapters: repository. 응답 DTO와 예외 타입을 독립 파일로 분리하고 테스트 선택 경로를 갱신했다.
상세 경로와 책임은 docs/architecture.md, 후속 구현 규칙은 backend/AGENTS.md에 반영했다.
Gradle 모듈 의존성, API·OpenAPI·DB 스키마·JSON 처리 규칙은 유지한다.

로컬 검증: clean 후 단위·MVC 11개, 계약 MVC 7개 및 생성 타입/패키징 YAML 일치,
실제 PostgreSQL 통합 6개, 실행 JAR + Profile/Swagger desktop·mobile 브라우저 4개,
DB 중단 시 503와 동일 API/UI 프로세스의 복구까지 모두 통과했다.
기존 Dashboard 빌드를 사용했으며 프런트엔드 소스는 변경하지 않았다.
원시 근거: docs/evidence/runs/ 아래 다음 실행 결과(JSON exit code 0/PASS).

- unit: 20261002T015706Z-2211bc1b
- contract: 20261002T015811Z-9c7e25cf
- integration: 20261002T015818Z-9cb09f25
- health/Profile/Swagger: 20261002T015826Z-4e61b9e7

[CI/CD 연결 및 API 설명 — 첫 구현 기록]
사용자 요청에 따라 GitHub Actions → GHCR → Git digest 갱신 → ArgoCD 흐름을 구현했다.
기존 context/ArgoCD/Traefik/local-path를 조회했고 전용 edgeai namespace와 Secret을 준비했다.
Kustomize/ArgoCD manifests는 실제 클러스터 server dry-run을 통과했다.
API/DB 주소의 환경 설정, 비루트 컨테이너, Next.js standalone, 실제 컨테이너 HTTP 검증을 추가했다.
Swagger의 모든 operation에 한국어 역할·입력·응답·오류 설명과 예시를 보강했다.

로컬 검증: unit 11개, 계약 타입/패키징 일치, UI lint/typecheck/build 및 8개 검사,
실제 PostgreSQL 통합 6개, Profile/Swagger PC·모바일 4개 및 DB 장애·복구 통과.
근거: unit 20261002T023728Z-2552d567, contract 20261002T023734Z-c96a7d3a,
UI 20261002T023836Z-0ecc2310, integration 20261002T023843Z-e5d47cee,
health 20261002T024516Z-13e1707a (모두 PASS/0).
Swagger 설명 선택자가 두 영역과 일치한 실패는 텍스트 범위로 수정했다.
Profile 브라우저에서 성공 문구 대기 실패가 한 번 발생해 실제 새 버전 POST 201을 먼저 확인하도록 보강했다.
로컬 Docker 권한 제한으로 이미지 빌드·발행은 GitHub runner에서 검증한다.
최초 CI 이미지 발행, GHCR pull 가능 여부 및 ArgoCD 실제 동기화는 이 기록 시점에 아직 미검증이다.

첫 CI 36957209450은 scaffold/storage 및 두 이미지 빌드까지 통과했지만 실제 컨테이너의
CSRF 거절 코드 검증에서 중단됐다. 기본 거절 처리의 /error 재디스패치가 403을 401로 바꾸는
현상을 로컬 실제 서버 로그로 재현했고, 거절 핸들러가 403을 직접 반환하도록 수정했다.
토큰 누락·잘못된 토큰 모두 403, 정상 등록/재등록/충돌/조회는 201/200/409/200으로 확인했다.
deployment-smoke 20261002T025851Z-53df87c0, contract 20261002T025908Z-d79f7da1: PASS/0.
빠른 재실행 시 TIME_WAIT를 활성 서버로 오인하던 포트 검사도 수정했고 활성 listener 차단은 확인했다.

[CI/CD 연결 및 API 설명 — 배포 확인, 2026-10-02]
코드 39eb6fe의 GitHub Actions 36958143060: scaffold/storage/images/gitops 모두 success.
내려받은 원시 결과 JSON 9개 모두 PASS/0. 실제 컨테이너 이미지 시험도 포함한다.
https://github.com/dsa04156/edgeai/actions/runs/36958143060
Actions가 검증한 두 이미지 digest를 912fa23으로 자동 기록했고 익명 GHCR manifest 조회도 200이었다.
ArgoCD edgeai-dev를 등록했으며 실제 Pod의 API/Dashboard imageID가 검증한 digest와 일치한다.
PostgreSQL PVC 5Gi Bound, DB/API/Dashboard 각 1/1 Ready, Git 동기화 Synced를 확인했다.
클러스터의 Docker Hub CDN reset으로 PostgreSQL은 동일 digest의 공식 ECR 미러로 전환했다(41bd732).

실제 Ingress에서 UI/CSS·API 연결·Swagger·인증·CSRF·등록201/재등록200/충돌409/조회200 통과.
두 사설망 주소 모두 인증된 Swagger/한글 계약/API 조회200 확인.
근거: kubernetes-http 20261002T031016Z-1de769da,
gitops-state 20261002T031127Z-a7f88d2d, ingress-swagger 20261002T031127Z-a1448527 (PASS/0).
배포 Swagger: http://edgeai.192.168.0.56.sslip.io/swagger-ui.html
대체 주소: http://edgeai.10.254.192.217.nip.io/swagger-ui.html
계정은 Git에서 제외한 .tools/kubernetes/edgeai-runtime.env에 보관한다.

남은 제한: 기존 Traefik LoadBalancer Service의 status.loadBalancer가 비어 Ingress 상태에도
주소가 게시되지 않는다. 실제 HTTP와 Pod는 정상이지만 ArgoCD aggregate health는 Progressing이다.
공유 Traefik/클러스터 설정은 변경하지 않았다. 작업 중 기존 control-plane 노드의 DiskPressure와
scheduler lease 갱신 실패도 관측했으며 이후 Pod 배치는 재개됐다. 클러스터 전체 안정성은 별도 운영 점검 대상이다.
로컬 검증 API/UI/DB와 일회성 registry probe Pod는 종료·제거했다. 배포 서비스와 PVC는 유지한다.

[M2 DEVICE/NODE — 2026-10-02 로컬 검증]
Device 8개·Node 2개 API, Flyway V3, controller/service/domain/repository 계층,
장치 등록/조회/revision 이름 수정/논리 해제·활성 연결 이력·bootId/epoch session fence·관측을 구현했다.
Profile UUID 참조·DEVICE 종류 FK, 활성 attachment/session partial UNIQUE 및 Device row lock을 적용했다.
실제 Kubernetes Node UID/Ready/allocatable/labels를 CA 검증·토큰 파일 기반 adapter로 관측한다.
실패 snapshot은 캐시를 제거하지 않으며 60초를 넘은 상태는 STALE로 조회한다.
Dashboard /devices와 Profile 메뉴를 연결하고 Swagger의 17개 operation에 역할·오류를 설명한다.

단위19·실제 PostgreSQL 통합13·계약 타입/YAML, UI lint/typecheck/build 및 offline12,
Profile/Device/Swagger PC·모바일6, DB 중단503/복구와 실제 Kubernetes 노드10개 대조를 통과했다.
실노드 연결 시험은 SYNTHETIC Device의 관리 이력이며 물리 장치 데이터 수신 시험이 아니다.
전용 ServiceAccount의 get/list nodes만 허용하는 bootstrap RBAC를 준비했고
nodes patch·secrets list·pods create가 허용되지 않는 것을 확인했다.
M2 코드의 새 GitHub CI, GHCR 이미지 및 클러스터 내 HTTPS/CA/ServiceAccount 경로 검증은 다음 확인 대상이다.
M3–M10은 아직 미완료이며 전체 목표를 M2로 축소하지 않는다.

[M2 CI·배포 확인 — 2026-10-02]
코드 3cfc41b의 GitHub Actions 36966964979 재실행: scaffold/storage/images/gitops 모두 success.
최초 시도는 Maven Central 의존성 다운로드403으로 backend 시험 전에 실패했다.
동일 POM 4개를 HTTP200으로 확인하고 코드 변경 없이 실패 job을 재실행해 통과했다.
다운로드한 platform/storage/image artifact의 result.json 9개 모두 PASS/0이다.
Actions가 d56f673으로 이미지 digest를 기록했고 실제 Pod imageID가 일치한다.
Argo Synced, DB/API/UI 각Ready1/1, PVC Bound. 기존 Ingress status 문제로 aggregate health는 Progressing이다.
Ingress에서 Device 관리·CSRF·session fence·관측 정밀도·해제 및 실제 Node10개 UID/metadata 대조를 통과했다.
전용 ServiceAccount/CA/HTTPS의 실제 cluster 내 노드 읽기 경로도 확인했다.
근거: m2-kubernetes-http 20261002T051528Z-b69dcf3d,
m2-kubernetes-nodes 20261002T051546Z-5657d8c7, m2-gitops-state 20261002T051753Z-35363223.

[M3 WORKFLOW/RUN/TASK — 2026-10-02 로컬 검증]
Workflow4/Run4/Task2 API, Flyway V4, 불변 DAG 봉인·SERVICE FK·동일 버전 FK·활성 Attempt UNIQUE,
Idempotency-Key 기반 원자적 실행 요청과 Run 행 잠금 취소·하위 전파·독립 분기 보존을 구현했다.
/workflows와 Swagger27개 operation, 큰 숫자·PC/모바일·인증/CSRF를 검증했다.
단위/MVC25, 실제 PostgreSQL 통합19, UI 오프라인14/실DB8, 계약 및 DB 장애·복구 모두 통과했다.
상세 실행 ID·실패 원인과 수정·한계는 docs/evidence/m3-workflow.md에 기록했다.
현재 root READY/QUEUED와 하위 WAITING은 요청 저장 상태이며 실제 작업 실행은 M4다.
M3 코드의 신규 CI·이미지·클러스터 배포 검증은 다음 확인 대상이다.

[M3 CI·배포 확인 — 2026-10-02]
코드8d1ae08의 Actions36970385137 네 job과 내려받은 결과JSON9개 모두 성공.
Actions의7a1614e 이미지pin과 실제 Pod imageID 일치, API/UI/DB Ready·PVC Bound·Argo Synced 확인.
Ingress HTTP·Node10개 대조·사설 HTTP Workflow PC/모바일까지 통과했다.
원시 증거: 20261002T055519Z-2f49e2b9 / -c35bcc0a / -7f924d90, 20261002T055555Z-bfc97a18.
전체 플랫폼 범위를 유지하며 M4 실제 실행 경로로 진행한다.
