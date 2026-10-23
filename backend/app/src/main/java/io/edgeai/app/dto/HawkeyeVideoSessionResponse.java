package io.edgeai.app.dto;

import io.edgeai.domain.scenario.HawkeyeVideoSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** windows is newest first and empty in list responses; windowCount is always the full count. */
public record HawkeyeVideoSessionResponse(UUID id, UUID workflowVersionId, Camera camera, int maxWindows, String state,
        String endReason, String failureCode, String failureMessage, Instant createdAt, Instant updatedAt, Instant endedAt,
        int windowCount, List<Window> windows) {
    public record Camera(String id, UUID uid) {}
    public record Window(int seq, UUID runId, String runState, Instant createdAt, Instant updatedAt) {}

    public static HawkeyeVideoSessionResponse from(HawkeyeVideoSession s, List<HawkeyeVideoSession.Window> windows, boolean detail) {
        return new HawkeyeVideoSessionResponse(s.id(), s.workflowVersionId(), new Camera(s.deviceId(), s.deviceUid()), s.maxWindows(),
            s.state(), s.endReason(), s.failureCode(), s.failureMessage(), s.createdAt(), s.updatedAt(), s.endedAt(), windows.size(),
            detail ? windows.stream().map(w -> new Window(w.seq(), w.runId(), w.runState(), w.createdAt(), w.runUpdatedAt())).toList() : List.of());
    }
}
