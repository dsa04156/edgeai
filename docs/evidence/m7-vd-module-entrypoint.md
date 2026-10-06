# VD Runner 모듈 실행 경로 검증

2026-10-05 구현·시험, 2026-10-06 원격 결과 확인. VD supervisor는 자식을
`python -m edgeai_runner.main`으로 시작한다. 이때 `__main__`의 RunnerError·cancelled와
SDK가 import한 `edgeai_runner.main`의 객체가 달라, 완료 요청의 일시 오류를
SDK가 잡지 못하고 Runner가 `CONTROL_PLANE_UNAVAILABLE`로 종료했다.

기존 파일 진입점과 같은 canonical 모듈의 main으로 시작하도록 수정했다.
실제 HTTPS·TLS MQTT·Runner/model subprocess를 사용하며 서버 응답·저장소는 fixture다.
새 시험은 supervisor와 같은 모듈 명령·명시적 PYTHONPATH로 자식을 실행한다.
supervisor 전체나 Kubernetes 수용을 대신하는 검증은 아니다.

| 검사 | 결과 |
|---|---|
| 수정 전 `20261005T081228Z-a6c5a2db` | 새3개 모두 FAIL, 완료503/응답지연에서 조기 종료 |
| 수정 후 `20261005T081339Z-75891337` | 새5개 PASS: 503재시도·허가지연/경로회수·취소·신원거절·기한 |
| 이미지 발행 gate `20261005T081625Z-7cb6f265` | 8개 PASS, 양쪽 native의111+112개 요구/이전107개 거절 |
| 전체 STREAM `20261005T081445Z-8ece4899` | 112개 중1실패·16오류, 전체 FAIL. 새5개는 통과 |

전체 실패에는 Processor의 느린 sink 진전 대기, Session/DeviceSource의 초기
AssignmentFenced와 lease 만료가 있다. 원인은 미확정이며 재실행 통과로 대체하지 않는다.
코드·시험 SHA256은 `.tools/vd-module-entrypoint-source.json`에 보존했다.
기본 Runner `20261006T014423Z-c1835cc3`도111개 중1오류로 FAIL이다. 측정503
서브케이스가 첫 resource sample의 `latencyMicros=None`을 양수와 비교했다.
실제 Spring/Kubernetes와 수정 뒤 전체 회귀는 별도로 확인해야 한다.
현재 변경은 로컬이며 아직 커밋·push·배포하지 않았다.

## 시험 준비와 측정 조건 정비

최초 임대가 발급된 뒤 peer journal을 준비하던 fixture에 각2.1초 준비 지연을 넣었다.
`20261006T015052Z-39a2a446`에서는 Session/Device 첫 조회 전에 각각4.432초·0.153초
만료되어2개 모두 FAIL이었다. peer 저장소를 먼저 준비하고 fixture가 최초 권한을
발급하도록 순서를 바꿨다. 발급된 임대를 갱신하거나2초 기간·SDK guard를 변경하지 않는다.

첫 수정 검증 `20261006T015154Z-9b7209d2`는 Session 통과/Device 실패다.
Device는 첫 조회 전1.993초가 남았지만 SDK journal 생성 중 만료됐다. 이후 생성 시간을
추가 관측한 `20261006T015313Z-797fd727`은 같은 준비 지연 아래2개 PASS이며
최초 잔여1.992/1.994초, 실제 SDK journal 생성0.040/0.041초였다.
이 통과로 앞선 SDK 생성 지연의 근본 원인이 해결됐다고 판단하지 않는다.

측정 fixture는 첫 sample에 작업 지연이 반드시 있다는 가정을 제거한다.
성공 작업은 실제 양수 latency가 관측되어야 하고, 첫 resource sample에서 받은 신원
거절은 작업을 즉시 종료할 수 있다. 실제 workload의 지연 기록 시작을1.25초 늦춘
`20261006T015427Z-9dc17796` PASS: 네 응답 경로 모두 첫 latency는 None,
정상/503/측정만료 경로에는 이후 양수3개와 Result1개가 있고 신원거절은 Result0개다.
Sampler의 누락값 처리·API 상태 처리·취소 동작은 변경하지 않았다.

전체 회귀 재실행 `20261006T015502Z-04d1d016`은 중단되어 `result.json`이 없다.
남은 로그에는 VD 취소 시험의 FAIL 표시가 있으나 상세 traceback과 전체 결과는 없다.
이를 PASS로 취급하지 않는다. 재개 후 같은 취소 시험만 실행한
`20261006T020156Z-a24f3c64`는1개 PASS(6.263초)다. 앞선 실패 원인은 미확정이며
소스 변경 없이 전체 회귀를 다시 확인한다.

재실행 `20261006T020215Z-2f10ae42`는112개 중111개 통과/Session timeout1오류다.
신규 VD5개와 이전 Processor/Source 실패 항목은 모두 통과했다. 남은 시험은0.8초
세션 안에 MQTT 준비·입력 저장·모델 시작이 끝난다고 가정하여, 모델 시작 대기 중의
정상 `STREAM_SESSION_TIMEOUT`을 실패로 처리했다.
단독 시간 관측 `20261006T020820Z-69169947`은 PASS였으므로 호스트 지연 원인은
확정하지 않는다. 앞선 입력 지연 probe `20261006T020753Z-1cb76626`은 입력 주입 전
연결 준비에서 이미 만료되어, 입력 저장 지연이 원인이라는 근거로 사용하지 않는다.

실제 HTTPS heartbeat 직후0.9초 정지하는 probe는 기존 시험에서
`20261006T020907Z-ef8cc4a7` FAIL, 시험 조건 정리 후
`20261006T020919Z-96831d22` PASS다.0.8초와 SDK는 그대로 유지한다.
시험은 heartbeat가 실제 성공했고 원래 deadline이 바뀌지 않았으며 정확한 세션
시간 초과로 transport가 닫혔음을 요구한다. 모델을 먼저 실행해야 한다는 준비 조건은
제거하며, 바로 다음 processor-deadline 시험에서 실제 자식 종료를 계속 확인한다.

마지막 Session 묶음 `20261006T021010Z-12dacb6a`는20개 중17개 통과/3오류다.
수정한 heartbeat deadline 및 실제 processor deadline/자식 종료는 통과했다.
오류는 외부 checkpoint 전달 중 `STREAM_ASSIGNMENT_EXPIRED`1개와 Session의
MQTT Link 생성 전 `MqttAuthorityExpired`2개다. 시험 중 호스트 I/O pressure
avg10 some50.29/full43.71을 관측했으나 해당 지연의 원인이나 개별 실패와의
인과관계는 확정하지 않는다. 현재 로컬 전체 회귀가 통과했다고 판정하지 않는다.

기본 Runner 후속 `20261006T021216Z-aeb58cd7`은111개 실행/측정 시험2개
서브케이스 실패다. 정상200·일시503 경로에서 프로세스 종료 코드가 기대0 대신1이었다.
최초 None latency 비교 오류와는 다른 실패이며 원인은 미확정이다.

선행 d39236f의 CI37281707257은 storage·scaffold·양쪽native·index가 성공했고
images의 실제 kind 시험에서 `vd-distinct-second`/SourceError로 실패하여 GitOps를 생략했다.
원시 `vd_stream_acceptance.py:252`는 **END를 보낸 뒤 완료를 기다리는 위치**다.
phase 문자열이 second에 머무르므로 END 이전 실패라고 추정하면 안 된다.
이 경로의 `RUNNER_FAILED`가 이번 모듈 문제인지 새 이미지로 재검증해야 한다.

같은 CI의 Remote Result 복구15개는 PASS이며 요청 거절5.12초·전체검사14.69초,
소유DB 정리 true다. 이전 잠금 시간 수정의 Compose 경로가 확인됐다.
원시 파일은 `.tools/ci-37281707257/`에 있다. M5 잔여·M7–M10 전체 목표는 유지한다.
