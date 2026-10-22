package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.List;

/** Durable, bounded handoff of stored qualified originals whose exact legacy duplicate still has to be hidden. */
public interface SensorReconciliationQueue {
    record Entry(SensorTelemetryRepository.Observation observation,String series,long sequence,int attempts) {}
    /** False when the queue already holds {@code limit} entries; a repeated observation is accepted once. */
    boolean enqueue(SensorTelemetryRepository.Observation observation,String series,long sequence,Instant now,long limit);
    List<Entry> due(Instant now,int limit);
    void complete(Entry entry);
    void retry(Entry entry,Instant next);
    /** Removes entries whose originals are older than {@code before}; the bucket already expired them. */
    int expire(Instant before);
    long count();
}
