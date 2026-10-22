package io.edgeai.domain.inference;

import java.util.Map;

/** One fixed image detection runtime. Implementations never follow redirects or retry a prediction. */
public interface ObjectDetectionRuntime {
    /** Health document of a successful response; throws Failure otherwise. */
    Map<?, ?> health();
    /** Result document of a completed prediction; throws Failure otherwise. */
    Map<?, ?> predict(String imageBase64);

    /** Stable classifications only: runtime payloads never become messages. */
    final class Failure extends RuntimeException {
        public enum Reason { BUSY, REJECTED, TOO_LARGE, UNAVAILABLE, INVALID_RESPONSE }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
