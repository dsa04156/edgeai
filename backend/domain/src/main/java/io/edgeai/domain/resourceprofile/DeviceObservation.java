package io.edgeai.domain.resourceprofile;

/**
 * Device-level telemetry from an existing exporter. Values always describe the whole device on its node (they may
 * include other processes or sharing the labels do not show); TARGET_ASSOCIATED only means the exporter's Pod labels
 * name the target's Pods and nothing else. It is association, not exclusive ownership or per-model usage.
 */
public record DeviceObservation(DeviceRef device, String exporter, String model, Attribution attribution,
                                ObservationStatus status, UsageSummary utilization, UsageSummary memoryUsed,
                                Double memoryTotalBytes, String reason) {
    public enum Attribution { TARGET_ASSOCIATED, NODE_DEVICE_SHARED, NODE_DEVICE_UNKNOWN_OWNER }
    /** No exporter here proves exclusive allocation, so device values are never the workload's own measurement. */
    public boolean perWorkload() { return false; }
}
