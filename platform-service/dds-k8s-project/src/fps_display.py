import sys
import time
import os  # 환경변수 확인용
import cv2
import numpy as np
import fastdds
import Image

class VideoListener(fastdds.DataReaderListener):
    def __init__(self):
        super().__init__()
        self.last_time = time.time()
        self.frame_count = 0

    def on_data_available(self, reader):
        info = fastdds.SampleInfo()
        packet = Image.ImagePacket()

        if reader.take_next_sample(packet, info) == 0:
            if info.valid_data:
                try:
                    # Publisher ID 확인
                    guid = info.sample_identity.writer_guid()
                    guid_prefix = [str(x) for x in guid.guidPrefix.value]
                    pub_id = ".".join(guid_prefix[:4])

                    data_list = packet.data()
                    np_arr = np.frombuffer(bytes(data_list), dtype=np.uint8)
                    frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

                    if frame is not None:
                        self.frame_count += 1
                        current_time = time.time()
                        elapsed = current_time - self.last_time

                        if elapsed >= 1.0:
                            fps = self.frame_count / elapsed
                            print(f"Recv from [PubID:{pub_id}..] | Frame: {packet.frame_id()} | FPS: {fps:.2f}")
                            self.frame_count = 0
                            self.last_time = current_time
                except Exception as e:
                    print(f"Error: {e}")

def run_display():
    # [기능 추가] 환경변수를 읽어서 현재 모드 자동 판별
    profile_env = os.environ.get("FASTRTPS_DEFAULT_PROFILES_FILE", "default")

    mode_str = "Unknown"
    if "udp" in profile_env.lower():
        mode_str = "UDP (Default)"
    elif "tcp" in profile_env.lower():
        mode_str = "TCP/UDP Hybrid"
    elif "shm" in profile_env.lower():
        mode_str = "Shared Memory (SHM)"

    print(f"[*] Initializing Display Subscriber")
    print(f"[*] Current Mode: {mode_str} (Profile: {profile_env})")

    factory = fastdds.DomainParticipantFactory.get_instance()
    factory.load_profiles()
    participant = factory.create_participant_with_profile(0, "participant_profile")

    if participant is None:
        print("[!] Profile load failed, using default.")
        participant = factory.create_participant(0, fastdds.DomainParticipantQos())

    pub_sub_type = Image.ImagePacketPubSubType()
    pub_sub_type.setName("ImagePacket")
    type_support = fastdds.TypeSupport(pub_sub_type)
    participant.register_type(type_support)

    topic = participant.create_topic("ImagePacketTopic", pub_sub_type.getName(), fastdds.TOPIC_QOS_DEFAULT)

    subscriber = participant.create_subscriber(fastdds.SUBSCRIBER_QOS_DEFAULT)
    listener = VideoListener()
    reader = subscriber.create_datareader(topic, fastdds.DATAREADER_QOS_DEFAULT, listener)

    print("[*] Waiting for frames...")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        participant.delete_contained_entities()
        factory.delete_participant(participant)

if __name__ == "__main__":
    run_display()
