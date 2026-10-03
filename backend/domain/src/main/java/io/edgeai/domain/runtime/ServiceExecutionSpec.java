package io.edgeai.domain.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ServiceExecutionSpec(String image, List<String> command, List<String> args,
        ResourceRequirements resources, List<String> architectures,
        Map<String, InputPort> inputs, Map<String, OutputPort> outputs, int timeoutSeconds,
        Map<String, String> nodeSelector, List<Toleration> tolerations, String runtimeClassName,String recoveryMode,
        StreamExecutionSpec stream) {
    public static final long MAX_FILE_BYTES = 268435456L;
    public static final long MAX_WORK_BYTES = 1073741824L;
    public ServiceExecutionSpec {
        if (image == null || image.length() > 512 || !image.matches("[a-z0-9]+([._:/-][a-z0-9]+)*@sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Image requires a lowercase repository and sha256 digest");
        command = arguments(command, 32); args = arguments(args, 128);
        if (command.isEmpty() || command.getFirst().isBlank()) throw new IllegalArgumentException("Workload command is required");
        if (resources == null) throw new IllegalArgumentException("Resources required");
        architectures = List.copyOf(architectures);
        if (architectures.isEmpty() || architectures.size() != Set.copyOf(architectures).size()
                || !Set.of("amd64", "arm64").containsAll(architectures))
            throw new IllegalArgumentException("Runner supports unique amd64/arm64 architectures");
        inputs = Map.copyOf(inputs); outputs = Map.copyOf(outputs);
        if (inputs.size() > 16 || outputs.isEmpty() || outputs.size() > 16)
            throw new IllegalArgumentException("At most 16 ports in each direction and at least one output are required");
        inputs.keySet().forEach(RuntimeNames::port); outputs.keySet().forEach(RuntimeNames::port);
        long bytes = 16777216L;
        for (var port : inputs.values()) bytes += port.maxBytes();
        for (var port : outputs.values()) bytes += port.maxBytes();
        if (stream != null) {
            bytes += stream.workBytes();
            if (!java.util.Collections.disjoint(inputs.keySet(), stream.inputs().keySet())
                    || !java.util.Collections.disjoint(outputs.keySet(), stream.outputs().keySet()))
                throw new IllegalArgumentException("File and stream ports must have distinct names in each direction");
        }
        if (bytes > MAX_WORK_BYTES) throw new IllegalArgumentException("Combined file budget exceeds work volume limit");
        if (timeoutSeconds < 1 || timeoutSeconds > 86400) throw new IllegalArgumentException("Timeout must be 1..86400 seconds");
        nodeSelector = Map.copyOf(nodeSelector);
        if (nodeSelector.size() > 32) throw new IllegalArgumentException("Too many node selectors");
        nodeSelector.forEach((key, value) -> { RuntimeNames.qualified(key); RuntimeNames.labelValue(value); });
        if (nodeSelector.containsKey("kubernetes.io/os") && !nodeSelector.get("kubernetes.io/os").equals("linux"))
            throw new IllegalArgumentException("Runner requires Linux");
        if (nodeSelector.containsKey("kubernetes.io/arch") && !architectures.contains(nodeSelector.get("kubernetes.io/arch")))
            throw new IllegalArgumentException("Node selector conflicts with architecture requirements");
        tolerations = List.copyOf(tolerations);
        if (tolerations.size() > 16) throw new IllegalArgumentException("Too many tolerations");
        if (runtimeClassName != null) RuntimeNames.dns(runtimeClassName, 253);
        if(!Set.of("NONE","RESTART","CHECKPOINT").contains(recoveryMode))throw new IllegalArgumentException("Unknown recovery mode");
        if ((stream != null) != "CHECKPOINT".equals(recoveryMode))
            throw new IllegalArgumentException("Stream execution requires explicit CHECKPOINT recovery");
    }
    static List<String> arguments(List<String> values, int maximum) {
        var result = List.copyOf(values);
        if (result.size() > maximum) throw new IllegalArgumentException("Too many command arguments");
        for (String value : result) if (value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid command argument");
        return result;
    }
    public long workBytes() {
        return 16777216L + inputs.values().stream().mapToLong(InputPort::maxBytes).sum()
            + outputs.values().stream().mapToLong(OutputPort::maxBytes).sum() + (stream == null ? 0 : stream.workBytes());
    }
    static void validatePort(String mediaType, long maxBytes) {
        if (mediaType == null || mediaType.length() > 128 || !mediaType.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*"))
            throw new IllegalArgumentException("Port requires a canonical media type without parameters");
        if (maxBytes < 1 || maxBytes > MAX_FILE_BYTES) throw new IllegalArgumentException("File limit must be 1..256 MiB");
    }
    public record InputPort(String mediaType, long maxBytes, boolean required) {
        public InputPort { validatePort(mediaType, maxBytes); }
    }
    public record OutputPort(String mediaType, long maxBytes) {
        public OutputPort { validatePort(mediaType, maxBytes); }
    }
    public record Toleration(String key, String operator, String value, String effect, Long seconds) {
        public Toleration {
            RuntimeNames.qualified(key); RuntimeNames.labelValue(value);
            if (!Set.of("Equal", "Exists").contains(operator) || (operator.equals("Exists") && !value.isEmpty()))
                throw new IllegalArgumentException("Invalid toleration operator/value");
            if (!Set.of("NoSchedule", "PreferNoSchedule", "NoExecute").contains(effect))
                throw new IllegalArgumentException("Toleration effect required");
            if (seconds != null && (!effect.equals("NoExecute") || seconds < 0 || seconds > 86400))
                throw new IllegalArgumentException("Invalid tolerationSeconds");
        }
    }
}
