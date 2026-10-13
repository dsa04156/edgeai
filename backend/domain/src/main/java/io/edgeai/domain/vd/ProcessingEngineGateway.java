package io.edgeai.domain.vd;

import java.util.UUID;

/** Attests the real shared engine Pod independently from per-VD runtimes. */
public interface ProcessingEngineGateway {
    record Identity(UUID podUid,String podName,String nodeName) {}
    Identity authenticate(String projectedToken);
}
