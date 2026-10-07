import sys
import time
import cv2
import argparse
import fastdds
import Image

def run_camera(topic_name, rtsp_url, camera_id):
    print(f"[*] Initializing Camera Publisher: {camera_id}")

    # 1. Participant 생성
    factory = fastdds.DomainParticipantFactory.get_instance()
    # XML 설정을 강제로 로드 (환경변수 의존 제거)
    factory.load_profiles()
    participant = factory.create_participant_with_profile(0, "participant_profile")

    if participant is None:
        # 프로파일 로드 실패 시 기본값으로 생성
        print("[!] Profile load failed, using default.")
        participant = factory.create_participant(0, fastdds.DomainParticipantQos())

    # 2. Type 등록
    pub_sub_type = Image.ImagePacketPubSubType()
    pub_sub_type.setName("ImagePacket")
    type_support = fastdds.TypeSupport(pub_sub_type)
    participant.register_type(type_support)

    # 3. Topic & Writer 생성
    topic = participant.create_topic("ImagePacketTopic", pub_sub_type.getName(), fastdds.TOPIC_QOS_DEFAULT)
    publisher = participant.create_publisher(fastdds.PUBLISHER_QOS_DEFAULT)
    writer = publisher.create_datawriter(topic, fastdds.DATAWRITER_QOS_DEFAULT)

    # 4. 카메라 연결
    cap = cv2.VideoCapture(rtsp_url)

    packet = Image.ImagePacket()
    frame_count = 0

    try:
        while True:
            if not cap.isOpened():
                cap = cv2.VideoCapture(rtsp_url)
                time.sleep(1)
                continue

            ret, frame = cap.read()
            if not ret:
                time.sleep(0.1)
                continue

            # 인코딩 및 전송
            _, encoded = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 50])

            packet.frame_id(frame_count)
            packet.width(frame.shape[1])
            packet.height(frame.shape[0])
            packet.data(list(encoded.tobytes()))

            writer.write(packet)

            if frame_count % 30 == 0:
                print(f"Sent Frame: {frame_count} | Size: {len(packet.data())}")

            frame_count += 1
            if frame_count > 2000000000: frame_count = 0
            time.sleep(0.033)

    except KeyboardInterrupt:
        pass
    finally:
        cap.release()
        participant.delete_contained_entities()
        factory.delete_participant(participant)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--topic", type=str, default="pipe1_video_dev1_raw")
    parser.add_argument("--rtsp", type=str, default="0")
    parser.add_argument("--camera_id", type=str, default="dev1")
    args = parser.parse_args()
    run_camera(args.topic, args.rtsp, args.camera_id)
