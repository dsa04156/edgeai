package io.edgeai.domain.runtime;

import java.net.URI;
import java.util.UUID;

public record RuntimeLaunch(UUID runId, UUID taskId, UUID attemptId, long epoch,
        String namespace, String serviceAccount, String claimSecret, URI controlPlane,
        UUID targetNodeId, String targetNodeName) {
    public RuntimeLaunch {
        if (runId == null || taskId == null || attemptId == null || epoch < 1 || epoch > 9007199254740991L)
            throw new IllegalArgumentException("Run/task/attempt identity and epoch required");
        RuntimeNames.dns(namespace, 63); RuntimeNames.dns(serviceAccount, 253); RuntimeNames.dns(claimSecret, 253);
        if (namespace.contains(".")) throw new IllegalArgumentException("Namespace requires a DNS label");
        if (controlPlane == null || controlPlane.getHost() == null || controlPlane.getUserInfo() != null
                || controlPlane.getQuery() != null || controlPlane.getFragment() != null
                || !controlPlane.getPath().isEmpty() || !(controlPlane.getScheme().equals("https") || controlPlane.getScheme().equals("http")))
            throw new IllegalArgumentException("Control Plane requires an HTTP(S) origin without embedded credentials");
        if ((targetNodeId == null) != (targetNodeName == null)) throw new IllegalArgumentException("NODE requires both UID and name");
        if (targetNodeName != null) RuntimeNames.dns(targetNodeName, 253);
    }
    public String jobName() { return "edgeai-" + attemptId; }
}
