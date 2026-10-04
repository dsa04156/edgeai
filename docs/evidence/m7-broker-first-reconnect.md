# 브로커 우선 회수와 Device 재연결

2026-10-04. [ADR0068](../adr/0068-broker-first-device-reconnect.md).

기존 실제 실행·저장소47개 `20261004T052726Z-69649eee`는46성공/1실패다. 공유 VD 그룹
재시도의 SECOND_CHECKPOINT에서 Device driver가 RECOVER/MqttError로 종료됐고 Run은
FAILED였다. 시험 자원은 정리했다. 원래 예외 문구는 공개 기록에 없어 연결/구독/발행 중 어느
거절인지는 미확정이다. 후속 fixture는 원문이나 credential 대신 허용된 고정 오류 분류를 남긴다.
해당 시험 단독 `20261004T053230Z-543d4f72`는 제품 수정 전에도 통과했다. 이를 원인 해결로
간주하지 않는다.

실제 TLS 브로커에서 기존 사용자를 먼저 회수하고 HTTP heartbeat 이전에 다시 연결하도록
순서를 고정한 `20261004T053533Z-564db13b`는 Paho timeout 설정 RuntimeError로 실패했다.
Paho는 소켓이 없어도 CONNECTION_LOST 상태를 유지하며 그 상태의 설정 변경을 거절한다.
공개 disconnect로 상태를 마친 후 `20261004T053644Z-6ca648fe`의 일반 재시작은 통과했고,
같은 회수 시험은 MQTT connection rejected로 실패했다. 이전 source가 자동 복구하지 못했다.

수정 후 Device SDK19개 `20261004T053823Z-4c23fc76`는 모두 PASS다. 새5개는 다음을 확인한다.

- 실제 브로커 우선 회수/HTTP409 뒤 동일 owner의 새 세대 연결과 LOCAL 커서·순번·미확인 데이터 보존.
- 실제 브로커만 재시작했을 때 같은 배정/source로 데이터 재전송.
- 브로커 거절을 전달했지만 HTTP 배정이 여전히 유효하면 종료하며 journal 보존.
- HTTP401을 브로커 재연결로 숨기지 않고 종료.
- HTTP503이면 이전 transport를 닫고 새 emit을 거절하며, 복구 뒤 새 배정에서 기존 커서 재개.

마지막 세 사례의 브로커 오류 전달과 HTTPS authority 상태는 명시적 fixture다. 첫 두 사례는
실제 Mosquitto 연결/거절을 사용한다. 기존 TLS 오류·고정 세션 교체·취소·timeout·SIGKILL·
fanout·완료 의도 시험도 통과했다. 전체 TLS MQTT95개 `20261004T053930Z-dc7c0286`과
Runner111개 `20261004T054337Z-fab5a5df`도 PASS다. 감사 동시 커밋 프로토타입과 결합한
전체 실제 실행·저장소47개는 `20261004T054527Z-cb63eb01` PASS이며 소유 자원을 정리했다.
아래는 감사 실험을 제외한 최종 코드의 검증이다.

최종 PostgreSQL230개 `20261004T054856Z-986bd582`는 PASS다. 기존 감사 transaction에서
32개 동시 접수/결과의 반환 직후 독립 연결 가시성을 확인하는 시험을 유지했다. StreamRun
시험 namespace도 JVM마다 UUID로 분리해 기존 누적 active Run에 영향을 받지 않게 했다.
최종 API JAR SHA256은 `3968964d694d6291cc69fb60ce545d540b0d0d379f6ae108f65439554f7174b7`로
ADR0066의 검증·배포 JAR과 완전히 같다. 실험 writer 클래스가 없음을 확인했다. 따라서 변경
없는 API/Swagger·단위122·백업/복원 증거는 재사용하고, 변경된 Python SDK와 그 실제 실행
경계는 위 시험 및 최종 실행·저장소 시험으로 다시 검증한다.

최종 실행·저장소47개 `20261004T055136Z-ca618f1f`는 모두 PASS다. 실제 Spring HTTP/
PostgreSQL/TLS MinIO/MQTT·VD supervisor/자식 Runner의 공유/독립 그룹 복구·전환·취소·
고정 S3 결과를 확인했고 소유 DB/MinIO/broker/process를 정리했다. Pod 제출/신원은 fixture다.
이 결과는 이번 최종 코드의 수용 근거이며 원래 간헐적 실패의 모든 원인을 확정하지 않는다.
새 이미지의 CI·실제 Kubernetes 배포는 별도 후속 게이트다.
