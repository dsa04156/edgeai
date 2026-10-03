# ADR 0039: DeviceSource의 공동 완료 대기와 종료 보고 복구

상태: SDK 구현·실제 Spring/PG/S3/TLS broker 구성 요소 통합 검증. 공개 STREAM은 비활성.

## 완료의 의미

DeviceSource의 `settled`는 모든 송신 경로의 END가 처리 확인됐다는 뜻이다.
`completed`는 각 generation/종료 순번에 대해 인증된 서버가 FINALIZE를 반환했다는 뜻이다.
이 허가는 해당 스트림 그룹의 종료 허가이며 Run의 파일 결과 저장 성공을 대신하지 않는다.
SDK는 기본적으로 공동 완료를 기다린다. 기존 전달 계층만 사용하는 명시적 시험은
`completion=False`로 분리하며, 이 모드에서 `completed`를 성공으로 만들지 않는다.

장치는 END 확인 뒤에도 WAITING 동안 양쪽 heartbeat를 유지한다. 완료 HTTP 요청은
현재 기한과 heartbeat 주기에 맞춰 짧게 제한하며, 일시 오류는 같은 순번으로 재시도한다.
최종 허가를 받거나 전송 권한을 잃으면 MQTT 연결을 닫는다. 완료 보고를 시작한 뒤에는
추가 샘플을 기록할 수 없다. 취소·장치 세션 교체·서버 거절·로컬 전체 기한은 계속 적용한다.

## 재시작과 영속 의도

모든 END가 처리 확인되면 첫 HTTP 보고 전에 0600 `completion.json`을 원자적으로 저장하고
파일·디렉터리를 fsync한다. 저장 내용은 Run/장치 세션·journal manifest·generation/종료 순번이며,
자격 증명·MQTT 비밀번호·샘플·adapter 상태·완료 허가는 포함하지 않는다.
재시작은 이 파일의 소유자·권한·크기·스키마·범위를 확인하고 동일 SQLite journal의 종료 커서와
대조한다. 파일 자체는 권한이 아니므로, 동일 장치 세션의 인증된 완료 API를 반드시 다시 조회한다.

FINALIZE를 다시 받을 수 있으면 이미 닫힌 경로의 배정 조회나 MQTT 연결 없이 끝낸다.
WAITING이면 명시적으로 재시작한 owner만 현재 배정을 새로 조회해 heartbeat를 재개한다.
조회·Link 생성 사이의 기한 만료 또는 경로 회수는 연결을 살리지 않는다. 영속 종료 의도를
보존하고 완료 허가만 재조회한다. 최종 허가 응답을 잃어도 같은 요청으로 복구할 수 있다.

기존 `handover=True`의 같은 장치 세션·논리 경로 인계 조건은 유지한다. 새 Device Session이나
원본 journal 볼륨 손실을 이 종료 의도로 복원할 수는 없다.

## 검증 범위와 남은 작업

[DeviceSource 완료 검증](../evidence/m7-device-source-completion.md)에 실제/fixture 경계를 기록한다.
새 Spring 통합시험은 장치 SDK·TLS MQTT·독립 계산 프로세스·고정 S3 체크포인트·DB 완료 허가·
Result 저장과 경로 회수 후 장치 복구를 연결한다. Kubernetes Pod 생성/신원은 명시적 fixture이고,
공개 Run 생성과 전체 Runner 최종 파일 실행까지 합친 Kubernetes 수용시험은 아니다.

Runner 자체가 허가 뒤 재시작할 때 봉인된 상태로 최종 파일만 복구하는 경로, 공개 route 생성과
그룹 동시 기동, 인접 Task 인계, 운영 TLS·API/UI·실제 Kubernetes 스트림 수용은 계속 필요하다.
DB migration과 HTTP 계약은 ADR0038/V24를 그대로 사용한다. 적용된 V1–V24는 변경하지 않는다.
