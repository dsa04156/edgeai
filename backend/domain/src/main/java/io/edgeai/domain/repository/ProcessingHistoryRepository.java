package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

/** Independent storage boundary: its failures must not roll back the live control plane. */
public interface ProcessingHistoryRepository {
    record Sample(ContinuousRepository.Result result,int retentionDays,String type,String unit,Object value,String sourcesJson) {}
    boolean enabled();
    void append(List<Sample> samples);
    Map<String,Object> read(UUID vdId,String outputId,Instant from,Instant to,int limit,String cursor,Integer bucketSeconds);
    int maintain(Instant now);
}
