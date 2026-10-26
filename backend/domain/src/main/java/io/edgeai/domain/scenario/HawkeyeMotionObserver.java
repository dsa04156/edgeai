package io.edgeai.domain.scenario;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The continuous forklift/person observer service (hawkeye-runtime/motion). It reads one pinned camera while a Hawkeye
 * video session runs and keeps its tracker state between window Runs. Implementations never follow redirects or
 * retry, and throw {@link Unavailable} when the service cannot be reached or answers outside its contract.
 */
public interface HawkeyeMotionObserver {
    /** HTTP status and JSON document of one call ({"error": code} for 4xx). */
    record Reply(int status, Map<?, ?> document) {
        public String error() { return document.get("error") instanceof String code ? code : "HTTP_" + status; }
    }

    Reply health();
    /** Starts (201) or renews (200) the session's observer for the pinned source. */
    Reply put(UUID sessionId, Map<String, Object> source, int leaseSeconds);
    Reply delete(UUID sessionId);
    Reply live(UUID sessionId);
    /**
     * A retained JPEG frame of the session's observer instance {@code epoch}, empty when it is no longer retained.
     * Sequence numbers restart with each instance, so another instance answers {@link Superseded}.
     */
    Optional<byte[]> frame(UUID sessionId, String epoch, long seq);

    final class Unavailable extends RuntimeException {
        public Unavailable() { super("motion observer unavailable"); }
    }
    final class Superseded extends RuntimeException {
        public Superseded() { super("motion observer instance changed"); }
    }
}
