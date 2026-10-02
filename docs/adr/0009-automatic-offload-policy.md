# ADR 0009: 측정 기반 자동 전환 정책

상태: 구현·로컬 검증 완료, 실제 kind·CI 수용 검증 전. 최종 오프로딩 알고리즘의 최적성은 실장비 측정·실험 후 별도로 판정한다.

Run 생성의 선택 필드 offload에 불변 정책을 저장한다. 생략/null은 비활성이다. 모든 SERVICE가
recovery.mode=RESTART일 때만 허용한다. 최초 실행은 기존 AUTO/NODE를 따르며, 명시적으로 활성화한
자동 전환은 기존 노드 지정과 관계없이 다른 호환 노드를 스케줄러가 선택하도록 허용한다.
실패한 producer의 재시도는 기존 retry 계약으로 처리하며 RUNNING 측정에 의한 전환과 구분한다.

CPU 제한 대비 사용률, 메모리 제한 대비 사용률, 개별 서비스 지연 중 하나 이상의 임계값을 설정한다.
같은 지표가 최신 연속 2–6개 표본에서 모두 임계값 이상이어야 한다. 누락 sequence, 오래된/미래 시각,
표본 간격 초과, 서로 다른 Attempt, 미수집 값·무제한 자원은 해당 판단을 만족하지 않는다.
지연 측정 시각도 증가해야 하며 한 지연 관측을 여러 번 재사용하지 않는다. 여러 지표가 동시에
충족되면 LATENCY→MEMORY→CPU 순서로 원인을 기록한다. p95/p99나 노드 잔여 자원을 추정하지 않는다.

최소 실행 시간 이후 및 이전 전환 완료 후 cooldown 이후에 관측한 새 표본만 인정한다.
작업별 자동 전환 maxTransfers(1–8)와 수동·자동 합계 8회 제한을 모두 적용한다.
이미 실행한 노드 이름을 제외하므로 자동 전환이 이전 노드로 돌아가지 않는다. 최소 하나의 다른
최근 READY·linux·arch/selector 호환 노드가 관측될 때만 source를 중단한다. 이 관측은 자원 여유나
taint/volume/runtimeClass의 스케줄 가능성을 보장하지 않는다. 실제 배치 실패는 시작 deadline으로 종료한다.

Run 잠금에서 현재 producer/상태와 표본을 확인하고 판단 근거, Operation, fence/DELETE 명령을
원자적으로 기록한다. 표본 정리와 무관하게 결정 당시 정책·표본·제외 노드가 Operation에 남는다.
취소/commit/수동 전환과 같은 잠금을 사용한다. 중복 worker 및 재시작에도 source별 전환은 하나다.
후속 drain·새 Attempt·Result/늦은 producer 차단은 ADR0007 경로를 사용한다.

새 Attempt는 AUTO와 제외 노드 목록을 저장한다. 재시도도 그 목록을 유지한다. PodSpec은
metadata.name NotIn required node affinity와 SERVICE 제약을 AND로 합치며 nodeName을 직접 설정하지 않는다.
Kubernetes matchFields는 requirement당 값 하나만 허용하므로 각 제외 이름을 개별 NotIn requirement로 넣는다.
claim 시에도 제외 이름을 확인한다. [Kubernetes 노드 affinity](https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/).

V1–V8 적용본은 수정하지 않는다. V9를 추가하고 기존 API Recreate 배포를 유지한다.
Remote·checkpoint·STREAM 및 실제 장비의 성능 수용시험은 이 정책으로 대체되지 않는다.
