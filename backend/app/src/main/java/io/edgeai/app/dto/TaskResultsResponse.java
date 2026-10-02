package io.edgeai.app.dto;

import io.edgeai.domain.storage.TaskResult;
import java.time.Instant;
import java.util.*;

/** Read-only verified metadata. Credentials and expiring download/upload URLs never enter this response. */
public record TaskResultsResponse(UUID taskId,List<Result> items) {
    public record Artifact(String port,String bucket,String objectKey,String objectVersion,String sha256,long bytes,String mediaType) {}
    public record Result(UUID id,UUID taskId,UUID attemptId,UUID runtimeId,long epoch,UUID producerPodUid,String manifestDigest,Instant createdAt,List<Artifact> artifacts,UUID remoteAllocationId,String remoteSourceMode) {}
    public static TaskResultsResponse from(UUID taskId,Optional<TaskResult> result,String remoteSourceMode) {
        return new TaskResultsResponse(taskId,result.stream().map(r->new Result(r.id(),r.taskId(),r.attemptId(),r.runtimeId(),r.epoch(),r.producerPodUid(),r.manifestDigest(),r.createdAt(),
            r.outputs().stream().map(o->new Artifact(o.port(),o.artifact().bucket(),o.artifact().objectKey(),o.artifact().versionId(),o.artifact().sha256(),o.artifact().bytes(),o.artifact().mediaType())).toList(),r.remoteAllocationId(),remoteSourceMode)).toList());
    }
}
