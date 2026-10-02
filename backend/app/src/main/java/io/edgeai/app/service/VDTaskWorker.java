package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.repository.RuntimeRepository;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Expiry/cancellation must continue even when the supervisor cannot reach the API. */
public final class VDTaskWorker {
    private final RuntimeRepository runtimes;
    private final VDTaskService service;
    private final RuntimeSettings settings;
    public VDTaskWorker(RuntimeRepository runtimes,VDTaskService service,RuntimeSettings settings){this.runtimes=runtimes;this.service=service;this.settings=settings;}
    @Scheduled(fixedDelayString="${edgeai.vd.task-reconcile-ms:1000}")
    public void reconcile() {
        try {
            for(var r:runtimes.activeVD(settings.namespace(),10000)) {
                try{service.reconcile(r.id());}catch(RuntimeException e){log(e);}
            }
        }catch(RuntimeException e){log(e);}
    }
    private static void log(RuntimeException e){LoggerFactory.getLogger(VDTaskWorker.class).warn("VD Task reconciliation deferred ({})",e.getClass().getSimpleName());}
}
