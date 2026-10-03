package io.edgeai.domain.repository;

import io.edgeai.domain.stream.StreamCheckpoint;
import java.util.*;

/** Writes require the Run authority lock and a verified fixed object version. */
public interface StreamCheckpointRepository {
    Optional<StreamCheckpoint> latest(UUID taskId);
    Optional<StreamCheckpoint> byAttemptSerial(UUID attemptId,long serial);
    void insert(StreamCheckpoint value);
}
