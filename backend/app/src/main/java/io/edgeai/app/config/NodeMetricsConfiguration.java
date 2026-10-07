package io.edgeai.app.config;

import io.edgeai.adapters.metrics.PrometheusNodeMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "edgeai.prometheus.enabled", havingValue = "true")
class NodeMetricsConfiguration {
    @Bean(destroyMethod = "close")
    PrometheusNodeMetrics prometheusNodeMetrics(@Value("${edgeai.prometheus.url}") String url) {
        return new PrometheusNodeMetrics(url);
    }
}
