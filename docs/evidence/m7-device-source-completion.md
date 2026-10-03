# M7 DeviceSource 공동 완료·재시작 검증

2026-10-03 KST. [ADR0039](../adr/0039-device-source-completion.md).
장치 SDK와 내부 서버를 연결한 구성 요소 검증이며 공개 STREAM501과 전체 M7 미완료를 유지한다.

| 검사 | 실행 ID | 결과 |
|---|---|---|
| 실제 HTTP Device 완료 client | 20261003T073827Z-99a49657 | PASS/0, 12개 |
| private intent·실제 SQLite·fanout | 20261003T074017Z-be601c52 | PASS/0, 4개 |
| 실제 HTTPS/TLS DeviceSource 최초 전체 | 20261003T074558Z-cf2bed10 | PASS/0, 14개; 후속 경합 시험은 미포함 |
| 배정 조회→Link 생성 사이 실제 기한 만료 | 20261003T075924Z-6af7483d | PASS/0, 1개 |
| 실제 Spring/PG/S3/TLS MQTT 완료·취소 | 20261003T075924Z-2cf46780 | PASS/0, 2개 |
| 전체 Runner·SDK 회귀 | 20261003T080020Z-87a1d3d2 | PASS/0, 107개·89.847초 |
| 실제 S3/PG·Result/Remote/VD/새 완료 전체 | 20261003T080020Z-49c00942 | PASS/0, 32개·실패/skip0 |
| 실제 Spring/PG/TLS broker 기존 회귀 | 20261003T080220Z-1cb5cdf6 | PASS/0, 23개·실패/skip0 |
| CI와 같은 discovery 방식의 SIGKILL 자식 | 20261003T080403Z-bbbef790 | PASS/0, 1개 |
| 최종 전체 HTTPS/TLS MQTT·모델·Runner·DeviceSource | 20261003T080629Z-748f4867 | PASS/0, 66개·118.835초 |

## 실제로 연결한 경로

`StreamSourceCompletionIntegrationTest`는 격리 PostgreSQL·고유 versioned MinIO bucket과
실제 TLS Mosquitto dynamic-security를 사용한다. Spring 권한 worker가 경로를 활성화하고
장치/Attempt 토큰을 검증한다. 별도 Python 프로세스의 DeviceSource 두 개가 DATA와 END를
송신하고 Session이 실제 계산 subprocess를 실행해 합14를 만든다.

Session은 실제 HTTP uploads/commit·S3 고정 version을 통해 체크포인트를 확정한 뒤에만
처리 확인을 보낸다. 장치 두 개가 종료를 보고해도 Task 보고 전에는 모두 WAITING이며
DB의 grant는 없다. Task가 같은 최신 terminal checkpoint를 보고하면 전체 허가 시각이 같고,
실제 S3 파일 업로드·Result commit이 성공한다. worker의 경로 권한 회수·closedAt을 확인한 뒤
장치 owner를 다시 열어 MQTT 배정/Link 없이 FINALIZE를 재조회한다.

취소 시험은 checkpoint/장치 보고 후 Task 보고 전에 Run을 취소한다. Task HTTP409·
DeviceSource 종료·grant 미기록을 확인한다. 물리 Pod 종료 확인 전 Run은 CANCELLING이다.
시험이 Kubernetes 종료 관측을 대신해 CANCELLED를 만들어 내지는 않는다.

Pod 생성/신원과 Run의 이미 시작한 Runtime 경계는 명시적 fixture다. API와 S3는 격리된
loopback HTTP이며 SDK의 명시적 시험 옵션을 쓴다. MQTT는 실제 TLS다. 별도 HTTPS SDK 시험과
이 연결 시험을 합쳐 운영 TLS 구성이나 전체 Runner/Kubernetes 수용을 검증했다고 주장하지 않는다.
이 시험의 최종 파일은 계산 상태를 확인한 probe가 Result API로 저장한다. 운영 Runner의
최종 파일 subprocess·새 Pod 복구는 후속 종단 게이트다.

SDK 시험은 손실된 완료 응답, 실제 SIGKILL 후 journal 재개, WAITING 재시작/heartbeat,
기한 만료·취소·서버409, 변조된 의도 파일, 응답의 잘못된 generation/순번을 다룬다.
intent 파일은 토큰·MQTT 자격·adapter 상태를 포함하지 않고0600이다. 모든 fanout 종료 ACK가
있어야 저장할 수 있다. 이 시험군의 완료 제어 API는 명시적 HTTPS fixture다.

## 실패와 수정

- `073827Z-29ba512f`: 새 intent parser 괄호 누락으로 import 실패. 수정 후 unit/TLS 실행.
- `075434Z-5f20e3cb`: 새 Java 시험의 DirtiesContext import 누락. 해당 import 수정.
- `075610Z-a4337d09`: 취소 즉시 CANCELLED 기대와 성공 Run의 취소 cleanup이 실제 상태 전이와
  달랐다. CANCELLING을 확인하고 terminal Run은 취소하지 않도록 수정했다.
- `075713Z-60491154`: SDK의 예약된 다음 HTTP poll 전에 한 번의 step만으로 취소 오류를 기대했다.
  다음 poll의 실제 거절과 owner 종료를 기다리고 그동안 completed가 되지 않음을 확인한다.
- `075816Z-12cac996`: 새 배정 후 Link 생성까지 지연시켜 실제 기한 만료를 재현했다. SDK가
  owner 전체를 종료해 이미 발급된 grant를 복구하지 못했다. 전송만 닫고 영속 종료 의도를
  통해 서버 허가만 재조회하도록 수정한 후 동일 경계 시험이 통과했다.
- `080048Z-32b8924a`: MQTT66개 중 SIGKILL 자식만 실패했다. 부모의 unittest discovery 경로는
  자식 Python에 상속되지 않아 SDK/fixture 모듈을 찾지 못했다. 자식에 저장소의 명시적
  PYTHONPATH를 전달하고 CI와 동일한 discovery 방식의 단독 시험으로 수정 효과를 확인했다.
  이후 같은 전체 실행080629Z-748f4867에서66개가 모두 통과했다. 실패 실행 자체를 성공 근거로 사용하지 않는다.

## CI와 선행 서버 배포

새 완료 시험은 `runtimeArtifactIntegrationTest`에 포함하며 일반 PostgreSQL 시험에서는 제외한다.
GitHub Actions storage job은 실제 Mosquitto와 고정 Paho 의존성을 준비한다. 로컬 재현도
PostgreSQL·MinIO 외에 `EDGEAI_STREAM_PYTHON`과 Mosquitto dynamic-security가 필요하다.
V1–V24의 원래 bytes를 대조했고 변경이 없다. V24 SHA256은
`857e0eab074587bfc4c143f852194cc88e0dfcf3ada3b5d0265d34e7e1266cb1`이다.

선행 서버 source46e24ad의 CI37106385090은5 jobs·다운로드 결과JSON17개 모두PASS/0이다.
Runner 컨테이너102개072721Z-104160b5, MQTT58개072917Z-4638a9bf와
kind074036Z-45143e86의 BATCH/Retry/Offload/TLS Remote/VD·고정S3결과20+5를 확인했다.
시험소유 cluster edgeai-ci-abc985c4c580 삭제도 확인했다.
GitOps32a01b4·배포080101Z-08722655에서 정확한API/dashboard/MinIO imageID·Ready·PVCBound·
ArgoSynced·VD활성화PASS다. 공유Ingress 때문에 aggregate health는Progressing이다.
이는 이번 DeviceSource 변경의 새 CI/배포 성공 증거가 아니다.

공개 route/provisioning·그룹 동시 시작, 허가 후 Runner 최종 상태 복구, peer journal 인계,
운영 TLS·API/UI·실제 Kubernetes 스트림 수용, M5 잔여와 M8–M10은 남아 있다.
