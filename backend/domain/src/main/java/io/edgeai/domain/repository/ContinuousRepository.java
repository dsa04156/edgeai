package io.edgeai.domain.repository;

import io.edgeai.domain.vd.VDOperation;
import java.time.Instant;
import java.util.*;

public interface ContinuousRepository {
    record Execution(UUID vdId,UUID generation,boolean running,long revision,String state,String reason,Instant updatedAt) {}
    record Session(String engineId,UUID podUid,UUID sessionId,long sequence,String digest,Instant expiresAt) {}
    record Result(UUID vdId,String outputId,UUID generation,String evaluationId,long sequence,Instant evaluationAt,String json) {}
    Optional<Execution> find(UUID id);
    List<Execution> desired();
    Optional<VDOperation> operation(UUID id);
    Optional<VDOperation> operationByKey(UUID id,UUID key);
    VDOperation request(UUID id,UUID key,long revision,String action,Instant now);
    int activeDevices();
    int activeOutputs();
    long estimatedHistoryBytes();
    void lockCapacity();
    Session renew(String engineId,UUID podUid,UUID sessionId,long sequence,String digest,Instant now);
    Optional<Session> session(String engineId);
    Optional<Session> sessionLocked(String engineId);
    Execution reset(UUID vdId,UUID generation,String reason,Instant now);
    void observe(UUID vdId,UUID generation,String state,String reason,Instant now);
    boolean appendLatest(Result result);
    boolean accept(Result result,Instant now);
    int maintainReceipts(Instant now);
    void markHistoryStored(UUID vdId,String outputId,UUID generation,long sequence,String json);
    List<String> latest(UUID vdId);
    void gap(UUID vdId,UUID generation,String reason,boolean open,Instant at);
    List<Map<String,Object>> gaps(UUID vdId,Instant from,Instant to);
}
