package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesInfrastructureInventory;
import io.edgeai.adapters.metrics.EdgeXSensorInventory;
import io.edgeai.adapters.metrics.EdgeXSensorAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
class InfrastructureConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.edgex.enabled", havingValue = "true")
    io.edgeai.adapters.metrics.EdgeXSensorRegistration sensorRegistration(@Value("${edgeai.edgex.metadata-url}") String url) {
        return new io.edgeai.adapters.metrics.EdgeXSensorRegistration(url);
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.edgex.enabled", havingValue = "true")
    EdgeXSensorAccess sensorAccess(@Value("${edgeai.edgex.metadata-url}") String metadata,
        @Value("${edgeai.edgex.data-url}") String data,@Value("${edgeai.edgex.command-url}") String command,java.time.Clock clock) {
        return new EdgeXSensorAccess(metadata,data,command,clock);
    }
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "edgeai.edgex.enabled", havingValue = "true")
    EdgeXSensorInventory sensorInventory(@Value("${edgeai.edgex.metadata-url}") String url) { return new EdgeXSensorInventory(url); }
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
