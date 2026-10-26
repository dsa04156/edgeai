# Forklift video replay

This input is a repeating recording, not a live factory camera.

- Original dataset: **Video Dataset for Safe and Unsafe Behaviours**, Oğuzhan Önal and Emre Dandıl, https://data.mendeley.com/datasets/xjmtb22pff/1
- License: [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/).
- Mirror: https://huggingface.co/datasets/Voxel51/Safe_and_Unsafe_Behaviours
- Pinned revision: `7369ff1830b5f6c00f7d7383eeeaa90704bfc5b5`
- File: `data/3_te1.mp4`, test split, class 3 “Carrying Overload with Forklift”. Chosen (2026-10-26) over the earlier
  `data/3_te2.mp4` (SHA-256 `99d7ad69…3c36`) because the RT-DETR detector found the forklift in all 20 frames sampled at 2 fps (the earlier clip: only its last 2 seconds),
  moving towards a worker (output/verification/forklift-motion-20261026/selected-video-detections.json).
- Original SHA-256: `79c298ce0cb0eaa9a920285dce299dadc25ebfc737bf1f058c981f6bc2e23517` (24,506,317 bytes)
- 10 seconds, 25 fps, 1920×1080. The original video is unmodified and republished over RTSP without audio. The Mapper downsizes decoded frames for inference.

The dataset label is source metadata, not a guaranteed Hawkeye prediction. The UI must show the model's actual output.

Publisher configuration follows https://mediamtx.org/docs/publish/ffmpeg . The MediaMTX image is pinned by its amd64 manifest digest.

`build.sh <registry/image:tag>` downloads and verifies the original clip, then builds the replay image. The deployment is included by the `hawkeye-video-replay` Kubernetes component. The RTSP address is `rtsp://10.254.192.47:18555/forklift-replay` in the current lab. `source.json` is the managed-device registration body for `POST /api/devices` (the dashboard proxy is `/api/control-plane/managed-devices`); use the normal session cookie and CSRF token. Register once, then retain the returned Device UID. A new environment must supply its own reachable host and edge node. The registered device's `ONLINE` state and fresh preview frames, rather than the publisher's TCP readiness alone, establish that decoding is working.
