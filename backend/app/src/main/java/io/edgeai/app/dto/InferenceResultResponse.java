package io.edgeai.app.dto;

import java.util.List;

/** Validated runtime result; boxes are original-image pixel coordinates. */
public record InferenceResultResponse(String modelId, String nodeName, String deviceName, String provider,
        int imageWidth, int imageHeight, double inferenceMs, List<Detection> detections) {
    public record Detection(String label, double score, double x, double y, double width, double height) {}
}
