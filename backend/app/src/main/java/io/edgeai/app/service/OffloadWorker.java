package io.edgeai.app.service;
import io.edgeai.app.config.RuntimeSettings;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
public final class OffloadWorker {
    private final OffloadService service;
    private final RuntimeSettings settings;
    public OffloadWorker(OffloadService service,RuntimeSettings settings){this.service=service;this.settings=settings;}
    @Scheduled(fixedDelayString="${edgeai.runtime.offload-poll-ms:1000}")
    public void reconcile() {
        try {for(var id:service.active(settings.namespace()))service.advance(id);}
        catch(RuntimeException error){LoggerFactory.getLogger(OffloadWorker.class).warn("Offload reconciliation deferred ({})",error.getClass().getSimpleName());}
    }
}
