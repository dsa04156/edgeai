package io.edgeai.app.config;

import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.service.*;
import io.edgeai.domain.repository.DataRouteRepository;
import io.edgeai.domain.repository.StreamRunRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Opt-in broker authority only; this flag does not enable public STREAM execution. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="edgeai.stream.enabled",havingValue="true")
public class StreamConfiguration {
    @Bean MosquittoStreamBroker streamBroker(Clock clock,
            @Value("${edgeai.stream.broker-url}") String endpoint,@Value("${edgeai.stream.admin-user}") String username,
            @Value("${edgeai.stream.admin-password-file}") String password,@Value("${edgeai.stream.ca-file}") String ca,
            @Value("${edgeai.stream.principal-key-file}") String key,@Value("${edgeai.stream.broker-digest}") String digest){
        return new MosquittoStreamBroker(endpoint,username,Path.of(password),Path.of(ca),Path.of(key),digest,clock);
    }
    @Bean(name="streamTaskScheduler") ThreadPoolTaskScheduler streamScheduler(){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("edgeai-stream-scan-");return scheduler;
    }
    @Bean(destroyMethod="close") StreamAuthorityWorker streamAuthorityWorker(DataRouteRepository routes,DataRouteService lifecycle,MosquittoStreamBroker broker,
            @Value("${edgeai.stream.broker-digest}") String digest,@Value("${edgeai.stream.concurrency:4}") int concurrency,
            @Value("${edgeai.stream.scan-size:64}") int pageSize){
        return new StreamAuthorityWorker(routes,lifecycle,broker,digest,concurrency,pageSize);
    }
    @Bean
    @ConditionalOnProperty(name="edgeai.stream.runs-enabled",havingValue="true")
    StreamRunWorker streamRunWorker(StreamRunRepository repository,StreamRunService streams,RuntimeLifecycleService runtimes,
            @Value("${edgeai.runtime.namespace}") String namespace){return new StreamRunWorker(repository,streams,runtimes,namespace);}
}
