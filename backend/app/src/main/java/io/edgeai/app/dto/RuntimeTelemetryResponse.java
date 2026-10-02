package io.edgeai.app.dto;
import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.time.Instant;
import java.util.UUID;
public record RuntimeTelemetryResponse(UUID attemptId,long sequence,Instant observedAt,Instant receivedAt,int intervalMillis,
        String resourceSource,Long cpuUsageMicros,Long cpuLimitMillicores,Long memoryBytes,Long memoryLimitBytes,
        String latencySource,Long latencyMicros,Instant latencyObservedAt) {
    public static RuntimeTelemetryResponse from(RuntimeTelemetry value) {
        if(value==null)return null;
        return new RuntimeTelemetryResponse(value.attemptId(),value.sequence(),value.observedAt(),value.receivedAt(),value.intervalMillis(),
            value.cpuUsageMicros()==null && value.memoryBytes()==null?null:"CGROUP_V2",value.cpuUsageMicros(),value.cpuLimitMillicores(),value.memoryBytes(),value.memoryLimitBytes(),
            value.latencyMicros()==null?null:"WORKLOAD",value.latencyMicros(),value.latencyObservedAt());
    }
}
