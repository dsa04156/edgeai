package io.edgeai.domain.vd;

import java.util.*;

/** Internal immutable configuration for the shared continuous engine. */
public record VDProfileSpec(Map<String, Source> sources, String continuousJson) {
    public VDProfileSpec { sources = Map.copyOf(sources); }
    public UUID serviceProfileVersionId() { return null; }
    public boolean continuous() { return true; }
    public record Source(String deviceModel, boolean required) {}
}
