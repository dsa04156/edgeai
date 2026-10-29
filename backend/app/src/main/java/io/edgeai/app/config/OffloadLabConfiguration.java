package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesOffloadLabGateway;
import io.edgeai.domain.runtime.OffloadLabGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;

/** The offload test console load Job needs the runtime namespace and an explicitly configured, digest-pinned lab image. */
@Configuration
@ConditionalOnExpression("${edgeai.runtime.enabled:false} and '${edgeai.offload-lab.load-image:}' != ''")
class OffloadLabConfiguration {
    @Bean OffloadLabGateway offloadLabGateway(RuntimeSettings settings,@Value("${edgeai.kubernetes.url}") String url,@Value("${edgeai.kubernetes.token-file:}") String token,
            @Value("${edgeai.kubernetes.ca-file:}") String ca,@Value("${edgeai.offload-lab.load-image}") String image) {
        return new KubernetesOffloadLabGateway(url,token,ca,settings.namespace(),settings.serviceAccount(),image);
    }
}
