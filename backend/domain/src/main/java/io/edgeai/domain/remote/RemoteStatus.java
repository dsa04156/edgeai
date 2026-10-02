package io.edgeai.domain.remote;
import java.time.Instant;
import java.util.*;
/** Provider state is an observation, never proof that a platform TaskResult is committed. */
public record RemoteStatus(RemoteIdentity identity,long revision,String requestDigest,State state,Instant expiresAt,
        String failureReason,String sourceMode,List<RemoteFile> outputs) {
    public enum State { ALLOCATED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED }
    public RemoteStatus {
        Objects.requireNonNull(identity);Objects.requireNonNull(state);outputs=List.copyOf(outputs);
        if(revision<1 || revision>9007199254740991L || !Set.of("SYNTHETIC","EXTERNAL").contains(sourceMode) ||
            requestDigest!=null && !requestDigest.matches("sha256:[a-f0-9]{64}") || outputs.size()>16 || outputs.stream().map(RemoteFile::port).distinct().count()!=outputs.size())
            throw new IllegalArgumentException("Invalid remote observation");
        if(requestDigest==null && (state!=State.CANCELLED || expiresAt!=null))throw new IllegalArgumentException("Only cancellation tombstones can lack a request");
        if(requestDigest!=null && expiresAt==null || state==State.SUCCEEDED && outputs.isEmpty() || state!=State.SUCCEEDED && !outputs.isEmpty())
            throw new IllegalArgumentException("Invalid remote result state");
        if((state==State.FAILED)!=(failureReason!=null) || failureReason!=null && !Set.of("LEASE_EXPIRED","WORKLOAD_FAILED","PROVIDER_RESTART","INPUT_INVALID","OUTPUT_INVALID").contains(failureReason))
            throw new IllegalArgumentException("Invalid remote failure state");
    }
}
