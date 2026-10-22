package io.edgeai.app.dto;

import java.util.List;
import java.util.UUID;

/** Configured scenario and the runtimes' current self-reports; reported fields are null when a runtime cannot be read. */
public record ScenarioCapabilityResponse(String id, boolean enabled, int maxImageCount, Dataset dataset, List<Target> targets, UUID activeRunId) {
    public record Dataset(String name, String version, String preprocessing, String labelSource, String manifestSha256,
            String inputsSha256, int imageCount) {}
    public record Target(String id, String modelName, String expectedModelSha256, String expectedProvider, String status,
            Reported reported) {}
    public record Reported(String nodeName, String deviceName, String provider, String precision, String runtime, String modelSha256) {}
}
