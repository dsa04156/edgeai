package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.execution.Task;
import io.edgeai.domain.storage.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResultContentTest {
    @Test void pendingUnknownAndOversizedResultsNeverReadStorage() {
        var executions=mock(ExecutionRepository.class);var runtimes=mock(RuntimeRepository.class);var files=mock(ArtifactFiles.class);
        var service=new ResultService(executions,runtimes,mock(RemoteRepository.class),Optional.of(files));
        UUID task=UUID.randomUUID();when(executions.task(task)).thenReturn(Optional.of(mock(Task.class)));
        when(runtimes.result(task)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.content(task,"output")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("RESULT_NOT_FOUND"));
        var artifact=new VerifiedArtifact("fixture","output","fixed-version","a".repeat(64),1048577,"application/json");
        when(runtimes.result(task)).thenReturn(Optional.of(new TaskResult(UUID.randomUUID(),task,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),"sha256:"+"a".repeat(64),Instant.now(),List.of(new TaskResult.Output("output",artifact)))));
        assertThatThrownBy(()->service.content(task,"other")).isInstanceOf(ControlPlaneException.class);
        assertThatThrownBy(()->service.content(task,"output")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("RESULT_PREVIEW_UNSUPPORTED"));
        verifyNoInteractions(files);
    }
    @Test void malformedStoredJsonFailsAndTheOwnedTemporaryFileIsRemoved() throws Exception {
        var executions=mock(ExecutionRepository.class);var runtimes=mock(RuntimeRepository.class);var files=mock(ArtifactFiles.class);
        var service=new ResultService(executions,runtimes,mock(RemoteRepository.class),Optional.of(files));UUID task=UUID.randomUUID();
        when(executions.task(task)).thenReturn(Optional.of(mock(Task.class)));
        var artifact=new VerifiedArtifact("fixture","output","fixed-version","a".repeat(64),2,"application/json");
        when(runtimes.result(task)).thenReturn(Optional.of(new TaskResult(UUID.randomUUID(),task,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID(),"sha256:"+"a".repeat(64),Instant.now(),List.of(new TaskResult.Output("output",artifact)))));
        var saved=new java.util.concurrent.atomic.AtomicReference<Path>();
        doAnswer(call->{Path file=call.getArgument(1);saved.set(file);Files.writeString(file,"{{");return null;}).when(files).downloadFile(eq(artifact),any());
        assertThatThrownBy(()->service.content(task,"output")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("RESULT_JSON_INVALID"));
        assertThat(saved.get()).doesNotExist();assertThat(saved.get().getParent()).doesNotExist();
    }
}
