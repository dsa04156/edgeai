# Forklift video replay

This input is a repeating recording, not a live factory camera.

- Original dataset: **Video Dataset for Safe and Unsafe Behaviours**, Oğuzhan Önal and Emre Dandıl, https://data.mendeley.com/datasets/xjmtb22pff/1
- License: [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
- Mirror: https://huggingface.co/datasets/Voxel51/Safe_and_Unsafe_Behaviours
- Pinned revision: `7369ff1830b5f6c00f7d7383eeeaa90704bfc5b5`
- File: `data/3_te2.mp4`, test split, “Carrying Overload with Forklift”.
- Original SHA-256: `99d7ad69638f0b4ebbf3b966f279386ceeadd4521f16ce81cc9b551ab0293c36`
- 11 seconds, 25 fps, 1920×1080. The original video is unmodified and republished over RTSP without audio. The Mapper downsizes decoded frames for inference.

The dataset label is source metadata, not a guaranteed Hawkeye prediction. The UI must show the model's actual output.

Publisher configuration follows https://mediamtx.org/docs/publish/ffmpeg . The MediaMTX image is pinned by its amd64 manifest digest.

`build.sh <registry/image:tag>` downloads and verifies the original clip, then builds the replay image. The deployment is included by the `hawkeye-video-replay` Kubernetes component. The RTSP address is `rtsp://10.254.192.47:18555/forklift-replay` in the current lab. `source.json` is the managed-device registration body for `POST /api/devices` (the dashboard proxy is `/api/control-plane/managed-devices`); use the normal session cookie and CSRF token. Register once, then retain the returned Device UID. A new environment must supply its own reachable host and edge node. The registered device's `ONLINE` state and fresh preview frames, rather than the publisher's TCP readiness alone, establish that decoding is working.
