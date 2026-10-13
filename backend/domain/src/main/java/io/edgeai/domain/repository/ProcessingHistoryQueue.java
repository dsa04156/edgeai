package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Durable, bounded handoff to the time-series store; completed records are removed. */
public interface ProcessingHistoryQueue {
    void enqueue(ProcessingHistoryRepository.Sample sample);
    List<ProcessingHistoryRepository.Sample> pending(int limit);
    void complete(ProcessingHistoryRepository.Sample sample);
    int expire(Instant now);
    long count();
}
