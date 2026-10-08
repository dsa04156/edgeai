package io.edgeai.domain.vd;

import java.util.List;

public record VDSourceSelection(String deviceId,List<String> properties,int maxAgeSeconds) {
    public VDSourceSelection { properties=List.copyOf(properties); }
}
