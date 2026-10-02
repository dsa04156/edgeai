package io.edgeai.domain.repository;
import io.edgeai.domain.vd.VDPoll;
import java.util.*;
/** Poll writes require the owning VD lock and the same transaction as lease/command changes. */
public interface VDPollRepository {
    Optional<VDPoll> find(UUID runtimeId);
    void save(VDPoll poll);
}
