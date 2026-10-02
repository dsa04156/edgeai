FROM python:3.13-slim-bookworm@sha256:5024f48ba9441d4b13a95d3945abc6365538e3a31109833367a1923523c6efed
LABEL org.opencontainers.image.source=https://github.com/dsa04156/edgeai
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /work
COPY --chown=10001:10001 runner/runner.py /opt/edgeai/runner.py
COPY --chown=10001:10001 runner/edgeai_runner /opt/edgeai/edgeai_runner
COPY --chown=10001:10001 runner/examples /opt/edgeai/examples
USER 10001:10001
ENTRYPOINT ["python3", "/opt/edgeai/runner.py"]
