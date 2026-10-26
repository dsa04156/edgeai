# Forklift video replay

These inputs are repeating recordings, not live factory cameras. The image serves three RTSP paths:

| Path | Clip | Device registration |
|---|---|---|
| `/forklift-replay` | dataset clip `3_te1.mp4` (below), unchanged | `source.json` |
| `/forklift-turn` | YouTube `tYYNdqPooMI`, converted | `source-turn.json` |
| `/forklift-accidents` | YouTube `lSGMB3h41ug`, real CCTV parts only, converted | `source-accidents.json` |

## `/forklift-replay`

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

`build.sh <registry/image:tag>` downloads and verifies the dataset clip, verifies the prepared YouTube clips, then builds the replay image. The deployment is included by the `hawkeye-video-replay` Kubernetes component. The RTSP addresses are `rtsp://10.254.192.47:18555/{forklift-replay,forklift-turn,forklift-accidents}` in the current lab. `source.json` (and `source-turn.json`, `source-accidents.json`) is the managed-device registration body for `POST /api/devices` (the dashboard proxy is `/api/control-plane/managed-devices`); use the normal session cookie and CSRF token. Register once, then retain the returned Device UID. A new environment must supply its own reachable host and edge node. The registered device's `ONLINE` state and fresh preview frames, rather than the publisher's TCP readiness alone, establish that decoding is working.

## `/forklift-turn` and `/forklift-accidents`

Both are from the YouTube channel TEKNECT Global (https://www.youtube.com/channel/UCfGfSiFQUkvZsKDJSE-XHZA), standard YouTube
licence (no Creative Commons grant). They are used for this lab replay only and are not committed to Git; the CC BY 4.0 notice
above does not apply to them. The video titles name accidents; the UI shows only the model's detections and the motion
estimate, never that a pictured accident was predicted.

- `/forklift-turn`: “TOP 5 Forklift Accidents Caused by Sharp Trun | Forklift Fails | Forklift Training”,
  https://www.youtube.com/watch?v=tYYNdqPooMI (uploaded 2025-09-23). Original video-only H.264 960×720 30 fps, 19 s,
  SHA-256 `3903a5a963e617b8dfa3951db27000a3952aa636fa2ee8e9da22662f28efb650`. A montage of five CCTV scenes with hard cuts.
  The whole clip is converted: `tYYNdqPooMI-24fps.mp4`, SHA-256 `8dcfd5fb65b391468d7e7434befc98429adc6970a081cbe1ef9e3c642315c516`,
  446 frames, 18.58 s.
- `/forklift-accidents`: “Forklift Accidents Caught on Camera 8 - Analysis of The Causes of The Accidents and How to Prevent”,
  https://www.youtube.com/watch?v=lSGMB3h41ug (uploaded 2026-01-02). Original video-only H.264 960×720 30 fps, 66 s,
  SHA-256 `875a07573d0d3b5e0eaea5765c638b3df0967787bea0fd97756933f94677d415`. It mixes CCTV with instructional animation; only
  the CCTV source frames `[0,439)`, `[876,1069)` and `[1392,1615)` are kept and concatenated:
  `lSGMB3h41ug-cctv-24fps.mp4`, SHA-256 `de12a1229946289c7a389a0067cf4e7f01898e71f7855f293d489bdec1cf7102`, 684 frames, 28.5 s.

Conversion (both): H.264 baseline, 640×480, 24 fps, `crf 18`, `preset fast`, `yuv420p`, `+faststart`, no audio
(`fps=24,scale=640:480` after `setpts=PTS-STARTPTS` and `concat` of the kept intervals for the second clip). `build.sh` expects the
prepared files in `output/hawkeye-youtube/` and checks their SHA-256; it fails if they are missing.

Camera cuts and the loop wrap are new scenes: the motion observer restarts tracking there (reset `SCENE_CUT`), so no track or
velocity spans two views.
