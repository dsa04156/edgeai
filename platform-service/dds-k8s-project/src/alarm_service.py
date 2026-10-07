import sys
import time
import os
import fastdds
import Sensor
import Alarm

class AlarmServiceListener(fastdds.DataReaderListener):
    def __init__(self, alarm_writer):
        super().__init__()
        self.alarm_writer = alarm_writer
        self.alarm_msg = Alarm.AlarmEvent()
        self.alarm_count = 0

    def on_data_available(self, reader):
        info = fastdds.SampleInfo()
        sensor_data = Sensor.SensorData()

        if reader.take_next_sample(sensor_data, info) == 0:
            if info.valid_data:
                try:
                    # [추가] 보낸 센서의 ID(GUID) 확인
                    guid = info.sample_identity.writer_guid()
                    guid_prefix = [str(x) for x in guid.guidPrefix.value]
                    pub_id = ".".join(guid_prefix[:4])

                    temp = sensor_data.value()
                    # [수정] PubID와 함께 출력
                    print(f"[Service] Recv from [PubID:{pub_id}] | Checked Temp: {temp:.2f}")

                    # [로직] 28도 이상이면 알람 발송
                    if temp >= 32.0:
                        self.alarm_msg.id(self.alarm_count)
                        self.alarm_msg.message(f"WARNING: High Temp {temp:.2f}!")

                        self.alarm_writer.write(self.alarm_msg)
                        print(f"    >>> [ALARM SENT] {self.alarm_msg.message()}")
                        self.alarm_count += 1
                except Exception as e:
                    print(f"Error: {e}")

def run_service():
    # [추가] 현재 통신 모드 감지 및 출력
    profile_env = os.environ.get("FASTRTPS_DEFAULT_PROFILES_FILE", "default")
    mode_str = "Unknown"
    if "udp" in profile_env.lower(): mode_str = "UDP"
    elif "tcp" in profile_env.lower(): mode_str = "TCP"
    elif "shm" in profile_env.lower(): mode_str = "SHM (Shared Memory)"

    print(f"[*] Starting Alarm Service")
    print(f"[*] Current Mode: {mode_str} (Profile: {profile_env})")

    factory = fastdds.DomainParticipantFactory.get_instance()
    factory.load_profiles()
    participant = factory.create_participant_with_profile(0, "participant_profile")
    if participant is None:
        participant = factory.create_participant(0, fastdds.DomainParticipantQos())

    # --- Sensor 구독 ---
    sensor_type = Sensor.SensorDataPubSubType()
    sensor_type.setName("SensorData")
    sensor_ts = fastdds.TypeSupport(sensor_type)
    participant.register_type(sensor_ts)

    sensor_topic = participant.create_topic("SensorDataTopic", sensor_type.getName(), fastdds.TOPIC_QOS_DEFAULT)
    subscriber = participant.create_subscriber(fastdds.SUBSCRIBER_QOS_DEFAULT)

    # --- Alarm 발행 ---
    alarm_type = Alarm.AlarmEventPubSubType()
    alarm_type.setName("AlarmEvent")
    alarm_ts = fastdds.TypeSupport(alarm_type)
    participant.register_type(alarm_ts)

    alarm_topic = participant.create_topic("AlarmTopic", alarm_type.getName(), fastdds.TOPIC_QOS_DEFAULT)
    publisher = participant.create_publisher(fastdds.PUBLISHER_QOS_DEFAULT)
    alarm_writer = publisher.create_datawriter(alarm_topic, fastdds.DATAWRITER_QOS_DEFAULT)

    # Listener 연결
    listener = AlarmServiceListener(alarm_writer)
    reader = subscriber.create_datareader(sensor_topic, fastdds.DATAREADER_QOS_DEFAULT, listener)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        participant.delete_contained_entities()
        factory.delete_participant(participant)

if __name__ == "__main__":
    run_service()
