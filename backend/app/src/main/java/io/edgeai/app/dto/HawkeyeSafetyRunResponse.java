package io.edgeai.app.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * target is what the adapter reported when the run started; result is the model's answer once received, also on a
 * FAILED run whose output named no category. inferenceMs and loadMs are Ollama's own timings of this request;
 * roundTripMs is the API-measured adapter call.
 */
public record HawkeyeSafetyRunResponse(UUID id, String sampleId, String referenceLabel, String state, Instant createdAt,
        Instant startedAt, Instant finishedAt, String failureCode, String failureMessage, Target target, Result result) {
    public record Target(String nodeName, String model, String modelDigest, String ollamaVersion, String promptSha256) {}
    public record Result(String category, String explanation, Boolean matchesReference, String rawOutput, String doneReason,
            String inputSha256, String processor, long sizeBytes, long sizeVramBytes, double inferenceMs, double loadMs,
            double roundTripMs, Integer promptEvalCount, Integer evalCount) {}
}
