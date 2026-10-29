# Recommendation-only KRR bridge (3-3 resource profiling). Build context: profiling-bridge/
# Base: official robusta-dev/krr v1.31.1 image pinned to its multi-arch index digest (linux/amd64, linux/arm64).
FROM robustadev/krr:v1.31.1@sha256:835bf3b6fec4d8f27d96d236a36542bd87bfcb4f12f3537f8dce7f886916ed9c
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 HOME=/tmp \
    KRR_COMMAND="python /app/krr.py" KRR_VERSION=v1.31.1 KRR_BRIDGE_PORT=8080
COPY --chown=10001:10001 krr_bridge.py /opt/edgeai/krr_bridge.py
WORKDIR /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python", "/opt/edgeai/krr_bridge.py"]
