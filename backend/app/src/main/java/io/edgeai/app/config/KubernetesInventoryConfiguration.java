package io.edgeai.app.config;
import io.edgeai.adapters.kubernetes.KubernetesNodeInventory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="edgeai.kubernetes.enabled",havingValue="true")
class KubernetesInventoryConfiguration {
    @Bean(destroyMethod="close") KubernetesNodeInventory nodeInventory(
        @Value("${edgeai.kubernetes.url}") String url,@Value("${edgeai.kubernetes.token-file:}") String token,
        @Value("${edgeai.kubernetes.ca-file:}") String ca) { return new KubernetesNodeInventory(url,token,ca); }
}
