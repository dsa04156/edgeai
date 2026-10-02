package io.edgeai.domain.runtime;
import java.util.Objects;
import java.util.UUID;
/** Identity obtained by the Kubernetes adapter after checking Job ownership, never from request JSON. */
public record RuntimePod(UUID jobUid, UUID podUid, UUID nodeUid, String nodeName) {
    public RuntimePod {
        Objects.requireNonNull(jobUid); Objects.requireNonNull(podUid); Objects.requireNonNull(nodeUid);
        RuntimeNames.dns(nodeName,253);
    }
}
