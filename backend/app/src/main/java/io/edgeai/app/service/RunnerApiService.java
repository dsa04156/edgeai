package io.edgeai.app.service;

import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.support.RunnerInput;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.StreamExecutionSpec;
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
    private final RuntimeStartJournal starts;
    private final RuntimeResultPublisher results;
    public RunnerApiService(RuntimeLifecycleService lifecycle,RuntimeRepository runtimes,ArtifactStore storage,ArtifactCommitService commits,Clock clock,RuntimeStartJournal starts,RuntimeResultPublisher results) {
        this.lifecycle=lifecycle;this.runtimes=runtimes;this.storage=storage;this.commits=commits;this.clock=clock;this.starts=starts;this.results=results;
    }
    public Object claim(RunnerPrincipal principal,String body) {
        RunnerInput.parse(body,principal);
        var current=runtimes.byAttempt(principal.attemptId()).orElseThrow();
        if(!current.vd() && current.jobUid()==null)lifecycle.submitted(principal.attemptId(),principal.pod().jobUid());
        var assignment=current.vd()?lifecycle.claimVD(principal.attemptId(),principal.epoch(),principal.vdProducer()):lifecycle.claim(principal.attemptId(),principal.epoch(),principal.pod());var runtime=assignment.runtime();
        var inputs=new TreeMap<String,Object>();
        for(var input:assignment.inputs()) {
            var artifact=input.artifact();var grant=storage.download(artifact);
            inputs.put(input.port(),Map.of("url",grant.url().toString(),"bytes",artifact.bytes(),"sha256",artifact.sha256(),"mediaType",artifact.mediaType()));
        }
        var outputs=new TreeMap<String,Object>();assignment.spec().outputs().forEach((port,spec)->outputs.put(port,Map.of("mediaType",spec.mediaType(),"maxBytes",spec.maxBytes())));
        var response=new TreeMap<String,Object>(Map.of("runId",runtime.runId().toString(),"taskId",runtime.taskId().toString(),"attemptId",runtime.attemptId().toString(),"epoch",runtime.epoch(),
            "command",assignment.spec().command(),"args",assignment.spec().args(),"parameters",JSON.decode(assignment.parametersJson()),"inputs",inputs,"outputs",outputs,
            "timeoutSeconds",Math.max(1,Math.min(assignment.spec().timeoutSeconds(),Duration.between(clock.instant(),runtime.expiresAt()).toSeconds()))));
        response.put("telemetry",Map.of("intervalSeconds",5));
        if(assignment.spec().stream()!=null){
            var stream=assignment.spec().stream();var limits=stream.limits();
            response.put("stream",Map.of("command",stream.command(),"args",stream.args(),
                "inputs",streamPorts(stream.inputs()),"outputs",streamPorts(stream.outputs()),
                "stepTimeoutSeconds",stream.stepTimeoutSeconds(),"limits",Map.of("maxFrames",limits.maxFrames(),
                    "maxBufferBytes",limits.maxBufferBytes(),"maxStateBytes",limits.maxStateBytes())));
        }
        if(!runtime.vd()){
            starts.retainStart(Objects.requireNonNull(assignment.startAuthority()));
            // External persistence must not let a concurrent cancellation or lease expiry issue a stale response.
            lifecycle.authorize(principal.attemptId(),principal.epoch(),principal.podUid());
        }
        response.put("timeoutSeconds",Math.max(1,Math.min(assignment.spec().timeoutSeconds(),Duration.between(clock.instant(),runtime.expiresAt()).toSeconds())));
        return response;
    }
    private static Map<String,Object> streamPorts(Map<String,StreamExecutionSpec.Port> ports){
        var result=new TreeMap<String,Object>();
        ports.forEach((name,port)->result.put(name,Map.of("mediaType",port.mediaType(),"maxPayloadBytes",port.maxPayloadBytes())));
        return result;
    }
    public Object uploads(RunnerPrincipal principal,String body) {
        var root=RunnerInput.parse(body,principal,"outputs");var outputs=RunnerInput.outputs(root.get("outputs"),false);
        var assignment=lifecycle.authorize(principal.attemptId(),principal.epoch(),principal.podUid());
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
        var committed=commits.commit(principal.attemptId(),principal.epoch(),principal.podUid(),RunnerInput.manifest(root.get("outputs")));
        if(committed.value().vdRuntimeId()!=null)return committed;
        return new Creation<>(results.publish(committed.value().runtimeId()),committed.created());
    }
    public Object fail(RunnerPrincipal principal,String body) {
        var root=RunnerInput.parse(body,principal,"reason");lifecycle.fail(principal.attemptId(),principal.epoch(),principal.podUid(),text(root.get("reason"),64));
        return Map.of("attemptId",principal.attemptId().toString(),"state","FAILED");
    }
}
