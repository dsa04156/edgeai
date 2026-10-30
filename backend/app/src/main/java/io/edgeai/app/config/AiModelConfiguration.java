package io.edgeai.app.config;

import io.edgeai.adapters.registry.DistributionRegistryClient;
import io.edgeai.adapters.repository.JdbcAiModelRepository;
import io.edgeai.domain.repository.AiModelRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(name = "edgeai.ai-models.enabled", havingValue = "true")
class AiModelConfiguration {
    @Bean AiModelRepository aiModelRepository(JdbcTemplate jdbc) { return new JdbcAiModelRepository(jdbc); }
    /**
     * The one registry the catalog may read; browsers only pick repositories/tags/digests inside it. Unset by default,
     * so image lookup and registration are disabled until the deployment names its registry explicitly.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${edgeai.ai-models.registry.url:}' != ''")
    DistributionRegistryClient aiModelRegistry(@Value("${edgeai.ai-models.registry.url}") String url,
                                               @Value("${edgeai.ai-models.registry.timeout-seconds:10}") int timeoutSeconds,
                                               @Value("${edgeai.ai-models.registry.username:}") String username,
                                               @Value("${edgeai.ai-models.registry.password-file:}") String passwordFile) throws IOException {
        String password = passwordFile.isEmpty() ? null : Files.readString(Path.of(passwordFile), StandardCharsets.UTF_8).strip();
        return new DistributionRegistryClient(url, Duration.ofSeconds(timeoutSeconds), username.isEmpty() ? null : username, password);
    }
}
