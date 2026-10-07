FROM eprosima/vulcanexus:humble-desktop

ENV DEBIAN_FRONTEND=noninteractive
WORKDIR /app

# 1. 필수 빌드 도구 설치
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    swig \
    python3-dev \
    python3-pip \
    python3-opencv \
    pkg-config \
    && rm -rf /var/lib/apt/lists/*

# 2. 파일 복사
COPY idl/ /app/idl/
COPY src/ /app/src/
COPY dds_config.xml /app/

COPY dds_client.xml /app/dds_client.xml
COPY dds_udp.xml /app/dds_udp.xml
COPY dds_shm.xml /app/dds_shm.xml
COPY dds_tcp.xml /app/dds_tcp.xml

# 3. IDL 컴파일 및 라이브러리 빌드
WORKDIR /app/src
SHELL ["/bin/bash", "-c"]

# 환경 변수 사전 설정
ENV PYTHONPATH="/app/src"
ENV LD_LIBRARY_PATH="/app/src"




RUN source /opt/vulcanexus/humble/setup.bash && \
    PY_INC=$(python3-config --includes) && \
    PY_LIB=$(python3-config --ldflags) && \
    DDS_INC="-I/opt/vulcanexus/humble/include -I/opt/vulcanexus/humble/include/fastrtps" && \
    DDS_LIB="-L/opt/vulcanexus/humble/lib -lfastrtps -lfastcdr" && \
    \
    echo "=== Building Sensor ===" && \
    fastddsgen -python -cdr v1 /app/idl/Sensor.idl && \
    swig -python -c++ -I/usr/include $DDS_INC Sensor.i && \
    g++ -fPIC -shared -std=c++11 -O0 \
        Sensor_wrap.cxx Sensor.cxx SensorPubSubTypes.cxx \
        -o _Sensor.so \
        $PY_INC $DDS_INC $PY_LIB $DDS_LIB


RUN source /opt/vulcanexus/humble/setup.bash && \
    PY_INC=$(python3-config --includes) && \
    PY_LIB=$(python3-config --ldflags) && \
    DDS_INC="-I/opt/vulcanexus/humble/include -I/opt/vulcanexus/humble/include/fastrtps" && \
    DDS_LIB="-L/opt/vulcanexus/humble/lib -lfastrtps -lfastcdr" && \
    \
    echo "=== Building Image ===" && \
    fastddsgen -python -cdr v1 /app/idl/Image.idl && \
    swig -python -c++ -I/usr/include $DDS_INC Image.i && \
    g++ -fPIC -shared -std=c++11 -O0 \
        Image_wrap.cxx Image.cxx ImagePubSubTypes.cxx \
        -o _Image.so \
        $PY_INC $DDS_INC $PY_LIB $DDS_LIB


RUN source /opt/vulcanexus/humble/setup.bash && \
    PY_INC=$(python3-config --includes) && \
    PY_LIB=$(python3-config --ldflags) && \
    DDS_INC="-I/opt/vulcanexus/humble/include -I/opt/vulcanexus/humble/include/fastrtps" && \
    DDS_LIB="-L/opt/vulcanexus/humble/lib -lfastrtps -lfastcdr" && \
    \
    echo "=== Building Alarm ===" && \
    fastddsgen -python -cdr v1 /app/idl/Alarm.idl && \
    swig -python -c++ -I/usr/include $DDS_INC Alarm.i && \
    g++ -fPIC -shared -std=c++11 -O0 \
        Alarm_wrap.cxx Alarm.cxx AlarmPubSubTypes.cxx \
        -o _Alarm.so \
        $PY_INC $DDS_INC $PY_LIB $DDS_LIB

# 4. 런타임 환경 설정
ENV FASTRTPS_DEFAULT_PROFILES_FILE="/app/dds_tcp.xml"
RUN chmod +x *.py

CMD ["python3"]
