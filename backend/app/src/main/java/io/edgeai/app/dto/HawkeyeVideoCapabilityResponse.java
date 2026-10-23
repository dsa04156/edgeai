package io.edgeai.app.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * executes is true only when the runtime is enabled and its scope includes the workflow key; otherwise a started
 * window Run would be stored without being dispatched.
 */
public record HawkeyeVideoCapabilityResponse(boolean enabled, boolean executes, String workflowKey, Workflow workflow,
        List<Version> versions, int maxWindows, UUID activeSessionId) {
    public record Workflow(UUID id, String key, String displayName) {}
    public record Version(UUID id, String version, Instant createdAt) {}
}
