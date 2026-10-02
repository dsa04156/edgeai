package io.edgeai.app.config;
import io.edgeai.domain.runtime.RuntimePod;
import java.util.UUID;
public record RunnerPrincipal(UUID attemptId,long epoch,RuntimePod pod) {}
