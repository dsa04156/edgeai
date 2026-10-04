package io.edgeai.app.config;
import io.edgeai.adapters.kubernetes.*;
import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.app.service.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeGateway;
import io.edgeai.domain.storage.ArtifactStore;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="edgeai.runtime.enabled",havingValue="true")
class RuntimeConfiguration {
    @Bean(name="taskScheduler") ThreadPoolTaskScheduler runtimeScheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(5);scheduler.setThreadNamePrefix("edgeai-controller-");return scheduler;}
    @Bean RuntimeSettings runtimeSettings(@Value("${edgeai.runtime.namespace}") String namespace,@Value("${edgeai.runtime.service-account}") String account,
            @Value("${edgeai.runtime.control-plane-url}") String url,@Value("${edgeai.runtime.dispatch-seconds:120}") int timeout,
            @Value("${edgeai.runtime.ca-config-map:}") String caConfigMap){return new RuntimeSettings(namespace,account,URI.create(url),timeout,caConfigMap);}
    @Bean(destroyMethod="close") RuntimeGateway runtimeGateway(RuntimeSettings settings,@Value("${edgeai.kubernetes.url}") String url,
            @Value("${edgeai.kubernetes.token-file:}") String token,@Value("${edgeai.kubernetes.ca-file:}") String ca){
        return new KubernetesRuntimeGateway(url,token,ca,settings.namespace(),settings.serviceAccount());
    }
    @Bean RunnerTokenService runnerTokenService(@Value("${edgeai.runtime.key-file}") String file){return new RunnerTokenService(file);}
    @Bean(destroyMethod="close") S3ArtifactStore artifactStore(Clock clock,@Value("${edgeai.storage.endpoint}") String endpoint,
            @Value("${edgeai.storage.runner-endpoint}") String runnerEndpoint,@Value("${edgeai.storage.access-key}") String access,
            @Value("${edgeai.storage.secret-key}") String secret,@Value("${edgeai.storage.bucket}") String bucket){return new S3ArtifactStore(endpoint,runnerEndpoint,access,secret,bucket,clock);}
    @Bean ArtifactCommitService artifactCommitService(RuntimeLifecycleService lifecycle,ArtifactStore store){return new ArtifactCommitService(lifecycle,store);}
    @Bean RuntimeResultPublicationRepository runtimeResultPublications(org.springframework.jdbc.core.JdbcTemplate jdbc){return new io.edgeai.adapters.repository.JdbcRuntimeResultPublicationRepository(jdbc);}
    @Bean RuntimeResultPublisher runtimeResultPublisher(RuntimeRepository runtimes,io.edgeai.domain.storage.RuntimeResultJournal journal,VDTaskRepository allocations,VDRuntimeRepository supervisors,io.edgeai.domain.storage.VDTaskResultJournal vdJournal){return new RuntimeResultPublisher(runtimes,journal,allocations,supervisors,vdJournal);}
    @Bean RunnerApiService runnerApiService(RuntimeLifecycleService lifecycle,RuntimeRepository runtimes,ArtifactStore store,ArtifactCommitService commit,Clock clock,io.edgeai.domain.storage.RuntimeStartJournal starts,RuntimeResultPublisher results,io.edgeai.domain.storage.VDTaskStartJournal vdStarts){return new RunnerApiService(lifecycle,runtimes,store,commit,clock,starts,results,vdStarts);}
    @Bean
    @ConditionalOnProperty(name="edgeai.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
    RuntimeResultPublicationWorker runtimeResultPublicationWorker(RuntimeResultPublicationRepository publications,RuntimeResultPublisher publisher,RuntimeSettings settings,Clock clock){return new RuntimeResultPublicationWorker(publications,publisher,settings,clock);}
    @Bean
    @ConditionalOnProperty(name="edgeai.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
    OffloadWorker offloadWorker(OffloadService service,RuntimeSettings settings){return new OffloadWorker(service,settings);}
    @Bean
    @ConditionalOnProperty(name="edgeai.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
    RuntimeWorker runtimeWorker(RuntimeRepository runtimes,RuntimeLifecycleService lifecycle,RuntimeGateway gateway,RunnerTokenService tokens,RuntimeSettings settings,Clock clock){
        return new RuntimeWorker(runtimes,lifecycle,gateway,tokens,settings,clock);
    }
    @Bean
    @ConditionalOnProperty(name={"edgeai.remote.enabled","edgeai.runtime.worker-enabled"},havingValue="true",matchIfMissing=false)
    RemoteWorker remoteWorker(RuntimeRepository runtimes,RuntimeLifecycleService lifecycle,ArtifactCommitService commits,S3ArtifactStore files,RemoteProvider provider,RuntimeSettings settings,Clock clock){
        return new RemoteWorker(runtimes,lifecycle,commits,files,provider,settings,clock);
    }
}
