package io.edgeai.domain.vd;

import java.time.Instant;
import java.util.UUID;

public record VDRuntimeBinding(UUID id, UUID vdId, UUID runtimeId, long openedRevision, Long closedRevision,
                               Instant openedAt, Instant closedAt) {}
