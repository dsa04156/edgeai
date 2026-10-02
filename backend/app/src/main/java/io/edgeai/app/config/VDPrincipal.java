package io.edgeai.app.config;
import io.edgeai.domain.vd.VDGateway;
import java.util.UUID;
public record VDPrincipal(UUID runtimeId,UUID vdId,long generation,VDGateway.PodIdentity pod) {}
