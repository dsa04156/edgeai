package io.edgeai.app.config;
import io.edgeai.domain.runtime.RuntimePod;
import io.edgeai.domain.vd.VDTaskProducer;
import java.util.UUID;
public record RunnerPrincipal(UUID attemptId,long epoch,RuntimePod pod,VDTaskProducer vdProducer) {
    public RunnerPrincipal(UUID attemptId,long epoch,RuntimePod pod){this(attemptId,epoch,pod,null);}
    public RunnerPrincipal {
        if((pod==null)==(vdProducer==null))throw new IllegalArgumentException("Exactly one producer identity is required");
    }
    public UUID podUid(){return vdProducer==null?pod.podUid():vdProducer.podUid();}
}
