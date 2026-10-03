# ADR 0042: Runtime HTTPS 신뢰 번들과 fsGroup 스트림 디렉터리

2026-10-03. 내부 TLS 연결을 실제 Kubernetes Runner에 전달한다. 전체 M7 수용 완료와 구분한다.

## HTTPS 신뢰 설정

플랫폼 설정 `EDGEAI_RUNTIME_CA_CONFIG_MAP`은 runtime namespace 안의 배포 소유 ConfigMap을
선택한다. 해당 ConfigMap의 공개 인증서 번들 `ca.crt`만 Runner/VD Pod의
`/var/run/edgeai-trust/ca.crt`에 읽기 전용으로 마운트하고 `SSL_CERT_FILE`로 전달한다.
Kubernetes DNS 이름을 검증하며 다른 namespace·파일 경로·임의 환경 변수나 Secret을 선택할 수 없다.
API와 S3의 HTTPS 인증서 검증을 유지한다. MQTT CA는 기존 인증된 배정의 broker CA를 사용한다.

기본값은 빈 문자열이며 이 경우 기존 Pod 문서와 시스템 HTTPS 신뢰 설정을 유지한다.
SERVICE Profile은 신뢰 번들을 선택하거나 검증을 끄는 필드를 받지 않는다.
Runner ServiceAccount의 권한은 추가하지 않는다. ConfigMap 준비는 배포 소유자가 담당한다.
번들에는 API·S3에 필요한 CA를 포함하며, 변경 시에는 불변 ConfigMap에 새 이름을 사용한다.

VD는 선택한 이름을 기존 불변 configuration과 digest에 포함한다. 이전 configuration은 새 필드를
추가하지 않은 채 그대로 읽고 digest를 유지한다. 대기 중인 VD의 고정 이름과 현재 worker 설정이
다르면 다른 신뢰 설정으로 실행하지 않는다. 이미 사용하는 번들은 해당 runtime이 끝날 때까지 유지한다.
DB DDL 변경은 없으며 V1–V25를 수정하지 않는다.

## fsGroup 작업 볼륨

실제 Kubernetes `fsGroup=10001` emptyDir의 디렉터리는 setgid이다. 그 아래 `mkdir(0700)`은
setgid를 상속해 실제2700이 된다. SDK는 세션 폴더의 정확한0700을 요구하므로 기존 Runner는
claim 후 지속 계산을 시작하기 전에 실패했다. 실제 Pod에서 UID10001·parent2777·child2700을 확인했다.

Runner는 **자신이 방금 새로 만든** stream 디렉터리에0700을 명시한다. 이미 있는 디렉터리를 열거나
권한을 임의로 바꾸지 않으며 Session/Journal/Processor의 기존 소유자·mode 검사를 유지한다.
SDK 사용자인 시험 DeviceSource도 자신이 만든 폴더를 같은 방법으로 준비한다.

검증 및 현재 Kubernetes 수용 상태는 [검증 기록](../evidence/m7-kubernetes-stream.md)을 따른다.
