# linux/arm64 only (requirements.txt is locked for aarch64 cp312). Build context: inference/
# Optional `--build-context wheels=<dir>` supplies the locked wheels locally; hashes are checked either way.
FROM scratch AS wheels

FROM python:3.12-slim-bookworm@sha256:6a0e06f5ba0b373d051aabd3fffb9b873f27f9a2a3a14bcbe37dee6f0e3bbc70
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY requirements.txt /opt/edgeai/requirements.txt
RUN --mount=type=bind,from=wheels,target=/wheels \
    if ls /wheels/*.whl >/dev/null 2>&1; then \
      source="--no-index --find-links /wheels"; \
    else \
      source="--index-url https://download.pytorch.org/whl/cu130 --extra-index-url https://pypi.org/simple"; \
    fi; \
    pip install --no-cache-dir --require-hashes --only-binary=:all: $source -r /opt/edgeai/requirements.txt
COPY fetch_weights.py /opt/edgeai/fetch_weights.py
RUN python3 /opt/edgeai/fetch_weights.py dd69338a24b8d7381807e247652bdc356325bcbaf1cd3e092e00e0a1a58706bf
COPY --chown=10001:10001 edgeai_inference /opt/edgeai/edgeai_inference
WORKDIR /opt/edgeai
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python3", "-m", "edgeai_inference.server"]
