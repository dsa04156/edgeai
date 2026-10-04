# 복구 중 원본 MQTT 접근 차단

선택한 전용 브로커에서 기존 API의 관리 자격을 교체하고 모든 EdgeAI Device/Task 계정을
비활성화한다. **기존 관리자·장치·Task 연결이 끊어지고 재접속이 거절되는 작업**이다.
[원본 DB 차단](recovery-database-fence.md)과 [실행 중지](recovery-producer-stop.md)에 사용한
복구 UUID를 유지하고 원본 브로커의 endpoint·인증서·설정 digest를 확인한다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-가상환경>/bin/python \
  bash scripts/fence-recovery-mqtt.sh \
  --endpoint ssl://<원본-브로커-hostname>:8883 \
  --ca-file <신뢰-CA-경로> \
  --certificate-sha256 <원본-leaf-인증서-DER-SHA256> \
  --broker-digest sha256:<확인한-브로커-설정-digest> \
  --admin-password-file <원래-admin.password-경로> \
  --recovery-id <이번-복구-UUID> \
  --state-directory .tools/recovery-mqtt-<복구-UUID> \
  --timeout 60 \
  --output .tools/recovery-mqtt-result-<새-실행명>
```

의존성은 `runner/requirements-stream.txt`에 고정한다. hostname은 인증서 SAN과 일치해야
한다. 인증서 SHA256은 PEM 텍스트가 아닌 DER 인증서의 해시이며, broker digest는 해당
설치의 `EDGEAI_STREAM_BROKER_DIGEST`와 같다. CA 검증을 끄는 옵션은 없다.
`edgeai-admin`, 기본 deny4개, group 없는 전용 브로커만 지원한다. 다른 계정이나 group이
섞여 있으면 자동 제거하지 않고 중단한다.

state directory의 `recovery.json`은 **새 관리자 비밀번호를 포함**하므로 Git·공개 evidence·
로그에 넣지 않는다. 소유자 전용 권한을 유지하고 [암호화 파일 백업](private-material-backup.md)에
포함한다. 비밀번호를 터미널에 출력하거나 명령 인자로 옮기지 않는다.

output은 매 실행마다 새 경로를 사용한다. `fence-report.json`의 정상 상태는
`SOURCE_BROKER_CLIENTS_DISABLED`/exit0이며 관리자 교체·기존 자격 거절·계정 수와 차단 상태를
확인한다. timeout/원격 확인 불가는 BLOCKED/exit2, 잘못된 대상·신원·정책 등은 FAIL/nonzero다.

실패해도 자동 해제하지 않는다. `fenceStateUnconfirmed=true`이면 다른 필드는 마지막
관측값이며 현재 전체 차단의 증거가 아니다. **같은 UUID와 state directory**, 원래 관리자
비밀번호 파일, 새 output으로 재개한다. 관리자 교체 직후 응답이 유실됐어도 비공개 상태의
새 자격으로 다시 확인한다. 다른 UUID로 기존 복구 상태를 가져갈 수 없다.

기존 API Secret은 그대로이므로 새 자격을 API에 적용해 즉시 서비스를 재개하지 않는다.
원본 producer 회수와 DB/S3/키/journal 대조·실행 상태 조정이 먼저 필요하다. 재활성화 명령은
아직 없으며 `activated=false`, `globalQuiescenceProven=false`를 유지한다. 보관 메시지와
장치 journal을 삭제하거나 복원하는 기능은 이 명령에 포함하지 않는다.

```bash
EDGEAI_STREAM_PYTHON=<고정-Paho-가상환경>/bin/python \
  bash scripts/test-recovery-mqtt-fence.sh
```

시험은 실제 Mosquitto/mosquitto_ctrl/Dynamic Security plugin·OpenSSL이 필요하다.
비표준 설치는 `EDGEAI_MOSQUITTO_BINARY`, `EDGEAI_MOSQUITTO_CTRL_BINARY`,
`EDGEAI_MOSQUITTO_DYNAMIC_SECURITY_PLUGIN`을 지정한다. 전용 로컬 TLS 프로세스만 생성하며
운영 브로커에 연결하지 않는다. [검증 범위](evidence/m9-recovery-mqtt-fence.md).
