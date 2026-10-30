package io.edgeai.domain.aimodel;

import io.edgeai.domain.resourceprofile.WorkloadTarget;
import java.util.List;
import java.util.UUID;

/**
 * The one temporary measurement Pod of a profiling run as the server intends it: the registered digest-pinned platform
 * image with its command/args (or the run's benchmark command), a fixed test allocation and the selected node. Every
 * value is validated here, so a gateway only translates it into a Job and never accepts YAML, volumes or privileges.
 */
public record ProfilingJob(UUID runId, UUID modelId, String nodeName, String architecture, String image,
                           List<String> command, List<String> args, long cpuMillicores, long memoryBytes,
                           String acceleratorResource, int acceleratorCount, Integer port, int durationSeconds,
                           int activeDeadlineSeconds) {
    public static final String CONTAINER = "model";
    public static final long MAX_CPU_MILLICORES = 64_000, MAX_MEMORY_BYTES = 256L << 30;
    public static final int MAX_DEADLINE_SECONDS = 4 * 3600;

    public ProfilingJob {
        if (runId == null || modelId == null) throw new IllegalArgumentException("Run and model are required");
        WorkloadTarget.name(nodeName, "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*", 253, "node");
        if (!ImagePlatform.RUNNABLE_ARCHITECTURES.contains(architecture)) throw new IllegalArgumentException("Unsupported architecture");
        if (image == null || !image.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,300}@sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Profiling image must be pinned by digest");
        command = exec(command);
        args = exec(args);
        if (cpuMillicores < 1 || cpuMillicores > MAX_CPU_MILLICORES || memoryBytes < 1 || memoryBytes > MAX_MEMORY_BYTES)
            throw new IllegalArgumentException("Test allocation out of range");
        if (acceleratorResource == null ? acceleratorCount != 0 : acceleratorCount < 1 || acceleratorCount > 16
            || !acceleratorResource.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+/[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Invalid accelerator allocation");
        if (port != null && (port < 1 || port > 65535)) throw new IllegalArgumentException("Invalid port");
        if (durationSeconds < 1 || activeDeadlineSeconds <= durationSeconds || activeDeadlineSeconds > MAX_DEADLINE_SECONDS)
            throw new IllegalArgumentException("Invalid deadline");
    }
    private static List<String> exec(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        if (values.size() > 32) throw new IllegalArgumentException("At most 32 exec items");
        for (String v : values)
            if (v == null || v.isEmpty() || v.length() > 4096 || v.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid exec item");
        return List.copyOf(values);
    }
    public static String jobName(UUID runId) { return "edgeai-mp-" + runId; }
}
