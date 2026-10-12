package io.edgeai.domain.repository;

import io.edgeai.domain.vd.VDSourceBinding;
import java.time.Instant;
import java.util.*;

public interface SensorSampleRepository {
    record Sample(Instant observedAt,String valueJson) {}
    void append(UUID deviceUid,String property,String configuration,Sample sample);
    List<Sample> recent(UUID deviceUid,String property,String configuration,Instant earliest,Instant latest,int limit);
    List<VDSourceBinding> watchedSources();
}
