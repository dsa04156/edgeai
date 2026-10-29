package io.edgeai.app.config;

import io.edgeai.adapters.profiling.KrrBridgeClient;
import io.edgeai.adapters.profiling.PrometheusWorkloadMetrics;
import io.edgeai.adapters.repository.JdbcResourceProfileRepository;
import io.edgeai.domain.repository.ResourceProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "edgeai.resource-profiling.enabled", havingValue = "true")
class ResourceProfilingConfiguration {
    @Bean ResourceProfileRepository resourceProfileRepository(JdbcTemplate jdbc) { return new JdbcResourceProfileRepository(jdbc); }
    @Bean ResourceProfilingSettings resourceProfilingSettings(@Value("${edgeai.resource-profiling.namespaces:edgeai}") String namespaces,
                                                              @Value("${edgeai.resource-profiling.krr.version:v1.31.1}") String krrVersion,
                                                              @Value("${edgeai.resource-profiling.observation-budget-seconds:60}") int observationSeconds,
                                                              @Value("${edgeai.resource-profiling.total-budget-seconds:180}") int totalSeconds) {
        return ResourceProfilingSettings.parse(namespaces, krrVersion, observationSeconds, totalSeconds);
    }
    /** Read-only Prometheus range queries for workload CPU/memory and existing device exporters. */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${edgeai.resource-profiling.prometheus-url:}' != ''")
    PrometheusWorkloadMetrics prometheusWorkloadMetrics(@Value("${edgeai.resource-profiling.prometheus-url}") String url) {
        return new PrometheusWorkloadMetrics(url);
    }
    /** KRR runs in the dedicated recommendation-only bridge; the API never spawns processes. */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${edgeai.resource-profiling.krr.bridge-url:}' != ''")
    KrrBridgeClient krrBridgeClient(@Value("${edgeai.resource-profiling.krr.bridge-url}") String url,
                                    @Value("${edgeai.resource-profiling.krr.timeout-seconds:130}") int timeoutSeconds) {
        return new KrrBridgeClient(url, java.time.Duration.ofSeconds(timeoutSeconds));
    }
}
