package io.edgeai.app.service;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.storage.*;
import java.util.*;

/** Storage reads deliberately occur between two short transactions; the second rechecks cancellation/producer identity. */
public final class ArtifactCommitService {
    private final RuntimeLifecycleService lifecycle;
    private final ArtifactStore storage;
    public ArtifactCommitService(RuntimeLifecycleService lifecycle,ArtifactStore storage) { this.lifecycle=lifecycle;this.storage=storage; }
    public Creation<TaskResult> commit(UUID attemptId,long epoch,UUID podUid,ResultManifest manifest) {
        var permit=lifecycle.prepareCommit(attemptId,epoch,podUid,manifest);
        if(permit.replay()!=null)return new Creation<>(permit.replay(),false);
        var outputs=new ArrayList<TaskResult.Output>();
        for(var output:manifest.outputs())outputs.add(new TaskResult.Output(output.port(),storage.verify(output.content(permit.runtime().taskId(),attemptId),output.versionId())));
        return lifecycle.commitVerified(permit,outputs);
    }
}
