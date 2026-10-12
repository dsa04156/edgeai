package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesInfrastructureInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
class InfrastructureConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.infrastructure.source", havingValue = "api")
    KubernetesInfrastructureInventory apiInfrastructure(@Value("${edgeai.kubernetes.url}") String url,
        @Value("${edgeai.kubernetes.token-file:}") String token, @Value("${edgeai.kubernetes.ca-file:}") String ca) {
        return new KubernetesInfrastructureInventory(url, token, ca);
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.infrastructure.source", havingValue = "kubectl")
    KubernetesInfrastructureInventory localInfrastructure(@Value("${edgeai.infrastructure.context:}") String context) {
        return new KubernetesInfrastructureInventory(context);
    }
}
