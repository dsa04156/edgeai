# ADR 0095: native amd64·arm64 Runner 시험과 불변 플랫폼 index

상태: 검증 중. 2026-10-05.

두 native 호스트의 Runner111/MQTT97 시험과 원시 artifact 감사는
[통과했다](../evidence/m10-native-runner-ci.md). index 발행·새 이미지 배포는 후속 검증한다.

실제 클러스터에 ARM64와 x86 노드가 있지만 기존 Runner 배포 pin은 linux/amd64 단일
manifest였다. 같은 소스를 각 architecture의 native GitHub runner에서 빌드하고 기존
실제 컨테이너111개·TLS MQTT97개를 각각 실행한다. 두 호스트의 machine, 실제 컨테이너
machine, image config architecture/revision label과 시험 결과를 함께 기록한다.
에뮬레이션 성공을 native 장비 검증으로 보고하지 않는다.

main에서는 각 시험이 통과한 로컬 이미지를 architecture별 tag로 그대로 push하고
그 단일 manifest digest를 기록한다. 후속 job은 동일한 소스의 amd64/arm64 증거가 모두
있을 때만 두 digest를 index로 묶는다. 공개한 index를 digest로 다시 읽어 platform map이
정확히 두 시험 이미지와 일치하는지 검사한다. 이 index가 kind의 기존 실제 실행·복구
시험을 통과한 뒤 GitOps가 index와 각 platform digest를 기록한다.

컨테이너 런타임이 index의 child manifest를 imageID로 보고할 수 있으므로 기존 Runner
Pod 검사는 고정 digest의 registry index를 읽어 해당 immutable child만 허용한다. 단일
manifest의 기존 검사는 유지한다. mutable tag, 다른 child, 중복 platform, 잘못된 manifest는
거절한다. Registry 조회 실패를 임의의 imageID 허용으로 바꾸지 않는다.

GitHub의 [native ARM 호스트 목록](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)과
Docker의 [index 생성](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/),
[manifest 조회](https://docs.docker.com/reference/cli/docker/buildx/imagetools/inspect/) 계약을 따른다.
기존 main CI를 취소하지 않도록 별도 branch ref의 concurrency group에서 native 시험을
먼저 실행한다. 검증 branch는 이미지를 공개하거나 GitOps pin을 변경하지 않는다.

이 검증은 native Runner와 패키지 신원에 대한 것이다. 실제 KubeEdge 장비에서의 공개 API
실행, GPU/NPU 장치 접근, 실제 모델/외부 계약·성능 수용은 별도로 남는다.
