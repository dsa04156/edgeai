package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesProfilingGateway;
import io.edgeai.adapters.profiling.HttpInferenceLoadDriver;
import io.edgeai.adapters.repository.JdbcModelProfilingRepository;
import io.edgeai.domain.repository.ModelProfilingRepository;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Model resource measurement. The repository exists with the catalog (models show their measurement state even when
 * measurement is disabled); the Kubernetes gateway and the HTTP load driver exist only when measurement is enabled.
 */
@Configuration
@ConditionalOnProperty(name = "edgeai.ai-models.enabled", havingValue = "true")
class ModelProfilingConfiguration {
    @Bean ModelProfilingRepository modelProfilingRepository(JdbcTemplate jdbc) { return new JdbcModelProfilingRepository(jdbc); }
    @Bean ModelProfilingSettings modelProfilingSettings(@Value("${edgeai.model-profiling.enabled:false}") boolean enabled,
                                                        @Value("${edgeai.model-profiling.namespace:edgeai-model-profiling}") String namespace,
                                                        @Value("${edgeai.model-profiling.service-account:edgeai-model-profiler}") String serviceAccount,
                                                        @Value("${edgeai.model-profiling.max-active-runs:2}") int maxActiveRuns,
                                                        @Value("${edgeai.model-profiling.accelerator-resources:nvidia.com/gpu.shared,nvidia.com/gpu}") String accelerators,
                                                        @Value("${edgeai.model-profiling.scrape-grace-seconds:60}") int grace,
                                                        @Value("${edgeai.model-profiling.collect-timeout-seconds:600}") int collect,
                                                        @Value("${edgeai.model-profiling.kubernetes-timeout-seconds:900}") int kubernetes) {
        return new ModelProfilingSettings(enabled, namespace, serviceAccount, maxActiveRuns, ModelProfilingSettings.list(accelerators),
            Duration.ofSeconds(grace), Duration.ofSeconds(collect), Duration.ofSeconds(kubernetes));
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("${edgeai.model-profiling.enabled:false} and ${edgeai.kubernetes.enabled:false}")
    KubernetesProfilingGateway profilingGateway(ModelProfilingSettings settings, @Value("${edgeai.kubernetes.url}") String url,
                                                @Value("${edgeai.kubernetes.token-file:}") String token, @Value("${edgeai.kubernetes.ca-file:}") String ca) {
        return new KubernetesProfilingGateway(url, token, ca, settings.namespace(), settings.serviceAccount());
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.model-profiling.enabled", havingValue = "true")
    HttpInferenceLoadDriver inferenceLoadDriver() { return new HttpInferenceLoadDriver(); }
}
