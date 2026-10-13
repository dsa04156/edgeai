package io.edgeai.domain.vd;

import io.edgeai.domain.device.Device;
import java.util.*;

public record VDProfileSpec(String type, UUID serviceProfileVersionId, Map<String, Source> sources,
                            int maxConcurrentTasks, int startupTimeoutSeconds, int drainTimeoutSeconds, String processingJson,
                            String continuousJson) {
    public VDProfileSpec(String type, UUID serviceProfileVersionId, Map<String, Source> sources,
            int maxConcurrentTasks, int startupTimeoutSeconds, int drainTimeoutSeconds, String processingJson) {
        this(type,serviceProfileVersionId,sources,maxConcurrentTasks,startupTimeoutSeconds,drainTimeoutSeconds,processingJson,null);
    }
    public VDProfileSpec(String type, UUID serviceProfileVersionId, Map<String, Source> sources, int maxConcurrentTasks, int startupTimeoutSeconds, int drainTimeoutSeconds) {
        this(type,serviceProfileVersionId,sources,maxConcurrentTasks,startupTimeoutSeconds,drainTimeoutSeconds,null);
    }
    public VDProfileSpec { sources = Map.copyOf(sources); }
    public boolean continuous() { return continuousJson != null; }
    public record Source(UUID deviceProfileVersionId, boolean required, Set<Device.SourceMode> sourceModes, String deviceModel) {
        public Source(UUID profile, boolean required, Set<Device.SourceMode> modes) { this(profile,required,modes,null); }
        public Source { sourceModes = Set.copyOf(sourceModes); }
    }
}
