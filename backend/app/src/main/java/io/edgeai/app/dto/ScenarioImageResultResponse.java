package io.edgeai.app.dto;

import java.util.List;

/** One dispatched image; a target outcome is null only while that image is still in flight. */
public record ScenarioImageResultResponse(int index, String path, int label, int referenceTop1, String tensorSha256,
        Outcome gpu, Outcome npu, Boolean top1Agreement) {
    public record Outcome(String status, Integer top1, List<Prediction> topK, Double inferenceMs, double roundTripMs, String failureCode) {}
    public record Prediction(int classIndex, double score) {}
}
