package io.edgeai.domain.repository;
import io.edgeai.domain.runtime.RuntimeTelemetry;
import java.util.*;
public interface TelemetryRepository {
    Optional<RuntimeTelemetry> find(UUID attempt,long sequence);
    List<RuntimeTelemetry> recent(UUID attempt,int limit);
    void append(RuntimeTelemetry sample);
}
