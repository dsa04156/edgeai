package io.edgeai.domain.repository;

import io.edgeai.domain.scenario.HawkeyeVideoSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Session rows are changed only while the caller holds the row lock taken by {@link #lock}. */
public interface HawkeyeVideoRepository {
    /** False when the idempotency key exists or another session is RUNNING. */
    boolean create(HawkeyeVideoSession session);
    Optional<HawkeyeVideoSession> find(UUID id);
    Optional<HawkeyeVideoSession> lock(UUID id);
    Optional<HawkeyeVideoSession> byIdempotencyKey(UUID key);
    Optional<UUID> active();
    List<UUID> running();
    List<HawkeyeVideoSession> list(int limit, int offset);
    /** Newest first. */
    List<HawkeyeVideoSession.Window> windows(UUID sessionId);
    void addWindow(UUID sessionId, int seq, UUID runId, Instant at);
    /** Sets the session camera snapshot once; false when one is already set. */
    boolean pinCamera(UUID sessionId, String cameraSourceJson);
    boolean windowRun(UUID runId);
    /** RUNNING → STOPPED/COMPLETED/FAILED. */
    boolean end(UUID id, String state, String reason, String failureCode, String failureMessage, Instant at);
}
