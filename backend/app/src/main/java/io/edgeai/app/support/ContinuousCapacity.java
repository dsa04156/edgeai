package io.edgeai.app.support;

import java.util.*;

/** Conservative estimate includes row/index overhead and input provenance, with 50% headroom. Benchmark before raising limits. */
public final class ContinuousCapacity {
    private ContinuousCapacity() {}
    public static long historyBytes(Object value,int inputs) {
        var config=DeviceProfileCompiler.map(value);long outputs=((List<?>)config.get("outputs")).size();
        return (long)Math.ceil((256+384L*inputs+512L*outputs)*86400.0*((Number)config.get("retentionDays")).intValue()/((Number)config.get("intervalSeconds")).intValue()*1.5);
    }
}
