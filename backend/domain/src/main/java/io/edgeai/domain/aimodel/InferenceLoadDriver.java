package io.edgeai.domain.aimodel;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Bounded HTTP inference load against one measurement Pod. The caller supplies the Pod IP it read from Kubernetes for
 * the owned Pod; the request target is always {@code http://podIp:port/path}, redirects are never followed and response
 * bodies are read only up to a limit and discarded.
 */
public interface InferenceLoadDriver {
    record HttpLoad(int port, String path, String method, String contentType, byte[] body, int concurrency, int requestTimeoutSeconds) {
        public HttpLoad { body = body == null ? new byte[0] : body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    /** Counts so far; {@code statusCounts} keys are HTTP codes or TIMEOUT/CONNECTION/IO/TOO_LARGE. Latencies of 2xx only. */
    record Progress(Instant started, Instant ended, long requests, long succeeded, long failed, long connectionFailures,
                    Map<String, Long> statusCounts, Double latencyP50Millis, Double latencyP95Millis, Double latencyMaxMillis) {
        public Progress { statusCounts = Map.copyOf(statusCounts); }
    }
    /** One request; a 2xx answer is ready. Connection failures are reported separately from HTTP errors. */
    enum ProbeResult { READY, HTTP_ERROR, TIMEOUT, CONNECTION_FAILED }
    interface Handle {
        Progress progress();
        boolean finished();
        /** Stops the workers (idempotent) and returns the final counts. */
        Progress stop();
    }
    ProbeResult probe(HttpLoad load, String podIp);
    Handle start(HttpLoad load, String podIp, Duration duration);
}
