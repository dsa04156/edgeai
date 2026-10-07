import sys
import time
import os
import fastdds
import Alarm

class MonitorListener(fastdds.DataReaderListener):
    def __init__(self):
        super().__init__()

    def on_data_available(self, reader):
        info = fastdds.SampleInfo()
        alarm_data = Alarm.AlarmEvent()

        if reader.take_next_sample(alarm_data, info) == 0:
            if info.valid_data:
                try:
                    # [추가] 보낸 서비스의 ID(GUID) 확인
                    guid = info.sample_identity.writer_guid()
                    guid_prefix = [str(x) for x in guid.guidPrefix.value]
                    pub_id = ".".join(guid_prefix[:4])

                    # [수정] PubID와 함께 출력
                    print(f"🚨 [MONITOR] Recv from [PubID:{pub_id}] | ALERT! ID:{alarm_data.id()} | {alarm_data.message()}")
                except Exception as e:
                    print(f"Error: {e}")

def run_monitor():
    # [추가] 현재 통신 모드 감지 및 출력
    profile_env = os.environ.get("FASTRTPS_DEFAULT_PROFILES_FILE", "default")
    mode_str = "Unknown"
    if "udp" in profile_env.lower(): mode_str = "UDP"
    elif "tcp" in profile_env.lower(): mode_str = "TCP"
    elif "shm" in profile_env.lower(): mode_str = "SHM (Shared Memory)"

    print(f"[*] Starting Alarm Monitor")
    print(f"[*] Current Mode: {mode_str} (Profile: {profile_env})")

    factory = fastdds.DomainParticipantFactory.get_instance()
    factory.load_profiles()
    participant = factory.create_participant_with_profile(0, "participant_profile")
    if participant is None:
        participant = factory.create_participant(0, fastdds.DomainParticipantQos())

    # TypeSupport
    alarm_type = Alarm.AlarmEventPubSubType()
    alarm_type.setName("AlarmEvent")
    type_support = fastdds.TypeSupport(alarm_type)
    participant.register_type(type_support)

    # Topic & Reader
    topic = participant.create_topic("AlarmTopic", alarm_type.getName(), fastdds.TOPIC_QOS_DEFAULT)
    subscriber = participant.create_subscriber(fastdds.SUBSCRIBER_QOS_DEFAULT)
    listener = MonitorListener()
    reader = subscriber.create_datareader(topic, fastdds.DATAREADER_QOS_DEFAULT, listener)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        participant.delete_contained_entities()
        factory.delete_participant(participant)

if __name__ == "__main__":
    run_monitor()
