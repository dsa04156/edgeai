package io.edgeai.app.service;

import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.workflow.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

/** All producer/state mutations serialize on the same Run lock as user cancellation. No network I/O here. */
@Service
public class RuntimeLifecycleService {
    private final RuntimeRepository runtimes;
    private final ExecutionRepository executions;
    private final WorkflowRepository workflows;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final Clock clock;
    public RuntimeLifecycleService(RuntimeRepository runtimes,ExecutionRepository executions,WorkflowRepository workflows,
            ProfileRepository profiles,NodeRepository nodes,Clock clock) {
        this.runtimes=runtimes;this.executions=executions;this.workflows=workflows;this.profiles=profiles;this.nodes=nodes;this.clock=clock;
    }
    public record InputArtifact(String port,VerifiedArtifact artifact) {}
    public record Assignment(RuntimeInstance runtime,ServiceExecutionSpec spec,String parametersJson,List<InputArtifact> inputs) {
        public Assignment { inputs=List.copyOf(inputs); }
    }
    public record CommitPermit(RuntimeInstance runtime,ResultManifest manifest,String digest,TaskResult replay) {}
    private record Context(WorkflowRun run,Task task,TaskAttempt attempt) {}

    @Transactional
    public RuntimeInstance plan(UUID attemptId,String namespace) {
        RuntimeNames.dns(namespace,63);
        if(namespace.contains("."))throw new IllegalArgumentException("Runtime namespace requires a DNS label");
        var c=lock(attemptId);var existing=runtimes.byAttempt(attemptId);
        if(existing.isPresent())return existing.get();
        if(!c.attempt().state().equals("QUEUED") || !c.task().state().equals("READY") || !Set.of("PENDING","RUNNING").contains(c.run().state()))throw fenced();
        validateDag(c.run().workflowVersionId());
        var spec=spec(c);inputs(c,spec);parameters(c);
        var now=clock.instant();
        var value=new RuntimeInstance(UUID.randomUUID(),attemptId,c.task().id(),c.run().id(),c.attempt().epoch(),namespace,
            "edgeai-"+attemptId,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,now,now);
        runtimes.create(value);return value;
    }
    @Transactional(readOnly=true)
    public void validateDag(UUID versionId) {
        var dag=storedDag(workflows.version(versionId).orElseThrow().dagJson());
        var specs=new HashMap<String,ServiceExecutionSpec>();
        for(var task:dag.tasks())specs.put(task.key(),ServiceExecutionInput.parseSpec(profiles.find(task.serviceProfileVersionId()).orElseThrow().specJson()));
        for(var edge:dag.dependencies()) {
            if(edge.mode()!=Dag.Mode.BATCH)throw new IllegalArgumentException("Runtime supports BATCH edges");
            var source=specs.get(edge.fromTask()).outputs().get(edge.fromPort());
            var target=specs.get(edge.toTask()).inputs().get(edge.toPort());
            if(source==null || target==null || !source.mediaType().equals(target.mediaType()) || source.maxBytes()>target.maxBytes())
                throw new IllegalArgumentException("Incompatible DAG artifact ports or budgets");
        }
        specs.forEach((key,spec)->spec.inputs().forEach((port,input)->{
            if(input.required() && dag.dependencies().stream().noneMatch(e->e.toTask().equals(key)&&e.toPort().equals(port)))
                throw new IllegalArgumentException("Required input has no producer");
        }));
    }
    @Transactional
    public RuntimeInstance submitted(UUID attemptId,UUID jobUid) {
        Objects.requireNonNull(jobUid);var c=lock(attemptId);var r=runtime(attemptId);
        if(r.jobUid()!=null && !r.jobUid().equals(jobUid))throw fenced();
        if(r.observedState().equals("TERMINATED") && !r.desiredState().equals("STOPPED"))throw fenced();
        runtimes.submitted(r.id(),jobUid,clock.instant().plusSeconds(spec(c).timeoutSeconds()),clock.instant());
        // A CREATE response may arrive after cancellation and after an earlier DELETE saw no Job.
        if(r.desiredState().equals("STOPPED"))runtimes.stop(r.id(),null,clock.instant());
        return runtime(attemptId);
    }
    @Transactional
    public Assignment claim(UUID attemptId,long epoch,RuntimePod pod) {
        var c=lock(attemptId);var r=runtime(attemptId);active(c,r,epoch);
        if(!pod.jobUid().equals(r.jobUid()) || (r.producerPodUid()!=null && !r.producerPodUid().equals(pod.podUid())))throw fenced();
        if(c.run().nodeId()!=null) {
            var expected=nodes.find(c.run().nodeId()).orElseThrow(RuntimeLifecycleService::fenced);
            if(!expected.id().equals(pod.nodeUid()) || !expected.name().equals(pod.nodeName()))throw fenced();
        }
        if(r.producerPodUid()!=null && (!r.nodeUid().equals(pod.nodeUid()) || !r.nodeName().equals(pod.nodeName())))throw fenced();
        var spec=spec(c);var inputs=inputs(c,spec);var parameters=parameters(c);
        runtimes.claimed(r.id(),pod,clock.instant());return new Assignment(runtime(attemptId),spec,parameters,inputs);
    }
    @Transactional
    public Assignment authorize(UUID attemptId,long epoch,UUID podUid) {
        var c=lock(attemptId);var r=runtime(attemptId);producer(c,r,epoch,podUid);
        var spec=spec(c);return new Assignment(r,spec,parameters(c),inputs(c,spec));
    }
    @Transactional
    public CommitPermit prepareCommit(UUID attemptId,long epoch,UUID podUid,ResultManifest manifest) {
        var c=lock(attemptId);var r=runtime(attemptId);String digest=digest(manifest);
        var replay=replay(r,epoch,podUid,digest);
        if(replay!=null)return new CommitPermit(r,manifest,digest,replay);
        producer(c,r,epoch,podUid);validateManifest(spec(c),manifest);
        return new CommitPermit(r,manifest,digest,null);
    }
    @Transactional
    public Creation<TaskResult> commitVerified(CommitPermit permit,List<TaskResult.Output> verified) {
        var c=lock(permit.runtime().attemptId());var r=runtime(c.attempt().id());
        var replay=replay(r,permit.runtime().epoch(),permit.runtime().producerPodUid(),permit.digest());
        if(replay!=null)return new Creation<>(replay,false);
        producer(c,r,permit.runtime().epoch(),permit.runtime().producerPodUid());
        validateManifest(spec(c),permit.manifest());
        if(!digest(permit.manifest()).equals(permit.digest()) || verified.size()!=permit.manifest().outputs().size())throw new IllegalArgumentException("Invalid verification receipt");
        for(var output:permit.manifest().outputs()) {
            var matches=verified.stream().filter(v->v.port().equals(output.port())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Missing verified output");
            var artifact=matches.getFirst().artifact();var content=output.content(r.taskId(),r.attemptId());
            if(!artifact.objectKey().equals(content.objectKey()) || !artifact.versionId().equals(output.versionId()) ||
                    !artifact.sha256().equals(output.sha256()) || artifact.bytes()!=output.bytes() || !artifact.mediaType().equals(output.mediaType()))
                throw new IllegalArgumentException("Verification receipt differs from declared artifact");
        }
        var now=clock.instant();var result=new TaskResult(UUID.randomUUID(),r.taskId(),r.attemptId(),r.id(),r.epoch(),r.producerPodUid(),permit.digest(),now,verified);
        runtimes.commit(result,now);runtimes.releaseReadyChildren(r.runId(),now);executions.reconcileRunState(r.runId(),now);
        return new Creation<>(result,true);
    }
    @Transactional
    public void fail(UUID attemptId,long epoch,UUID podUid,String reason) {
        if(!Set.of("WORKLOAD_FAILED","TIMEOUT","INPUT_INVALID","OUTPUT_INVALID","STORAGE_FAILED","CANCELLED","RUNNER_FAILED").contains(reason))
            throw new IllegalArgumentException("Unknown Runner failure code");
        var c=lock(attemptId);var r=runtime(attemptId);
        if(c.attempt().state().equals("FAILED") && r.epoch()==epoch && Objects.equals(r.producerPodUid(),podUid) && Objects.equals(r.failureReason(),reason))return;
        producer(c,r,epoch,podUid);var now=clock.instant();runtimes.fail(r.id(),reason,now);
        var descendants=storedDag(workflows.version(c.run().workflowVersionId()).orElseThrow().dagJson()).descendants(c.task().key());
        for(var child:executions.tasks(r.runId()))if(descendants.contains(child.key()))executions.cancelTask(child.id(),"SKIPPED","UPSTREAM_FAILED",now);
        runtimes.stopForRun(r.runId(),now);executions.reconcileRunState(r.runId(),now);
    }
    @Transactional
    public void confirmStopped(UUID attemptId) {
        var c=lock(attemptId);var r=runtime(attemptId);
        if(!r.desiredState().equals("STOPPED"))throw fenced();
        runtimes.terminated(r.id(),clock.instant());executions.reconcileRunState(c.run().id(),clock.instant());
    }
    private TaskResult replay(RuntimeInstance runtime,long epoch,UUID pod,String digest) {
        var result=runtimes.result(runtime.taskId()).orElse(null);if(result==null)return null;
        if(!result.attemptId().equals(runtime.attemptId()) || result.epoch()!=epoch || !result.producerPodUid().equals(pod) || !result.manifestDigest().equals(digest))throw fenced();
        return result;
    }
    private Context lock(UUID attemptId) {
        var initial=executions.attempt(attemptId).orElseThrow(RuntimeLifecycleService::fenced);
        var task=executions.task(initial.taskId()).orElseThrow();
        var run=executions.run(task.runId(),true).orElseThrow();
        return new Context(run,executions.task(initial.taskId()).orElseThrow(),executions.attempt(attemptId).orElseThrow());
    }
    private RuntimeInstance runtime(UUID attemptId) { return runtimes.byAttempt(attemptId).orElseThrow(RuntimeLifecycleService::fenced); }
    private void active(Context c,RuntimeInstance r,long epoch) {
        if(r.epoch()!=epoch || !r.desiredState().equals("RUNNING") || !Set.of("SUBMITTED","RUNNING").contains(r.observedState()) ||
                !Set.of("DISPATCHING","RUNNING").contains(c.attempt().state()) || !c.task().state().equals("RUNNING") || !c.run().state().equals("RUNNING") ||
                r.expiresAt()==null || !clock.instant().isBefore(r.expiresAt()))throw fenced();
    }
    private void producer(Context c,RuntimeInstance r,long epoch,UUID pod) {
        active(c,r,epoch);if(pod==null || !pod.equals(r.producerPodUid()) || !c.attempt().state().equals("RUNNING"))throw fenced();
    }
    private TaskDefinition definition(Context c) {
        return workflows.definitions(c.run().workflowVersionId()).stream().filter(d->d.id().equals(c.task().definitionId())).findFirst().orElseThrow();
    }
    private ServiceExecutionSpec spec(Context c) { return ServiceExecutionInput.parseSpec(profiles.find(definition(c).serviceProfileVersionId()).orElseThrow().specJson()); }
    private String parameters(Context c) {
        var merged=new TreeMap<String,Object>();
        ((Map<?,?>)JSON.decode(definition(c).parametersJson())).forEach((k,v)->merged.put((String)k,v));
        ((Map<?,?>)JSON.decode(c.run().parametersJson())).forEach((k,v)->merged.put((String)k,v));
        return JSON.boundedCanonical(merged,65536);
    }
    private List<InputArtifact> inputs(Context c,ServiceExecutionSpec spec) {
        var tasks=new HashMap<String,Task>();executions.tasks(c.run().id()).forEach(t->tasks.put(t.key(),t));
        var result=new ArrayList<InputArtifact>();
        for(var edge:storedDag(workflows.version(c.run().workflowVersionId()).orElseThrow().dagJson()).dependencies()) {
            if(!edge.toTask().equals(c.task().key()))continue;
            var parent=tasks.get(edge.fromTask());
            if(!parent.state().equals("SUCCEEDED"))throw fenced();
            var source=runtimes.result(parent.id()).orElseThrow(RuntimeLifecycleService::fenced);
            var artifact=source.outputs().stream().filter(o->o.port().equals(edge.fromPort())).findFirst().orElseThrow(RuntimeLifecycleService::fenced).artifact();
            var expected=spec.inputs().get(edge.toPort());
            if(expected==null || artifact.bytes()>expected.maxBytes() || !artifact.mediaType().equals(expected.mediaType()))throw fenced();
            result.add(new InputArtifact(edge.toPort(),artifact));
        }
        return List.copyOf(result);
    }
    private static void validateManifest(ServiceExecutionSpec spec,ResultManifest manifest) {
        if(!spec.outputs().keySet().equals(new HashSet<>(manifest.outputs().stream().map(ResultManifest.Output::port).toList())))
            throw new IllegalArgumentException("Commit must contain every declared output exactly once");
        for(var output:manifest.outputs()) {
            var expected=spec.outputs().get(output.port());
            if(output.bytes()>expected.maxBytes() || !output.mediaType().equals(expected.mediaType()))throw new IllegalArgumentException("Output exceeds port contract");
        }
    }
    private static String digest(ResultManifest manifest) {
        return JSON.digest("edgeai-result-v1",manifest.outputs().stream().map(o->Map.of("port",o.port(),"bytes",o.bytes(),"sha256",o.sha256(),"mediaType",o.mediaType(),"versionId",o.versionId())).toList());
    }
    private static io.edgeai.app.exception.ControlPlaneException fenced() { return error(409,"PRODUCER_FENCED","현재 실행·epoch·Pod claim을 확인하세요."); }
}
