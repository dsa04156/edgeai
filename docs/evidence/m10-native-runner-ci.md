# M10 native Runner CI 검증

## main의 검증된 두 플랫폼 이미지 발행

소스 `60c8be3e5bd0970ea83d15941ba3ec6761b8a240`의
[CI 37223827513](https://github.com/dsa04156/edgeai/actions/runs/37223827513)에서
amd64·arm64 native job과 index 발행 job이 성공했다. 내려받은 각 플랫폼의 원시 시험
결과와 provenance, 발행된 immutable index를 `20261004T182346Z-6cf4c91c`에서
대조했다. 각 플랫폼 Runner111개·실제 TLS MQTT97개, 총 원시 결과4개가 PASS다.

| 항목 | 고정 digest |
| --- | --- |
| Runner index | `sha256:8a2f8067c0bd3d5a10e76b2c46e145b045d8a0789c3f01c10984e1af915ac2ff` |
| linux/amd64 | `sha256:dd466cb3eee8acae1296803be03d0bd8318d6845193de701fc96e0aeb4ff5151` |
| linux/arm64 | `sha256:c355f3b5060f55459fd309df081a4cca1d29792af6bd59bb9b012954308b9a26` |

서로 다른 실제 native host/container와 동일 source를 확인했고, index에는 독립 시험을
통과한 두 manifest만 포함된다. 이 시점의 전체 CI와 새 API/GitOps 배포는 아직 진행 중이다.
이 index로 기존 API 배포에서 수행한 [ARM GPU 실행](m10-hardware-runtime.md)은 별도 근거다.

## 공개 ARM STREAM→BATCH 실행

`20261004T182458Z-f434c88e`에서 위 index로 기존 TLS API에 AUTO·NODE·cancel
STREAM DAG를 요청했다. 실제 ARM Runner8Pod, 고정 S3결과6개, checkpoint의 상태 SHA,
Attempt/Pod/Node 신원과 결과 계산을 확인했다. 세 case 모두 PASS이며 생성한 source-driver
Pod/ConfigMap과 runtime Job/Pod/Secret이 제거됐다. 공개 실행 이력은 보존했다.

driver machine은 `aarch64`, 관측한8개 Runner의 Node는 `etri-ser0003-cg0ms0`다.
이 실행은 ARM 서버이며 KubeEdge 실행을 뜻하지 않는다. API image는
`ghcr.io/dsa04156/edgeai-api@sha256:37974615f6034c2a230aaca567960d4df4a0a2700753f71718f4778a76fd6edb`로
선행 배포를 사용했다. 새 API/GitOps 배포와 실제 모델 수용은 별도로 확인한다.

이어 `20261004T182820Z-f547a037`에서 `--edge-only --node etri-dev0005-jetagx`로
같은 AUTO·NODE·cancel 시나리오를 수행했다. source-driver와 모든 Runner에 엣지/arm64
selector를 적용하고 실제 Node label·architecture·imageID를 관측했다. 실행된8개 Runner는
모두 `etri-dev0005-jetagx`였고 고정 S3결과6개·checkpoint·계산·취소·소유 정리가 PASS다.
실제 KubeEdge에서의 플랫폼 경로를 증명하며, 입력은 SYNTHETIC이다. 장치 단절 중 복구나
실제 모델/외부 시스템 성능 수용을 이 결과로 대체하지 않는다.

## 선행 branch 검증

ADR0095의 소스 `6853fd87dc5f743c7c862296f53b8ffa6f337720`에 대해
[CI 37220159508](https://github.com/dsa04156/edgeai/actions/runs/37220159508)의
두 native Runner job과 다운로드한 원시 artifact를 대조했다.
감사 `20261004T173754Z-71f9acbd` PASS다.

전체 branch CI 종료 뒤 감사 `174542Z-e978bc85`에서4개 job 성공·main 전용3개 생략,
원시 결과35개 PASS/exit0·PostgreSQL230개·단위122개(failure/error/skip0)를 확인했다.

| 플랫폼 | 실제 호스트/컨테이너 machine | Runner | 실제 TLS MQTT |
| --- | --- | --- | --- |
| linux/amd64 | x86_64 / x86_64 | 111개 PASS | 97개 PASS |
| linux/arm64 | aarch64 / aarch64 | 111개 PASS | 97개 PASS |

두 job의 완료 상태, 각 원시 결과2개와 skip 없는 실행 수, source label과 image config digest,
host/container architecture를 확인했다. branch 시험이므로 publishedDigest는 null이다.
이 근거는 전체 workflow·이미지 index 발행·GitOps 배포 완료를 뜻하지 않는다.

위 첫 감사는 native job 범위이며, 후속 전체 branch 감사도 index 발행·GitOps 배포는
포함하지 않는다. `demo-multidevice.py`에 architecture·명시 NODE 이름·edge-only 선택을
추가했다. 프로필과 실제 Runner/driver 배치에 같은 architecture/selector를 적용하고,
관측한 Node architecture와 실제 driver machine을 검사한다. ARM 요청은 multi-platform
index가 없으면 자원 생성 전에 거절한다. 기존 amd64 공개 API 데모
`174227Z-17088dc7`는 AUTO/NODE/cancel·Runner8Pod·고정 S3결과6개·정리 PASS다.
이 후속 데모 변경은 위6853fd87 branch 소스에 포함되지 않으며 ARM 공개 API 데모는 후속이다.

고정 이미지/플랫폼/시험 신원 거절 단위8개 `171902Z-3a3cc830` PASS와 실제 registry index 조회도
확인했다. 변경된 Pod imageID 검사로 기존 배포 Runner를 실행한 Kubernetes AUTO/VD-shared
회귀 `172125Z-481e3fb3`도 PASS다. 실제 고정 S3 결과6개의 bytes/SHA/계산과 소유 자원 정리를
확인했다. 새 index의 실제 kind/배포 검증은 main CI 발행 이후에 수행한다.

native package 검증은 [실제 장비 구성 요소](m10-hardware-components.md)와 구분한다.
공개 플랫폼 API에서 실제 모델을 수행하는 전체 M10 수용은 미완료다.
