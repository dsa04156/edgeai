# linux/amd64. The released platform Runner (deploy/kubernetes/overlays/dev/release.json runnerDigest, source e4b7a6a)
# plus the Hawkeye capture/infer (v1) and snapshot (v2) workloads of the hawkeye-video-safety SERVICE workflow. No RUN step, so the arm64
# rootless builder assembles it without emulation; the Runner entrypoint and user are unchanged.
FROM ghcr.io/dsa04156/edgeai-runner@sha256:ed9a4c7dd286cf845dd4631f90af384f92d6c036f0c18d2d7b512e3a52a1c793
COPY hawkeye_capture.py hawkeye_infer.py hawkeye_snapshot.py /opt/edgeai/hawkeye/
