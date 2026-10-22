package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Original measurement archive; independent of latest twins and the control database. */
public interface SensorTelemetryRepository {
    record Observation(UUID deviceUid,String property,Instant observedAt,String documentJson) {}
    boolean enabled();
    void append(Observation observation);
    Map<String,Object> readSensor(UUID deviceUid,String property,Instant from,Instant to,int limit,String cursor);
    default Map<String,Object> readSensor(UUID deviceUid,String property,Instant from,Instant to,int limit,String cursor,boolean descending) {
        if(descending)throw new UnsupportedOperationException("Latest-first sensor history is not supported");
        return readSensor(deviceUid,property,from,to,limit,cursor);
    }
    /**
     * Chart points for [from,to): all originals when {@code originalsWithinBudget} and at most {@code pointBudget} exist,
     * otherwise the first/min/max/last originals of each epoch-aligned {@code windowSeconds} window with exact per-window counts.
     */
    default Map<String,Object> readSensorChart(UUID deviceUid,String property,Instant from,Instant to,int windowSeconds,int pointBudget,boolean originalsWithinBudget) {
        throw new UnsupportedOperationException("Full-period sensor charts are not supported");
    }
    List<Map<String,Object>> sensorSeries();
    int rawRetentionDays();
}
