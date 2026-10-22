package io.edgeai.domain.scenario;

import java.util.Map;

/** One fixed-dataset image classification runtime. Implementations never follow redirects or retry a call. */
public interface ClassificationRuntime {
    /** Health document of a 200 (ready) or 503 (not ready) response; throws Failure otherwise. */
    Map<?, ?> health();
    /** Exact bytes of the runtime's dataset manifest. */
    byte[] manifest();
    /** Result document for one dataset index; throws Failure otherwise. */
    Map<?, ?> classify(int index, String manifestSha256);

    /** Stable classifications only: runtime payloads never become messages. */
    final class Failure extends RuntimeException {
        public enum Reason { BUSY, REJECTED, UNAVAILABLE, INVALID_RESPONSE }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
