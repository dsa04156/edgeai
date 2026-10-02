package io.edgeai.app.config;

import io.edgeai.adapters.kubernetes.KubernetesVDGateway;
import io.edgeai.app.service.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.VDGateway;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name={"edgeai.runtime.enabled","edgeai.vd.enabled"},havingValue="true")
class VDConfiguration {
    @Bean(destroyMethod="close") VDGateway vdGateway(RuntimeSettings settings,@Value("${edgeai.kubernetes.url}") String url,
            @Value("${edgeai.kubernetes.token-file:}") String token,@Value("${edgeai.kubernetes.ca-file:}") String ca){
        return new KubernetesVDGateway(url,token,ca,settings.namespace(),settings.serviceAccount());
    }
    @Bean VDTokenService vdTokenService(@Value("${edgeai.runtime.key-file}") String file){return new VDTokenService(file);}
    @Bean @ConditionalOnProperty(name="edgeai.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
    VDTaskWorker vdTaskWorker(RuntimeRepository runtimes,VDTaskService tasks,RuntimeSettings settings){return new VDTaskWorker(runtimes,tasks,settings);}
    @Bean @ConditionalOnProperty(name="edgeai.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
    VDWorker vdWorker(VDRuntimeRepository runtimes,ProfileRepository profiles,VDLifecycleService lifecycle,VDGateway gateway,VDTokenService tokens,RuntimeSettings settings,Clock clock){
        return new VDWorker(runtimes,profiles,lifecycle,gateway,tokens,settings,clock);
    }
}
