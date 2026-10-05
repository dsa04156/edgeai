package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.repository.StreamCompletionPublicationRepository;
import java.time.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class StreamCompletionPublicationWorker {
    private final StreamCompletionPublicationRepository publications;private final StreamCompletionPublisher publisher;
    private final RuntimeSettings settings;private final Clock clock;
    public StreamCompletionPublicationWorker(StreamCompletionPublicationRepository publications,StreamCompletionPublisher publisher,RuntimeSettings settings,Clock clock){
        this.publications=publications;this.publisher=publisher;this.settings=settings;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${edgeai.runtime.result-publication-poll-ms:1000}")
    public void publications(){try{for(int i=0;i<10 && publishOne();i++);}catch(RuntimeException error){log(error);}}
    public boolean publishOne(){
        var lease=publications.lease(settings.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofMinutes(3));
        if(lease.isEmpty())return false;var value=lease.get();
        try{publisher.publish(value.id());publications.finish(value.id(),value.owner(),clock.instant());}
        catch(RuntimeException error){publications.defer(value.id(),value.owner(),clock.instant().plusSeconds(5),clock.instant());log(error);}
        return true;
    }
    private static void log(RuntimeException error){LoggerFactory.getLogger(StreamCompletionPublicationWorker.class).warn("Stream completion publication deferred ({})",error.getClass().getSimpleName());}
}
