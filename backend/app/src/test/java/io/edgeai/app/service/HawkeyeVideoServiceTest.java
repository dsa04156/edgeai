package io.edgeai.app.service;

import io.edgeai.app.config.HawkeyeVideoProperties;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.Task;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeScope;
import io.edgeai.domain.storage.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HawkeyeVideoServiceTest {
    final HawkeyeVideoRepository repository = mock(HawkeyeVideoRepository.class);

    HawkeyeVideoService service(boolean runtimeEnabled, String scope, boolean inspection) {
        return new HawkeyeVideoService(repository, mock(ExecutionService.class), mock(ExecutionRepository.class), mock(RuntimeRepository.class),
            mock(WorkflowRepository.class), Optional.empty(), mock(TransactionTemplate.class),
            new HawkeyeVideoProperties(true, "hawkeye-video-safety", 3, Duration.ofMillis(20), "", null), RuntimeScope.parse(scope),
            Clock.systemUTC(), runtimeEnabled, inspection, Optional.empty());
    }

    @Test void sessionsAreNotAdvancedWithoutAnExecutingRuntimeOrInInspection() throws Exception {
        for (var service : List.of(service(false, "*", false), service(true, "other-workflow", false), service(true, "*", true))) {
            Thread.sleep(150);
            service.close();
        }
        verify(repository, never()).running();
    }

    @Test void anExecutingRuntimeAdvancesRunningSessions() throws Exception {
        var service = service(true, "hawkeye-video-safety", false);
        try { verify(repository, timeout(2000).atLeastOnce()).running(); }
        finally { service.close(); }
    }

    static byte[] jpeg(int seed) { var b = new byte[64]; Arrays.fill(b, (byte) seed); b[0] = (byte) 0xff; b[1] = (byte) 0xd8; b[62] = (byte) 0xff; b[63] = (byte) 0xd9; return b; }
    /** The snapshot workload's ustar artifact: manifest.json + frame-NNNN.jpg (one 512-byte block per frame here). */
    static byte[] frames(byte[]... images) {
        var frames = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < images.length; i++) frames.add(Map.of("seq", i + 1, "file", "frame-%04d.jpg".formatted(i + 1), "sha256", HawkeyeFrames.sha256(images[i])));
        var out = new ByteArrayOutputStream();
        entry(out, "manifest.json", new JsonDocuments().canonical(Map.of("apiVersion", "edgeai.hawkeye.frames/v1", "frames", frames)).getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < images.length; i++) entry(out, "frame-%04d.jpg".formatted(i + 1), images[i]);
        out.writeBytes(new byte[1024]); return out.toByteArray();
    }
    static void entry(ByteArrayOutputStream out, String name, byte[] data) {
        var header = new byte[512]; System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, header, 0, name.length());
        var size = "%011o".formatted(data.length).getBytes(StandardCharsets.US_ASCII); System.arraycopy(size, 0, header, 124, size.length);
        header[156] = '0'; out.writeBytes(header); out.writeBytes(data); out.writeBytes(new byte[(512 - data.length % 512) % 512]);
    }

    /** A committed snapshot frames Result of a window Run; the store writes the given archive bytes on download. */
    private record Stored(HawkeyeVideoService service, ArtifactFiles files, UUID task) {}
    private Stored stored(long bytes, byte[] tar) {
        var runs = mock(ExecutionRepository.class); var runtimes = mock(RuntimeRepository.class); var files = mock(ArtifactFiles.class);
        UUID taskId = UUID.randomUUID(), runId = UUID.randomUUID();
        var task = mock(Task.class); when(task.runId()).thenReturn(runId);
        when(runs.task(taskId)).thenReturn(Optional.of(task)); when(repository.windowRun(runId)).thenReturn(true);
        var artifact = new VerifiedArtifact("results", "frames", "v1", "a".repeat(64), bytes, HawkeyeVideoService.FRAMES_MEDIA_TYPE);
        when(runtimes.result(taskId)).thenReturn(Optional.of(new TaskResult(UUID.randomUUID(), taskId, UUID.randomUUID(), UUID.randomUUID(), 1,
            UUID.randomUUID(), "sha256:" + "a".repeat(64), Instant.now(), List.of(new TaskResult.Output("frames", artifact)))));
        if (tar != null) doAnswer(call -> { Files.write((Path) call.getArgument(1), tar); return null; }).when(files).downloadFile(eq(artifact), any());
        var service = new HawkeyeVideoService(repository, mock(ExecutionService.class), runs, runtimes, mock(WorkflowRepository.class), Optional.of(files),
            mock(TransactionTemplate.class), new HawkeyeVideoProperties(true, "hawkeye-video-safety", 3, Duration.ofMillis(20), "", null),
            RuntimeScope.parse("*"), Clock.systemUTC(), false, false, Optional.empty());
        return new Stored(service, files, taskId);
    }

    @Test void framesOfOneVerifiedArchiveAreReadWithOneDownload() throws Exception {
        byte[] first = jpeg(1), second = jpeg(2);
        var tar = frames(first, second);
        // A 24 fps window is about 16 MB of JPEGs, over the former 16 MiB bound.
        var stored = stored(20L << 20, tar);
        assertThat(stored.service().frame(stored.task(), 1)).isEqualTo(first);
        assertThat(stored.service().frame(stored.task(), 2)).isEqualTo(second);
        assertThat(stored.service().frame(stored.task(), 1)).isEqualTo(first);
        verify(stored.files(), times(1)).downloadFile(any(), any());
    }

    @Test void rejectedDownloadsAndOversizedArchivesAreNotServedOrCached() throws Exception {
        var failing = stored(1024, null);
        doThrow(new ArtifactVerificationException("digest mismatch")).when(failing.files()).downloadFile(any(), any());
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> failing.service().frame(failing.task(), 1)).isInstanceOf(ArtifactVerificationException.class);
        verify(failing.files(), times(2)).downloadFile(any(), any());
        // A frame whose bytes differ from its manifest SHA-256 is rejected every time.
        var tar = frames(jpeg(1));
        tar[tar.length - 1024 - 512 + 10] ^= 1;
        var tampered = stored(tar.length, tar);
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> tampered.service().frame(tampered.task(), 1))
            .isInstanceOfSatisfying(ControlPlaneException.class, e -> assertThat(e.code()).isEqualTo("FRAMES_ARTIFACT_INVALID"));
        var oversized = stored((32L << 20) + 1, new byte[0]);
        assertThatThrownBy(() -> oversized.service().frame(oversized.task(), 1))
            .isInstanceOfSatisfying(ControlPlaneException.class, e -> assertThat(e.code()).isEqualTo("FRAME_NOT_FOUND"));
        verifyNoInteractions(oversized.files());
    }
}
