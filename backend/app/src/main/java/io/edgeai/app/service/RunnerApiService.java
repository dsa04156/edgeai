package io.edgeai.app.service;

import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.support.RunnerInput;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.storage.*;
import java.time.*;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** API orchestration keeps S3 calls outside the lifecycle service's transactions. */
public final class RunnerApiService {
    private final RuntimeLifecycleService lifecycle;
    private final RuntimeRepository runtimes;
    private final ArtifactStore storage;
    private final ArtifactCommitService commits;
    private final Clock clock;
    public RunnerApiService(RuntimeLifecycleService lifecycle,RuntimeRepository runtimes,ArtifactStore storage,ArtifactCommitService commits,Clock clock) {
        this.lifecycle=lifecycle;this.runtimes=runtimes;this.storage=storage;this.commits=commits;this.clock=clock;
    }
    public Object claim(RunnerPrincipal principal,String body) {
        RunnerInput.parse(body,principal);
        var current=runtimes.byAttempt(principal.attemptId()).orElseThrow();
        if(current.jobUid()==null)lifecycle.submitted(principal.attemptId(),principal.pod().jobUid());
        var assignment=lifecycle.claim(principal.attemptId(),principal.epoch(),principal.pod());var runtime=assignment.runtime();
        var inputs=new TreeMap<String,Object>();
        for(var input:assignment.inputs()) {
            var artifact=input.artifact();var grant=storage.download(artifact);
            inputs.put(input.port(),Map.of("url",grant.url().toString(),"bytes",artifact.bytes(),"sha256",artifact.sha256(),"mediaType",artifact.mediaType()));
        }
        var outputs=new TreeMap<String,Object>();assignment.spec().outputs().forEach((port,spec)->outputs.put(port,Map.of("mediaType",spec.mediaType(),"maxBytes",spec.maxBytes())));
        var response=new TreeMap<String,Object>(Map.of("runId",runtime.runId().toString(),"taskId",runtime.taskId().toString(),"attemptId",runtime.attemptId().toString(),"epoch",runtime.epoch(),
            "command",assignment.spec().command(),"args",assignment.spec().args(),"parameters",JSON.decode(assignment.parametersJson()),"inputs",inputs,"outputs",outputs,
            "timeoutSeconds",Math.max(1,Math.min(assignment.spec().timeoutSeconds(),Duration.between(clock.instant(),runtime.expiresAt()).toSeconds()))));
        response.put("telemetry",Map.of("intervalSeconds",5));return response;
    }
    public Object uploads(RunnerPrincipal principal,String body) {
        var root=RunnerInput.parse(body,principal,"outputs");var outputs=RunnerInput.outputs(root.get("outputs"),false);
        var assignment=lifecycle.authorize(principal.attemptId(),principal.epoch(),principal.pod().podUid());
        var contents=new ArrayList<ArtifactContent>();
        for(var output:outputs) {
            var content=new ArtifactContent(assignment.runtime().taskId(),principal.attemptId(),text(output.get("port"),100),text(output.get("sha256"),64),
                RunnerInput.integer(output.get("bytes")),text(output.get("mediaType"),128));
            var spec=assignment.spec().outputs().get(content.port());
            if(spec==null || !spec.mediaType().equals(content.mediaType()) || content.bytes()>spec.maxBytes())throw new IllegalArgumentException("Output differs from SERVICE port");
            contents.add(content);
        }
        var grants=new ArrayList<Object>();
        for(var content:contents){var grant=storage.upload(content);grants.add(Map.of("port",content.port(),"url",grant.url().toString(),"headers",grant.headers(),"expiresAt",grant.expiresAt().toString()));}
        return Map.of("outputs",grants);
    }
    public Creation<TaskResult> commit(RunnerPrincipal principal,String body) {
        var root=RunnerInput.parse(body,principal,"outputs");
        return commits.commit(principal.attemptId(),principal.epoch(),principal.pod().podUid(),RunnerInput.manifest(root.get("outputs")));
    }
    public Object fail(RunnerPrincipal principal,String body) {
        var root=RunnerInput.parse(body,principal,"reason");lifecycle.fail(principal.attemptId(),principal.epoch(),principal.pod().podUid(),text(root.get("reason"),64));
        return Map.of("attemptId",principal.attemptId().toString(),"state","FAILED");
    }
}
