package io.edgeai.app.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * executes is true only when the runtime is enabled and its scope includes the workflow key; otherwise a started
 * window Run would be stored without being dispatched. A version's mode is MOTION (continuous observer + snapshot
 * task) or FRAMES (capture → infer). observer is null when no motion observer is configured; otherwise its current
 * health: READY, NOT_READY or UNAVAILABLE.
 */
public record HawkeyeVideoCapabilityResponse(boolean enabled, boolean executes, String workflowKey, Workflow workflow,
        List<Version> versions, int maxWindows, UUID activeSessionId, Observer observer) {
    public record Workflow(UUID id, String key, String displayName) {}
    public record Version(UUID id, String version, Instant createdAt, String mode) {}
    public record Observer(String status, String nodeName, String pod, String provider, Map<?, ?> model, String error) {}
}
