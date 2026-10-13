package io.edgeai.app.config;

import io.edgeai.adapters.metrics.MqttObservationSubscriber;
import io.edgeai.domain.device.SensorObservationSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
class SensorArchiveConfiguration {
    @Bean(destroyMethod="close") SensorObservationSubscriber sensorObservationSubscriber(@Value("${edgeai.data-store.raw.client-id:edgeai-archive}") String clientId){return new MqttObservationSubscriber(clientId);}
}
