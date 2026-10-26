package io.edgeai.app.dto;

import io.edgeai.domain.scenario.HawkeyeVideoSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * windows is newest first and empty in list responses; windowCount is always the full count. mode is MOTION or
 * FRAMES (see the capability); observer is the observer instance a MOTION session started, null otherwise.
 */
public record HawkeyeVideoSessionResponse(UUID id, UUID workflowVersionId, String mode, Camera camera, int maxWindows, String state,
        String endReason, String failureCode, String failureMessage, Instant createdAt, Instant updatedAt, Instant endedAt,
        Observer observer, int windowCount, List<Window> windows) {
    public record Camera(String id, UUID uid) {}
    public record Window(int seq, UUID runId, String runState, Instant createdAt, Instant updatedAt) {}
    public record Observer(String epoch, Instant startedAt) {}

    public static HawkeyeVideoSessionResponse from(HawkeyeVideoSession s, String mode, List<HawkeyeVideoSession.Window> windows, boolean detail) {
        return new HawkeyeVideoSessionResponse(s.id(), s.workflowVersionId(), mode, new Camera(s.deviceId(), s.deviceUid()), s.maxWindows(),
            s.state(), s.endReason(), s.failureCode(), s.failureMessage(), s.createdAt(), s.updatedAt(), s.endedAt(),
            s.observerEpoch() == null ? null : new Observer(s.observerEpoch(), s.observerStartedAt()), windows.size(),
            detail ? windows.stream().map(w -> new Window(w.seq(), w.runId(), w.runState(), w.createdAt(), w.runUpdatedAt())).toList() : List.of());
    }
}
