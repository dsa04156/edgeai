package io.edgeai.app.config;

import io.edgeai.adapters.inference.HttpObjectDetectionRuntime;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "edgeai.inference.enabled", havingValue = "true")
public class InferenceConfiguration {
    @Bean(destroyMethod = "close")
    HttpObjectDetectionRuntime objectDetectionRuntime(@Value("${edgeai.inference.runtime-url}") String url,
            @Value("${edgeai.inference.timeout:20s}") Duration timeout) {
        return new HttpObjectDetectionRuntime(url, timeout);
    }
}
