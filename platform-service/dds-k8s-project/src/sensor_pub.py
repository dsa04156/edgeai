import sys
import time
import random
import argparse
import fastdds
import Sensor

def run_sensor(sensor_id):
    # [1] XML 프로파일 강제 로드 (UDP 모드)
    factory = fastdds.DomainParticipantFactory.get_instance()
    factory.load_profiles()
    participant = factory.create_participant_with_profile(0, "participant_profile")

    if participant is None:
        participant = factory.create_participant(0, fastdds.DomainParticipantQos())

    # [2] TypeSupport 적용
    pub_sub_type = Sensor.SensorDataPubSubType()
    pub_sub_type.setName("SensorData")
    type_support = fastdds.TypeSupport(pub_sub_type)
    participant.register_type(type_support)

    # [3] Topic & Writer
    topic = participant.create_topic("SensorDataTopic", pub_sub_type.getName(), fastdds.TOPIC_QOS_DEFAULT)
    publisher = participant.create_publisher(fastdds.PUBLISHER_QOS_DEFAULT)
    writer = publisher.create_datawriter(topic, fastdds.DATAWRITER_QOS_DEFAULT)

    print(f"[*] Sensor {sensor_id} Started (UDP Mode).")

    data = Sensor.SensorData()
    msg_count = 0

    try:
        while True:
            data.id(msg_count)
            data.value(float(random.uniform(20.0, 35.0)))
            data.sensor_name(str(sensor_id))

            writer.write(data)
            print(f"[Sensor] Sent ID: {msg_count}, Temp: {data.value():.2f}")

            msg_count += 1
            if msg_count > 2000000000: msg_count = 0
            time.sleep(1.0)

    except KeyboardInterrupt:
        pass
    finally:
        participant.delete_contained_entities()
        factory.delete_participant(participant)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--sensor_id", type=str, default="sensor1")
    args = parser.parse_args()
    run_sensor(args.sensor_id)
