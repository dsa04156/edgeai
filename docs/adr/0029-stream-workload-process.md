# ADR0029 — 지속 스트림 계산 프로세스와 상태 확정 경계

상태: 구성 요소 구현·로컬 검증 완료. 실제 공개 STREAM 실행·Kubernetes 수용은 후속 연결이다.

스트림 SERVICE 계산은 하나의 지속 프로세스에서 줄 단위 JSON stdin/stdout 프로토콜
`edgeai.stream-workload/v1`을 사용한다. 모델을 매 frame마다 다시 기동하지 않는다.
Runner가 checkpoint revision·상태 bytes·현재 처리 가능한 입력 frame과 named port,
끝난 입력 목록·출력 규격·고정 parameters를 보낸다. 모델은 같은 revision에 대해 WAIT,
COMMIT 또는 COMPLETE, 소비할 입력 순번·새 상태·출력 bytes를 반환한다.
[요청 schema](../../contracts/streams/workload-request.schema.json)와
[응답 schema](../../contracts/streams/workload-response.schema.json), 각각의 example을 제공한다.

Runner만 journal을 확정한다. 모델은 요청의 상태를 권위 있는 상태로 사용하고 확정 전 외부
부작용을 만들지 않아야 한다. WAIT는 상태/소비/출력을 바꾸지 않으며 같은 입력 집합으로 다시
호출하지 않는다. COMMIT/COMPLETE는 최소 하나의 실제 다음 입력을 소비한다. 여러 입력의
join 의미는 모델이 결정한다. 같은 출력 port의 여러 route에는 같은 결과를 복제한다.
COMPLETE는 모든 입력의 END를 소비했을 때만 가능하며 마지막 DATA와 각 출력 END를 같은
트랜잭션에 넣는다. 이를 위해 journal의 한 commit 출력 상한은32개(16개 DATA+16개 END)다.

처리 결과가 backpressure로 확정되지 않으면 후보를 메모리에 하나만 유지해 commit만 재시도한다.
모델에 같은 계산을 다시 요청하지 않는다. 재기동은 확정된 checkpoint만 복구한다. 동일 볼륨의
실행 규격 digest를 검증해 다른 command/parameters/port mapping으로 계산을 계속하지 않는다.
S3 checkpoint와 새로운 Attempt/generation 복원은 이 계약만으로 충족되지 않는다.

실제 입력/출력 pipe는 nonblocking이며 메시지는12MiB, 상태는journal의 기존 상한,
입출력은각16port·frame은기존256KiB로 제한한다. stderr·원문응답·환경자격은 로그에 내보내지 않는다.
모델 stdout은 프로토콜 전용이다. 잘못된 revision·소비 순번·다른 port·과대 출력·부적절한
완료 응답과 중복/비정상 JSON은 확정 전에 거절한다.

독립 watchdog은 monotonic lease·계산 요청 timeout·취소를 확인한다. 부모가 HTTP/MQTT I/O에
대기 중이어도 실행 중인 계산을 종료한다. 새 배정은 기존 Link와 watchdog이 모두 만료 전일
때만 반영하며 늦은 응답은 권한을 되살리지 않는다. 종료 시 소유한 프로세스와 자손을 회수한다.
이는 Linux 프로세스 경계이며 악의적인 workload의 임의 세션 탈출을 격리하는 sandbox는 아니다.

모든 입력 종료·미확인 출력0은 로컬 계산/전달의 상태이며 검증된 S3 Result 확정이 아니다.
로컬 계산이 끝나도 제어 서버가 실행 종료를 확인할 때까지 Link/처리 ACK/heartbeat는 유지해야
한다. END 처리 ACK가 유실된 뒤 upstream이 재전송하는 경우에도 동일 watermark를 응답해야 한다.
공개 제어 서버·SERVICE Profile 실행 계약·Kubernetes Job/VD 실행 연결은 후속 수용 게이트다.

검증은 [실제 프로세스·MQTT·Spring 기록](../evidence/m7-stream-workload.md)을 따른다.
