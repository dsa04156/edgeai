package io.edgeai.app.service;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class RuntimeWorkerTest {
    @Test void jobObservationWithVdAttemptIdDoesNotInterruptJobReconciliation(){
        var runtimes=mock(RuntimeRepository.class);var lifecycle=mock(RuntimeLifecycleService.class);var gateway=mock(RuntimeGateway.class);
        Instant now=Instant.now();
        var r=new RuntimeInstance(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"edgeai-runtimes",null,
            UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,now.plusSeconds(120),null,now,now,null,UUID.randomUUID());
        when(runtimes.active(r.namespace(),10000)).thenReturn(List.of());when(runtimes.byAttempt(r.attemptId())).thenReturn(Optional.of(r));
        when(gateway.listJobs()).thenReturn(new RuntimeGateway.Snapshot(Map.of(r.attemptId(),new RuntimeGateway.JobObservation(
            r.attemptId(),r.taskId(),r.runId(),r.epoch(),"edgeai-"+r.attemptId(),UUID.randomUUID(),"COMPLETE")),"17"));
        var worker=new RuntimeWorker(runtimes,lifecycle,gateway,mock(RunnerTokenService.class),
            new RuntimeSettings(r.namespace(),"edgeai-runner",URI.create("http://fixture.invalid"),120),Clock.fixed(now,ZoneOffset.UTC));
        worker.reconcile();
        verify(gateway).watchJobs("17");verifyNoInteractions(lifecycle);
    }
}
