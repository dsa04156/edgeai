package io.edgeai.app.service;

import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.RuntimeInstance;
import io.edgeai.domain.runtime.RuntimePod;
import io.edgeai.domain.storage.ArtifactStore;
import io.edgeai.domain.storage.RuntimeStartJournal;
import io.edgeai.domain.runtime.RuntimeStartAuthority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Claim serialization only. Route orchestration remains gated by RuntimeLifecycleService. */
class RunnerStreamClaimTest {
    @Test void claimPreservesDistinctPersistentCommandAndArtifactFinalizer() throws Exception {
        var lifecycle=mock(RuntimeLifecycleService.class);var runtimes=mock(RuntimeRepository.class);
        var runtime=mock(RuntimeInstance.class);var storage=mock(ArtifactStore.class);
        var now=Instant.parse("2026-10-03T06:00:00Z");var attempt=UUID.randomUUID();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        var principal=new RunnerPrincipal(attempt,1,pod);
        when(runtime.jobUid()).thenReturn(pod.jobUid());when(runtime.attemptId()).thenReturn(attempt);
        when(runtime.runId()).thenReturn(UUID.randomUUID());when(runtime.taskId()).thenReturn(UUID.randomUUID());
        when(runtime.epoch()).thenReturn(1L);when(runtime.expiresAt()).thenReturn(now.plusSeconds(300));
        when(runtimes.byAttempt(attempt)).thenReturn(Optional.of(runtime));
        var spec=ServiceExecutionInput.parseSpec(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        when(lifecycle.claim(attempt,1,pod)).thenReturn(new RuntimeLifecycleService.Assignment(runtime,spec,"{}",List.of(),mock(RuntimeStartAuthority.class)));
        var api=new RunnerApiService(lifecycle,runtimes,storage,mock(ArtifactCommitService.class),Clock.fixed(now,ZoneOffset.UTC),mock(RuntimeStartJournal.class));
        var json=new JsonDocuments();var response=(Map<?,?>)api.claim(principal,json.canonical(Map.of("epoch",1,"podUid",pod.podUid().toString())));
        var original=(Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));
        assertThat(json.canonical(response.get("stream"))).isEqualTo(json.canonical(original.get("stream")));
        assertThat(response.get("command")).isEqualTo(spec.command());
        // RunnerController uses bounded canonical JSON, not Jackson's record serializer.
        var encoded=json.boundedCanonical(response,262144);
        assertThat(encoded).contains("stream_sum.py","stream_result.py","maxBufferBytes","maxPayloadBytes","stepTimeoutSeconds");
        assertThat(((Map<?,?>)response.get("outputs")).keySet()).hasSize(1);
        verifyNoInteractions(storage);
    }
}
