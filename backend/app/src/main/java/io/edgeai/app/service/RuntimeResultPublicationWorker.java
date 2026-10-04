package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.repository.RuntimeResultPublicationRepository;
import java.time.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Publication survives API restart, producer termination and loss of the HTTP response. */
public final class RuntimeResultPublicationWorker {
    private final RuntimeResultPublicationRepository publications;
    private final RuntimeResultPublisher publisher;
    private final RuntimeSettings settings;
    private final Clock clock;
    public RuntimeResultPublicationWorker(RuntimeResultPublicationRepository publications,RuntimeResultPublisher publisher,RuntimeSettings settings,Clock clock){
        this.publications=publications;this.publisher=publisher;this.settings=settings;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${edgeai.runtime.result-publication-poll-ms:1000}")
    public void publications(){
        try{for(int i=0;i<10 && publishOne();i++);}
        catch(RuntimeException error){log(error);}
    }
    public boolean publishOne(){
        var item=publications.lease(settings.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofMinutes(3));
        if(item.isEmpty())return false;var p=item.get();
        try{
            var result=publisher.publish(p.runtimeId());
            if(!result.id().equals(p.resultId()))throw new IllegalStateException("Publication Result identity differs");
            publications.finish(p.resultId(),p.leaseOwner(),clock.instant());
        }catch(RuntimeException error){
            publications.defer(p.resultId(),p.leaseOwner(),clock.instant().plusSeconds(5),clock.instant());log(error);
        }
        return true;
    }
    private static void log(RuntimeException error){LoggerFactory.getLogger(RuntimeResultPublicationWorker.class).warn("Result publication deferred ({})",error.getClass().getSimpleName());}
}
