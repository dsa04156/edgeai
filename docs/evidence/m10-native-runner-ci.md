# M10 native Runner CI 검증

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
