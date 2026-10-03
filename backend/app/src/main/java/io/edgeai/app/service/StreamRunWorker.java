package io.edgeai.app.service;

import io.edgeai.domain.repository.StreamRunRepository;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Recoverable metadata preparation, separate from the broker's bounded network executor. */
public final class StreamRunWorker {
    private final StreamRunRepository repository;
    private final StreamRunService streams;
    private final RuntimeLifecycleService runtimes;
    private final String namespace;
    private UUID cursor;
    public StreamRunWorker(StreamRunRepository repository,StreamRunService streams,RuntimeLifecycleService runtimes,String namespace){
        this.repository=repository;this.streams=streams;this.runtimes=runtimes;this.namespace=namespace;
    }
    @Scheduled(fixedDelayString="${edgeai.stream.reconcile-ms:500}",scheduler="streamTaskScheduler")
    public synchronized void tick(){try{
        var ids=repository.active(namespace,cursor,64);cursor=ids.isEmpty()?null:ids.getLast();
        for(var id:ids)try{for(var failure:streams.prepare(id))runtimes.observeFailure(failure.attemptId(),failure.reason());}
        catch(RuntimeException e){log(e);}
    }catch(RuntimeException e){log(e);}}
    private static void log(RuntimeException error){LoggerFactory.getLogger(StreamRunWorker.class).warn("Stream Run preparation deferred ({})",error.getClass().getSimpleName());}
}
