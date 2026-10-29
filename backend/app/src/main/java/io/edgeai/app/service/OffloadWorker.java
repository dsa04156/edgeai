package io.edgeai.app.service;
import io.edgeai.app.config.RuntimeSettings;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
public final class OffloadWorker {
    private final OffloadService service;
    private final RuntimeSettings settings;
    private final NodeMetricsService metrics;
    public OffloadWorker(OffloadService service,RuntimeSettings settings,NodeMetricsService metrics){this.service=service;this.settings=settings;this.metrics=metrics;}
    @Scheduled(fixedDelayString="${edgeai.runtime.offload-poll-ms:1000}")
    public void reconcile() {
        try {
            for(var id:service.active(settings.namespace()))attempt(()->service.advance(id));
            var candidates=service.automaticCandidates(settings.namespace());
            // One shared (5 s cached) node snapshot per pass, read before any Run lock is taken.
            var nodes=candidates.isEmpty()?null:metrics.snapshot();
            for(var task:candidates)attempt(()->service.evaluate(task,settings.namespace(),nodes));
            service.retainNodeSamples(candidates);
        }
        catch(RuntimeException error){LoggerFactory.getLogger(OffloadWorker.class).warn("Offload reconciliation deferred ({})",error.getClass().getSimpleName());}
    }
    private void attempt(Runnable action) {
        try {action.run();}catch(RuntimeException error){LoggerFactory.getLogger(OffloadWorker.class).warn("Offload task deferred ({})",error.getClass().getSimpleName());}
    }
}
