# Recommendation-only KRR bridge (3-3 resource profiling). Build context: profiling-bridge/
# Base: official robusta-dev/krr v1.31.1 image pinned to its multi-arch index digest (linux/amd64, linux/arm64).
FROM robustadev/krr:v1.31.1@sha256:835bf3b6fec4d8f27d96d236a36542bd87bfcb4f12f3537f8dce7f886916ed9c
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
# The upstream image has no USER (root) and `krr version` prints "dev"; run as 10001 and record the pinned base.
# KRR writes only its report and matplotlib/config caches under HOME/TMPDIR, so /tmp is the only writable path.
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp TMPDIR=/tmp \
    KRR_COMMAND="python /app/krr.py" KRR_VERSION=v1.31.1 KRR_BRIDGE_PORT=8080 \
    KRR_IMAGE=robustadev/krr:v1.31.1@sha256:835bf3b6fec4d8f27d96d236a36542bd87bfcb4f12f3537f8dce7f886916ed9c
COPY --chown=10001:10001 krr_bridge.py /opt/edgeai/krr_bridge.py
WORKDIR /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python", "/opt/edgeai/krr_bridge.py"]
