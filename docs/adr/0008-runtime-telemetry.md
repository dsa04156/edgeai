# ADR 0008: 현재 producer의 실행 측정

상태: 채택, 로컬·실제 컨테이너/kind·CI36996007482·실제 배포 검증 완료. 자동 전환에 필요한 측정 경로를 연결한다.
최종 전환 알고리즘/임계값의 최적성은 원문대로 측정·실험 후 검증하며 자동 정책 자체는 후속 단계다.

Runner는 claim 응답이 telemetry를 허용할 때 실행 중 5초마다 cgroup v2의 cpu.stat 사용 시간 증가량,
cpu.max quota/period, memory.current/max를 읽는다. 노드 전체 잔여 자원이나 GPU/NPU 지표가 아니다.
CPU는 intervalMillis 동안의 cpuUsageMicros와 cpuLimitMillicores, 메모리는 사용/제한 bytes를 저장한다.
무제한 quota, 지원하지 않는 cgroup 또는 읽기 실패는 null이며 가상의 0으로 바꾸지 않는다.
참고: [Linux cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html).

서비스가 지연 시간을 측정하면 EDGEAI_TELEMETRY_FILE에 sequence, observedAt, latencyMicros를
원자적으로 교체해 쓸 수 있다. 이는 해당 서비스가 보고한 개별 측정이며 p95/p99나 전체 실행 시간으로
간주하지 않는다. Runner는 일반 파일·최대4KiB·단조 증가 sequence·최근 시각을 검증하고 같은 지연
샘플을 반복 전송하지 않는다. 지연 파일이 없으면 지연 값은 null이다.

내부 POST /internal/v1/attempts/{attemptId}/telemetry는 기존 Attempt HMAC과 Pod TokenReview를
요구한다. Run 잠금 아래 현재 epoch/producer를 재확인하여 취소/전환/commit 뒤 측정을 차단한다.
sequence와 observedAt은 단조 증가해야 하며 오래된/미래 측정을 거절한다. 동일 sequence/동일 내용
재전송은 같은 receipt, 내용 충돌은409다. 수신 시각은 서버가 기록한다.
시각은 PostgreSQL의 microsecond 정밀도로 정규화하며 Run 잠금을 얻은 후 freshness를 확인한다.
V8 runtime_telemetry에 Attempt별 최신64개를 보관한다. 자동 판단의 영속 감사 근거는 정책 구현 시
별도 결정 기록으로 복사해야 하며 이 제한된 샘플 보관을 감사 로그로 취급하지 않는다.

Task 상세에는 최신 Attempt의 최신 측정만 제공한다. 이전 Attempt의 수치를 새 실행의 현재 수치로
표시하지 않는다. 수집되지 않은 지표·만료된 측정은 화면에서 구분한다. 기존 실패 재시도와 NODE 전환
계약은 유지한다. telemetry 장애는 결과 계산을 실패시키지 않지만 producer fencing은 작업을 중단한다.
측정 시각/내용409와 producer409를 오류 코드로 구분한다. 측정 전송은 best effort이며 자동 정책은
누락 순번·만료·불충분한 표본을 판단에 포함해야 한다.
