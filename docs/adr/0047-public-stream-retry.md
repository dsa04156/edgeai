# ADR 0047: 공개 STREAM Run의 재시도 정책

2026-10-03. ADR0044의 그룹 재시도, ADR0045의 최종 처리 인계와 ADR0046의 장치 자동
재연결을 기존 `POST /api/v1/workflow-runs`의 `retry`에 연결한다. 별도 복구 요청이나
시험 전용 정책 생성 경로를 만들지 않는다. V1–V26은 변경하지 않는다.

## 정책과 불변 조건

공개 STREAM을 명시적으로 활성화한 환경의 AUTO/NODE에서 기존 RetryPolicy를 받는다.
생략하면 최초 1회만 실행하며 허용 오류·횟수·대기 시간·총 기한 검증을 그대로 적용한다.
정규화된 정책과 streamInputs는 기존 Idempotency-Key 요청 비교에 포함한다. 배열 순서만
달라진 같은 요청은 같은 Run을 반환하고 정책을 바꾼 요청은409다.

계산 중 재시도 가능한 실패는 STREAM 또는 동일 Device fanout으로 연결된 전체 그룹을
재시도한다. 각 Task의 최초 실행 포함 횟수 예산을 소비하며 가장 이른 기한을 공유한다.
모든 이전 실행의 실제 종료, 미완료 CREATE 명령 해소와 broker 권한 회수를 확인하기 전에는
새 Attempt/epoch·경로 세대를 만들지 않는다. 외부 checkpoint가 있으면 확인된 상태와
커서를 새 세대에 인계한다. 아직 checkpoint가 없는 실행은 NEW로 시작한다.

공동 완료 허가 이후 실패는 해당 Task의 최종 처리만 재시도한다. 원래 허가·최종 checkpoint를
참조하고 확정된 다른 Task 결과를 보존한다. Device는 같은 고정 세션과 LOCAL 송신 볼륨을
유지한 DeviceRunSource로 재연결한다. 볼륨 손실이나 세션 교체를 자동 초기화하지 않는다.

STREAM offload·REMOTE·VD는 계속409이며 기본 공개 STREAM 비활성은501이다.
이는 기존 안전장벽을 통과한 재시도를 공개하는 변경이며 실행 중 위치 전환의 수용을 뜻하지 않는다.

## 검증

그룹/최종 처리 DB 시험과 실제 Spring·PG·S3·MQTT·독립 Runner 시험의 Run 생성도 모두
공개 MVC 요청으로 바꾼다. 정책을 직접 저장하는 시험 우회 경로를 제거한다. Pod 생성·신원·
종료 관측 fixture는 별도로 표시한다. PC/모바일에서 정책 입력·오류 후 재전송·동일 요청 키를
확인하고 Swagger의 동작·제한과 생성 타입을 함께 갱신한다.

실제 Kubernetes 시험에는 공개 retry Run의 sink Job을 UID 조건으로 제거하는 시나리오를
추가한다. 이전 Pod2개·세대3개의 종료, 새 Attempt2개·새 Pod2개, 새 Attempt에 저장된 상태9,
같은 장치 owner/센서 커서, 후속14/23/BATCH37의 고정 S3 결과와 전체 자원 정리를 확인한다.
실제 최종 처리 단계의 Kubernetes 장애도 [후속 검증](../evidence/m7-finalizer-kubernetes.md)에서
파일 장벽으로 허가 뒤 장애 시점을 고정하고 원본 grant/checkpoint·peer Result 보존을 확인했다.

[실행 결과와 제한](../evidence/m7-public-stream-retry.md).
