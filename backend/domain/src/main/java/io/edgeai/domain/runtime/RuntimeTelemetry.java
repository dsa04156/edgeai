package io.edgeai.domain.runtime;
import java.time.Instant;
import java.util.UUID;

/** Reported cgroup counters and optional application latency, never estimates of node capacity. */
public record RuntimeTelemetry(UUID attemptId,long sequence,Instant observedAt,Instant receivedAt,int intervalMillis,
        Long cpuUsageMicros,Long cpuLimitMillicores,Long memoryBytes,Long memoryLimitBytes,Long latencyMicros,Instant latencyObservedAt) {
    public RuntimeTelemetry {
        if(attemptId==null || observedAt==null || receivedAt==null || sequence<1 || sequence>9007199254740991L || intervalMillis<200 || intervalMillis>60000)
            throw new IllegalArgumentException("Invalid telemetry identity/window");
        bounded(cpuUsageMicros,0,9007199254740991L);bounded(cpuLimitMillicores,1,1000000000L);
        bounded(memoryBytes,0,9007199254740991L);bounded(memoryLimitBytes,1,9007199254740991L);bounded(latencyMicros,0,600000000L);
        if(cpuUsageMicros==null && cpuLimitMillicores!=null || memoryBytes==null && memoryLimitBytes!=null ||
                (latencyMicros==null)!=(latencyObservedAt==null) || cpuUsageMicros==null && memoryBytes==null && latencyMicros==null)
            throw new IllegalArgumentException("Telemetry requires a measured value and consistent units");
    }
    private static void bounded(Long value,long low,long high){if(value!=null && (value<low || value>high))throw new IllegalArgumentException("Telemetry value out of range");}
    public boolean sameMeasurement(RuntimeTelemetry other) {
        return new RuntimeTelemetry(attemptId,sequence,observedAt,other.receivedAt,intervalMillis,cpuUsageMicros,cpuLimitMillicores,memoryBytes,memoryLimitBytes,latencyMicros,latencyObservedAt).equals(other);
    }
}
