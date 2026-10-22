package io.edgeai.app.dto;

import java.util.List;

public record InferenceModelsResponse(List<Model> items) {
    public record Model(String id, String name, String nodeName, String deviceName, boolean available) {}
}
