package io.edgeai.app.dto;

import java.util.List;

public record PlatformResponse(String name, String version, String milestone,
                               List<String> capabilities) {}
