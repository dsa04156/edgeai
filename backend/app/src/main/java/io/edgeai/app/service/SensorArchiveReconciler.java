package io.edgeai.app.service;

import io.edgeai.domain.repository.SensorReconciliationQueue;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.DeviceProfileCompiler.map;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Hides exact legacy duplicates of stored qualified originals after their ACK. A slow or failing store delays
 * only this queue, which is bounded in rows, per-entry retry interval and age (the raw retention period).
 */
@Service
public class SensorArchiveReconciler implements AutoCloseable {
    private final SensorTelemetryRepository telemetry;
    private final SensorReconciliationQueue queue;
    private final Clock clock;
    private final long limit;
    private final boolean enabled;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("sensor-archive-reconcile").factory());
    private final AtomicLong dropped=new AtomicLong();
    public SensorArchiveReconciler(SensorTelemetryRepository telemetry,SensorReconciliationQueue queue,Clock clock,
        @Value("${edgeai.data-store.raw.reconciliation-limit:100000}") long limit,@Value("${edgeai.data-store.raw.enabled:false}") boolean enabled,
        @Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.telemetry=telemetry;this.queue=queue;this.clock=clock;this.limit=limit;this.enabled=enabled&&!inspection;
        if(limit<64||limit>1000000)throw new IllegalArgumentException("Invalid archive reconciliation limit");
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){if(enabled&&telemetry.enabled())scheduler.scheduleWithFixedDelay(this::drain,1000,1000,TimeUnit.MILLISECONDS);}
    /** Called before the archive ACK of a stored original; only qualified originals supersede legacy ones. A full queue never withholds the ACK; the miss is counted. */
    public void enqueue(SensorTelemetryRepository.Observation observation) {
        var document=map(JSON.decode(observation.documentJson()));if(!"QUALIFIED".equals(document.get("quality")))return;
        String series=document.get("sourceGeneration")+":"+document.get("sessionId");long sequence=((Number)document.get("sequence")).longValue();
        if(!queue.enqueue(observation,series,sequence,clock.instant(),limit))dropped.incrementAndGet();
    }
    public Map<String,Object> state(){return Map.of("pendingReconciliations",queue.count(),"droppedReconciliations",dropped.get());}
    public synchronized void drain() {
        try {
            queue.expire(clock.instant().minusSeconds(telemetry.rawRetentionDays()*86400L));
            while(true) {
                var entries=queue.due(clock.instant(),256);if(entries.isEmpty())return;
                try{telemetry.hideLegacyDuplicates(entries.stream().map(SensorReconciliationQueue.Entry::observation).toList());}
                catch(RuntimeException e) {
                    // An unavailable store would fail the next batch too; wait for the next interval.
                    for(var entry:entries)queue.retry(entry,clock.instant().plusSeconds(Math.min(300L,1L<<Math.min(entry.attempts(),9))));
                    return;
                }
                for(var entry:entries)queue.complete(entry);
                if(entries.size()<256)return;
            }
        }catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(SensorArchiveReconciler.class).warn("Archive reconciliation deferred ({})",e.getClass().getSimpleName());}
    }
    public void close(){scheduler.shutdownNow();}
}
