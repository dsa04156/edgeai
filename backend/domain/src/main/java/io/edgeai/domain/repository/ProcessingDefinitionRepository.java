package io.edgeai.domain.repository;

import java.time.Instant;
import java.util.*;

public interface ProcessingDefinitionRepository {
    void append(UUID vdId,long revision,UUID profileVersionId,String configuration,String sources,String rules,Instant at);
    List<Map<String,Object>> versions(UUID vdId,int limit,int offset);
}
