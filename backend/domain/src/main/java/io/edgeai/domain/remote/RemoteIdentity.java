package io.edgeai.domain.remote;
import java.util.UUID;
public record RemoteIdentity(UUID allocationId,UUID runId,UUID taskId,UUID attemptId,long epoch) {
    public RemoteIdentity {
        if(allocationId==null || runId==null || taskId==null || attemptId==null || epoch<1 || epoch>9007199254740991L)
            throw new IllegalArgumentException("Complete remote execution identity required");
    }
}
