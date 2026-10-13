package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Original measurement archive; independent of latest twins and the control database. */
public interface SensorTelemetryRepository {
    record Observation(UUID deviceUid,String property,Instant observedAt,String documentJson) {}
    boolean enabled();
    void append(Observation observation);
    Map<String,Object> readSensor(UUID deviceUid,String property,Instant from,Instant to,int limit,String cursor);
    List<Map<String,Object>> sensorSeries();
    int rawRetentionDays();
}
