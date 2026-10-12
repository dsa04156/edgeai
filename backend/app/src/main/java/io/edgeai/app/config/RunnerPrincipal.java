package io.edgeai.app.config;
import io.edgeai.domain.runtime.RuntimePod;
import java.util.Objects;
import java.util.UUID;
public record RunnerPrincipal(UUID attemptId,long epoch,RuntimePod pod) {
    public RunnerPrincipal { Objects.requireNonNull(pod); }
    public UUID podUid(){return pod.podUid();}
}
