package io.edgeai.domain.scenario;

import java.util.Map;

/**
 * The Hawkeye warehouse-safety adapter beside one Ollama process. Implementations never follow redirects or retry
 * a call and throw ClassificationRuntime.Failure with a stable reason.
 */
public interface HawkeyeRuntime {
    /** Health document of a 200 (ready) or 503 (not ready) response. */
    Map<?, ?> health();
    /** Exact bytes of one pinned sample image. */
    byte[] sample(String sampleId);
    /** Raw model output, Ollama timings and placement for one pinned sample. */
    Map<?, ?> classify(String sampleId, String samplesSha256);
}
