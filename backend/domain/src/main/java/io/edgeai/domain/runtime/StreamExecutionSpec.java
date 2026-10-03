package io.edgeai.domain.runtime;

import java.util.List;
import java.util.Map;

/** Persistent computation; the parent SERVICE command materializes final files from its sealed state. */
public record StreamExecutionSpec(List<String> command, List<String> args, Map<String, Port> inputs,
        Map<String, Port> outputs, int stepTimeoutSeconds, Limits limits) {
    public StreamExecutionSpec {
        command = ServiceExecutionSpec.arguments(command, 32);
        args = ServiceExecutionSpec.arguments(args, 128);
        if (command.stream().anyMatch(String::isEmpty) || args.stream().anyMatch(String::isEmpty))
            throw new IllegalArgumentException("Stream command arguments must not be empty");
        if (command.isEmpty() || command.getFirst().isBlank()) throw new IllegalArgumentException("Stream command required");
        inputs = Map.copyOf(inputs); outputs = Map.copyOf(outputs);
        if (inputs.isEmpty() || inputs.size() > 16 || outputs.size() > 16)
            throw new IllegalArgumentException("Stream requires 1..16 inputs and 0..16 outputs");
        inputs.keySet().forEach(RuntimeNames::port); outputs.keySet().forEach(RuntimeNames::port);
        if (stepTimeoutSeconds < 1 || stepTimeoutSeconds > 3600 || limits == null)
            throw new IllegalArgumentException("Invalid stream step timeout or limits");
    }
    public long workBytes() {
        // Journal/WAL, portable candidate, restore staging and private model state.
        return 6L * (limits.maxBufferBytes() + limits.maxStateBytes()) + 33554432L;
    }
    public record Port(String mediaType, long maxPayloadBytes) {
        public Port {
            ServiceExecutionSpec.validatePort(mediaType, maxPayloadBytes);
            if (mediaType.length() > 127 || maxPayloadBytes > 262144)
                throw new IllegalArgumentException("Stream payload exceeds protocol limit");
        }
    }
    public record Limits(int maxFrames, long maxBufferBytes, long maxStateBytes) {
        public Limits {
            if (maxFrames < 1 || maxFrames > 4096 || maxBufferBytes < 1 || maxBufferBytes > 67108864
                    || maxStateBytes < 1 || maxStateBytes > 1048576)
                throw new IllegalArgumentException("Invalid stream journal limits");
        }
    }
}
