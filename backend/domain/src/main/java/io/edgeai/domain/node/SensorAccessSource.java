package io.edgeai.domain.node;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bounded sensor history and commands through EdgeX Core Data/Core Command. */
public interface SensorAccessSource {
    record Reading(String resource, String valueType, String value, String units, Instant observedAt) {}
    record Readings(String device, Instant fetchedAt, List<Reading> readings) {}
    record Parameter(String resource, String valueType) {}
    record Command(String name, boolean readable, boolean writable, List<Parameter> parameters) {}
    record Commands(String device, String adminState, String operatingState, List<Command> commands) {}
    record CommandResult(String device, String command, String method, Instant completedAt, List<Reading> readings) {}
    Readings readings(String device, String resource, int limit);
    Commands commands(String device);
    CommandResult execute(String device, String command, String method, Map<String,String> values);
    final class Failure extends RuntimeException {
        public enum Kind { NOT_FOUND, UNAVAILABLE, INVALID, LOCKED }
        private final Kind kind;
        public Failure(Kind kind, String message) { super(message); this.kind=kind; }
        public Kind kind() { return kind; }
    }
}
