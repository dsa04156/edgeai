# 원본 DDS 이미지 빌드 문맥

`/home/jinuk/codex-work/jinuk/Platform-Service/dds-k8s-project`의 Dockerfile,
IDL, Python 소스, DDS XML, Buildkit 설정을 가져왔다. 원본 DDS 동작을 유지한다.
생성된 바이너리와 Python 가상환경은 포함하지 않는다.

`sensor_pub.py`는 합성 센서 값을 발행한다. 실제 EdgeX 센서 연결을 뜻하지 않는다.
이미지는 FastAPI의 Buildx 단계에서 명시적으로 빌드·푸시한다.
