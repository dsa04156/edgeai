package io.edgeai.app.support;

import java.util.*;

/** Conservative estimate includes row/index overhead and input provenance, with 50% headroom. Benchmark before raising limits. */
public final class ContinuousCapacity {
    private ContinuousCapacity() {}
    public static long historyBytes(Object value,int inputs) {
        var config=DeviceProfileCompiler.map(value);var policy=ResultStoragePolicy.policy(config);
        if("NONE".equals(policy.get("mode")))return 0;
        long outputs="SELECTED".equals(policy.get("mode"))?((List<?>)policy.get("outputs")).size():((List<?>)config.get("outputs")).size();
        return (long)Math.ceil((256+384L*inputs+512L*outputs)*86400.0*((Number)config.get("retentionDays")).intValue()/((Number)config.get("intervalSeconds")).intValue()*1.5);
    }
}
