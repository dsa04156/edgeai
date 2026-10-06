package io.edgeai.app.dto;
import java.time.Instant;
import java.util.*;
public record NodeResponse(UUID id, String name, String architecture, String operatingSystem, String status, String cpu, String memory, Object labels, Instant observedAt, Object allocatable) {
    public static NodeResponse from(io.edgeai.domain.node.ExecutionNode n, Instant now) { return new NodeResponse(n.id(),n.name(),n.architecture(),n.operatingSystem(),n.status(now),n.cpu(),n.memory(),io.edgeai.app.support.DeviceInput.JSON.decode(n.labelsJson()),n.observedAt(),io.edgeai.app.support.DeviceInput.JSON.decode(n.allocatableJson())); }
}
