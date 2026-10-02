package io.edgeai.domain.vd;

import io.edgeai.domain.device.Device;
import java.util.*;

public record VDProfileSpec(String type, UUID serviceProfileVersionId, Map<String, Source> sources,
                            int maxConcurrentTasks, int startupTimeoutSeconds, int drainTimeoutSeconds) {
    public VDProfileSpec { sources = Map.copyOf(sources); }
    public record Source(UUID deviceProfileVersionId, boolean required, Set<Device.SourceMode> sourceModes) {
        public Source { sourceModes = Set.copyOf(sourceModes); }
    }
}
