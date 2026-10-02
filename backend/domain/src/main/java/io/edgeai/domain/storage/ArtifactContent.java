package io.edgeai.domain.storage;

import io.edgeai.domain.runtime.RuntimeNames;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import java.util.UUID;

public record ArtifactContent(UUID taskId, UUID attemptId, String port, String sha256, long bytes, String mediaType) {
    public ArtifactContent {
        if (taskId == null || attemptId == null) throw new IllegalArgumentException("Artifact requires task and attempt");
        RuntimeNames.port(port);
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Artifact requires SHA-256");
        if (bytes < 0 || bytes > ServiceExecutionSpec.MAX_FILE_BYTES) throw new IllegalArgumentException("Artifact exceeds file budget");
        // Reuse the canonical port MIME validation. Empty files are valid, a port budget must be positive.
        new ServiceExecutionSpec.OutputPort(mediaType, Math.max(1, bytes));
    }
    public String objectKey() { return "tasks/" + taskId + "/attempts/" + attemptId + "/" + port + "/" + sha256; }
}
