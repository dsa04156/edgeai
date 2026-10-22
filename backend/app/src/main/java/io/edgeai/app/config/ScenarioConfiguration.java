package io.edgeai.app.config;

import io.edgeai.adapters.repository.JdbcImageComparisonRepository;
import io.edgeai.adapters.scenario.HttpClassificationRuntime;
import io.edgeai.app.support.ImageComparisonTargets;
import io.edgeai.domain.repository.ImageComparisonRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ScenarioConfiguration {
    @Bean ImageComparisonRepository imageComparisonRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new JdbcImageComparisonRepository(jdbc, new TransactionTemplate(transactions));
    }

    /** Both runtime URLs are server configuration; the browser never selects an upstream. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.scenario.enabled", havingValue = "true")
    ImageComparisonTargets imageComparisonTargets(@Value("${edgeai.scenario.timeout:20s}") Duration timeout,
            @Value("${edgeai.scenario.dataset.manifest-sha256}") String manifestSha256,
            @Value("${edgeai.scenario.dataset.inputs-sha256}") String inputsSha256,
            @Value("${edgeai.scenario.gpu.url}") String gpuUrl, @Value("${edgeai.scenario.gpu.model-name}") String gpuModel,
            @Value("${edgeai.scenario.gpu.model-sha256}") String gpuSha, @Value("${edgeai.scenario.gpu.provider}") String gpuProvider,
            @Value("${edgeai.scenario.npu.url}") String npuUrl, @Value("${edgeai.scenario.npu.model-name}") String npuModel,
            @Value("${edgeai.scenario.npu.model-sha256}") String npuSha, @Value("${edgeai.scenario.npu.provider}") String npuProvider) {
        return new ImageComparisonTargets(List.of(
            new ImageComparisonTargets.Target("GPU", new HttpClassificationRuntime(gpuUrl, timeout), gpuModel, gpuSha, gpuProvider),
            new ImageComparisonTargets.Target("NPU", new HttpClassificationRuntime(npuUrl, timeout), npuModel, npuSha, npuProvider)),
            manifestSha256, inputsSha256);
    }
}
