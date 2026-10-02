package io.edgeai.domain.node;
import java.time.Instant;
import java.util.UUID;
public record ExecutionNode(UUID id, String name, String architecture, String operatingSystem,
                            String observedStatus, String cpu, String memory, String labelsJson, Instant observedAt) {
    public String status(Instant now) {
        if (observedStatus.equals("REMOVED")) return "REMOVED";
        return observedAt.plusSeconds(60).isBefore(now) ? "STALE" : observedStatus;
    }
}
