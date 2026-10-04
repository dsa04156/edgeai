package io.edgeai.app.service;

import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.app.support.RemoteDocuments;
import io.edgeai.domain.remote.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.workflow.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
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
    private final OffloadRepository offloads;
    private final RemoteRepository remotes;
    private final Clock clock;
    private final boolean autoDispatch;
    private final VirtualDeviceRepository vds;
    private final VDRuntimeRepository vdRuntimes;
    private final VDTaskRepository vdTasks;
    private final boolean vdEnabled;
    private final int dispatchSeconds;
    private final StreamExecutionRepository streamExecutions;
    private final StreamRunService streams;
    private final StreamRecoveryService streamRecovery;
    public RuntimeLifecycleService(RuntimeRepository runtimes,ExecutionRepository executions,WorkflowRepository workflows,
            ProfileRepository profiles,NodeRepository nodes,OffloadRepository offloads,RemoteRepository remotes,Clock clock,@Value("${edgeai.runtime.enabled:false}") boolean autoDispatch,
            VirtualDeviceRepository vds,VDRuntimeRepository vdRuntimes,VDTaskRepository vdTasks,@Value("${edgeai.vd.enabled:false}") boolean vdEnabled,@Value("${edgeai.runtime.dispatch-seconds:120}") int dispatchSeconds,
            StreamExecutionRepository streamExecutions,StreamRunService streams,StreamRecoveryService streamRecovery) {
        this.runtimes=runtimes;this.executions=executions;this.workflows=workflows;this.profiles=profiles;this.nodes=nodes;this.offloads=offloads;this.remotes=remotes;this.clock=clock;this.autoDispatch=autoDispatch;
        this.vds=vds;this.vdRuntimes=vdRuntimes;this.vdTasks=vdTasks;this.vdEnabled=vdEnabled;this.dispatchSeconds=dispatchSeconds;
        this.streamExecutions=streamExecutions;
        this.streams=streams;this.streamRecovery=streamRecovery;
    }
    public record InputArtifact(String port,VerifiedArtifact artifact) {}
    public record Assignment(RuntimeInstance runtime,ServiceExecutionSpec spec,String parametersJson,List<InputArtifact> inputs,RuntimeStartAuthority startAuthority) {
        public Assignment { inputs=List.copyOf(inputs); }
        public Assignment(RuntimeInstance runtime,ServiceExecutionSpec spec,String parametersJson,List<InputArtifact> inputs){this(runtime,spec,parametersJson,inputs,null);}
    }
    public record CommitPermit(RuntimeInstance runtime,ResultManifest manifest,String digest,TaskResult replay) {}
    public record Dispatch(RuntimeInstance runtime,ServiceExecutionSpec spec,UUID nodeId,String nodeName,List<String> excludedNodeNames) {}
    public record RemoteDispatch(RuntimeInstance runtime,RemoteAllocation allocation,RemoteWork work,List<InputArtifact> inputs) {}
    private record Context(WorkflowRun run,Task task,TaskAttempt attempt) {}

    @Transactional(readOnly=true)
    public void validateRequest(UUID versionId,String parameters) {
        validateRequest(versionId,parameters,false);
    }
    @Transactional(readOnly=true)
    public void validateRequest(UUID versionId,String parameters,boolean stream) {
        validateDag(versionId,stream);
        for(var definition:workflows.definitions(versionId))mergeParameters(definition.parametersJson(),parameters);
    }
    @Transactional
    public void startRun(UUID runId,String namespace) {
        executions.run(runId,true).orElseThrow();
        streams.releaseReady(runId);
        for(UUID attempt:runtimes.readyAttempts(runId,128))plan(attempt,namespace);
    }
    @Transactional
    public Dispatch dispatch(UUID attemptId) {
        var c=lock(attemptId);var r=runtime(attemptId);
        if(r.remote() || r.vd())throw fenced();
        String name=c.attempt().nodeId()==null?null:nodes.find(c.attempt().nodeId()).orElseThrow(RuntimeLifecycleService::fenced).name();
        return new Dispatch(r,spec(c),c.attempt().nodeId(),name,c.attempt().excludedNodeNames());
    }

    @Transactional(readOnly=true)
    public void validateAutomaticOffload(UUID versionId,boolean stream) {
        validateDag(versionId,stream);
        for(var definition:workflows.definitions(versionId)){
            var spec=ServiceExecutionInput.parseSpec(profiles.find(definition.serviceProfileVersionId()).orElseThrow().specJson());
            if(!spec.recoveryMode().equals("RESTART") && !(stream && spec.stream()!=null && spec.recoveryMode().equals("CHECKPOINT")))
                throw error(409,"OFFLOAD_RECOVERY_UNSUPPORTED","자동 전환은 일반 SERVICE의 RESTART 또는 STREAM의 CHECKPOINT 복구 선언이 필요합니다.");
        }
    }

    @Transactional
    public RuntimeInstance plan(UUID attemptId,String namespace) {
        RuntimeNames.dns(namespace,63);
        if(namespace.contains("."))throw new IllegalArgumentException("Runtime namespace requires a DNS label");
        var c=lockRun(attemptId);var existing=runtimes.byAttempt(attemptId);
        if(existing.isPresent())return existing.get();
        if(c.attempt().mode().equals("VD"))return planVD(c,namespace);
        if(c.attempt().mode().equals("REMOTE"))return planRemote(attemptId,namespace,c.attempt().remoteTarget());
        if(!c.attempt().state().equals("QUEUED") || !c.task().state().equals("READY") || !Set.of("PENDING","RUNNING").contains(c.run().state()))throw fenced();
        validateDag(c.run().workflowVersionId(),streams.managed(c.run().id()));
        var spec=spec(c);inputs(c,spec);parameters(c);
        var now=clock.instant();
        var value=new RuntimeInstance(UUID.randomUUID(),attemptId,c.task().id(),c.run().id(),c.attempt().epoch(),namespace,
            "edgeai-"+attemptId,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,null,null,now,now);
        runtimes.create(value);return value;
    }
    @Transactional
    public void validateVDRequest(UUID vdId,UUID versionId,String namespace) {
        validateVDTaskProfiles(vdId,workflows.definitions(versionId).stream().map(TaskDefinition::serviceProfileVersionId).collect(java.util.stream.Collectors.toSet()),namespace);
    }
    @Transactional
    public void validateVDTaskProfiles(UUID vdId,Set<UUID> serviceProfiles,String namespace) {
        if(!autoDispatch || !vdEnabled)throw error(503,"VD_EXECUTION_DISABLED","VD 실행 설정을 확인하세요.");
        var vd=vds.find(vdId,true).orElseThrow(()->error(404,"VD_NOT_FOUND","VD를 찾을 수 없습니다."));
        var current=vdRuntimes.current(vdId).orElseThrow(()->error(409,"VD_NOT_READY","VD를 먼저 기동하세요."));
        if(vd.state()!=VirtualDevice.State.REGISTERED || !current.ready(clock.instant()) || !current.namespace().equals(namespace))
            throw error(409,"VD_NOT_READY","현재 namespace의 Ready VD만 실행 대상으로 선택할 수 있습니다.");
        if(serviceProfiles.stream().anyMatch(id->!id.equals(vd.serviceProfileVersionId())))
            throw error(409,"VD_SERVICE_MISMATCH","해당 VD에 배치한 작업은 VD와 같은 SERVICE Profile 버전을 사용해야 합니다.");
    }
    private RuntimeInstance planVD(Context c,String namespace) {
        if(!autoDispatch || !vdEnabled)throw error(503,"VD_EXECUTION_DISABLED","VD 실행 설정을 확인하세요.");
        if(!c.attempt().state().equals("QUEUED") || !c.task().state().equals("READY") || !Set.of("PENDING","RUNNING").contains(c.run().state()) ||
            c.attempt().vdId()==null || !c.attempt().vdId().equals(c.task().initialVdId()))throw fenced();
        var vd=vds.find(c.attempt().vdId(),false).orElseThrow(RuntimeLifecycleService::fenced);
        if(!definition(c).serviceProfileVersionId().equals(vd.serviceProfileVersionId()))throw error(409,"VD_SERVICE_MISMATCH","작업과 VD의 SERVICE Profile 버전이 다릅니다.");
        validateDag(c.run().workflowVersionId(),streams.managed(c.run().id()));
        var spec=spec(c);inputs(c,spec);parameters(c);var now=clock.instant();
        // Retries/children can queue during replacement; only the public initial request requires Ready.
        var r=new RuntimeInstance(UUID.randomUUID(),c.attempt().id(),c.task().id(),c.run().id(),c.attempt().epoch(),namespace,null,UUID.randomUUID(),
            "RUNNING","PENDING",null,null,null,null,now.plusSeconds(dispatchSeconds),null,now,now,null,vd.id());
        runtimes.create(r);return r;
    }
    @Transactional
    public VDTaskAllocation allocateVD(UUID attemptId,UUID supervisorId,int slot,long sequence) {
        var c=lock(attemptId);var r=runtime(attemptId);var vr=vdRuntimes.runtime(supervisorId).orElseThrow(RuntimeLifecycleService::fenced);
        var now=clock.instant();
        if(!r.vd() || !r.vdId().equals(vr.vdId()) || !r.namespace().equals(vr.namespace()) || !vr.ready(now) ||
            !r.desiredState().equals("RUNNING") || !r.observedState().equals("PENDING") || !now.isBefore(r.expiresAt()) ||
            !c.attempt().state().equals("DISPATCHING") || !c.task().state().equals("RUNNING") || !c.run().state().equals("RUNNING"))throw fenced();
        var a=new VDTaskAllocation(UUID.randomUUID(),r.id(),r.vdId(),vr.id(),vr.generation(),vr.sessionId(),vr.podUid(),slot,sequence,now,null,null,null,null);
        vdTasks.create(a,now.plusSeconds(spec(c).timeoutSeconds()));return vdTasks.byRuntime(r.id()).orElseThrow();
    }
    @Transactional
    public Assignment claimVD(UUID attemptId,long epoch,VDTaskProducer proof) {
        var c=lock(attemptId);var r=runtime(attemptId);active(c,r,epoch);var a=vdAuthority(r,proof.podUid());
        var vr=vdRuntimes.runtime(a.vdRuntimeId()).orElseThrow(RuntimeLifecycleService::fenced);
        if(!a.vdRuntimeId().equals(proof.runtimeId()) || a.generation()!=proof.generation() || !a.sessionId().equals(proof.sessionId()) ||
            !vr.nodeUid().equals(proof.nodeUid()) || !vr.nodeName().equals(proof.nodeName()))throw fenced();
        var spec=spec(c);var inputs=inputs(c,spec);var parameters=parameters(c);
        var now=clock.instant();vdTasks.claimed(r.id(),proof.podUid(),proof.nodeUid(),proof.nodeName(),now);
        offloads.completedByClaim(attemptId,now);
        return new Assignment(runtime(attemptId),spec,parameters,inputs);
    }
    /** Called only with authenticated completion/absence evidence or confirmed supervisor deletion. */
    @Transactional
    public void finishVD(UUID attemptId,String reason,Long sequence,Integer exitCode) {
        var c=lock(attemptId);var r=runtime(attemptId);if(!r.vd())throw fenced();
        var a=vdTasks.byRuntime(r.id()).orElse(null);
        if(a!=null && !a.open())return;
        if(!Set.of("PROCESS_EXIT","NOT_STARTED","POD_GONE","UNASSIGNED").contains(reason) ||
            a==null && !reason.equals("UNASSIGNED") || a!=null && reason.equals("UNASSIGNED"))throw fenced();
        if(a!=null && reason.equals("POD_GONE") && !vdRuntimes.runtime(a.vdRuntimeId()).orElseThrow().terminal())throw fenced();
        if(a!=null && reason.equals("NOT_STARTED") && (sequence==null || sequence<=a.assignedSequence() || r.producerPodUid()!=null))throw fenced();
        if(r.desiredState().equals("RUNNING"))recordFailure(c,r,reason.equals("PROCESS_EXIT")?(Objects.equals(exitCode,0)?"RESULT_MISSING":"WORKLOAD_FAILED"):"RUNTIME_LOST");
        var now=clock.instant();runtimes.terminated(r.id(),now);
        if(a!=null)vdTasks.close(r.id(),reason,sequence,exitCode,now);
        executions.reconcileRunState(r.runId(),now);
    }
    @Transactional
    public RuntimeInstance planRemote(UUID attemptId,String namespace,RemoteTarget target) {
        RuntimeNames.dns(namespace,63);if(namespace.contains("."))throw new IllegalArgumentException("Worker scope requires a DNS label");
        Objects.requireNonNull(target);var c=lock(attemptId);if(!target.equals(c.attempt().remoteTarget()))throw fenced();var existing=runtimes.byAttempt(attemptId);
        if(existing.isPresent()) {
            var r=existing.get();if(!r.remote() || !r.namespace().equals(namespace) || !remotes.find(r.remoteAllocationId()).orElseThrow().target().equals(target))throw fenced();
            return r;
        }
        if(!c.attempt().mode().equals("REMOTE") || !c.attempt().state().equals("QUEUED") || !c.task().state().equals("READY") || !Set.of("PENDING","RUNNING").contains(c.run().state()))throw fenced();
        validateDag(c.run().workflowVersionId());var spec=spec(c);var inputs=inputs(c,spec);String parameters=parameters(c);
        var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);UUID allocationId=UUID.randomUUID();
        var r=new RuntimeInstance(UUID.randomUUID(),attemptId,c.task().id(),c.run().id(),c.attempt().epoch(),namespace,null,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,
            now.plusSeconds(spec.timeoutSeconds()),null,now,now,allocationId);
        var identity=remoteIdentity(r);var work=new RemoteWork(identity,profiles.find(definition(c).serviceProfileVersionId()).orElseThrow().specJson(),parameters,
            inputs.stream().map(i->new RemoteFile(i.port(),i.artifact().bytes(),i.artifact().sha256(),i.artifact().mediaType())).toList(),r.expiresAt());
        runtimes.create(r);remotes.create(new RemoteAllocation(allocationId,r.id(),target,RemoteDocuments.workJson(work),RemoteDocuments.digest(work),0,"UNKNOWN",null,null,now));return r;
    }
    @Transactional
    public RemoteDispatch remoteDispatch(UUID attemptId) {
        var c=lock(attemptId);var r=runtime(attemptId);if(!r.remote())throw fenced();
        var allocation=remotes.find(r.remoteAllocationId()).orElseThrow();var work=RemoteDocuments.readWork(allocation.workJson());
        if(!work.identity().equals(remoteIdentity(r)) || !work.expiresAt().equals(r.expiresAt()) || !RemoteDocuments.digest(work).equals(allocation.requestDigest()))throw fenced();
        return new RemoteDispatch(r,allocation,work,inputs(c,spec(c)));
    }
    @Transactional
    public RemoteStart remoteStart(UUID attemptId) {
        var c=lock(attemptId);var r=runtime(attemptId);active(c,r,c.attempt().epoch());
        if(!r.remote())throw fenced();
        var allocation=remotes.find(r.remoteAllocationId()).orElseThrow();
        var transfers=offloads.forTask(c.task().id()).stream().filter(o->attemptId.equals(o.targetAttemptId()) ||
            o.members().stream().anyMatch(m->attemptId.equals(m.targetAttemptId()))).toList();
        if(transfers.size()>1)throw fenced();
        var transfer=transfers.isEmpty()?null:transfers.getFirst();
        if(transfer!=null && (transfer.startDeadline()==null || !Set.of("STARTING","SUCCEEDED").contains(transfer.state())))throw fenced();
        return new RemoteStart(remoteIdentity(r),allocation.requestDigest(),r.expiresAt(),
            transfer==null?null:transfer.id(),transfer==null?null:transfer.startDeadline());
    }
    @Transactional
    public RuntimeInstance observeRemote(RemoteStatus status) {
        var allocation=remotes.find(status.identity().allocationId()).orElseThrow(RuntimeLifecycleService::fenced);
        var initial=runtimes.runtime(allocation.runtimeId()).orElseThrow();var c=lock(initial.attemptId());var r=runtime(initial.attemptId());
        allocation=remotes.find(r.remoteAllocationId()).orElseThrow();
        if(!remoteIdentity(r).equals(status.identity()) || !allocation.target().sourceMode().equals(status.sourceMode()))throw fenced();
        boolean tombstone=status.state()==RemoteStatus.State.CANCELLED && status.requestDigest()==null && r.desiredState().equals("STOPPED");
        if(tombstone && !allocation.providerState().equals("UNKNOWN") && !allocation.providerState().equals("CANCELLED"))throw fenced();
        if(!tombstone && (!allocation.requestDigest().equals(status.requestDigest()) || !r.expiresAt().equals(status.expiresAt())))throw fenced();
        String observation=RemoteDocuments.statusJson(status);String old=allocation.observationJson()==null?null:JSON.canonical(JSON.decode(allocation.observationJson()));
        if(status.revision()<allocation.providerRevision())return r;
        if(status.revision()==allocation.providerRevision() && !observation.equals(old) ||
            Set.of("SUCCEEDED","FAILED","CANCELLED").contains(allocation.providerState()) && !observation.equals(old) ||
            allocation.providerState().equals("RUNNING") && status.state()==RemoteStatus.State.ALLOCATED ||
            allocation.providerState().equals("CANCELLING") && status.state()!=RemoteStatus.State.CANCELLING && status.state()!=RemoteStatus.State.CANCELLED && status.state()!=RemoteStatus.State.FAILED)
            throw error(409,"REMOTE_OBSERVATION_CONFLICT","원격 revision과 상태 전이가 기존 관측과 충돌합니다.");
        if(status.state()==RemoteStatus.State.SUCCEEDED)validateRemoteOutputs(spec(c),status.outputs());
        var now=clock.instant();if(status.revision()>allocation.providerRevision())remotes.observe(allocation.id(),status.revision(),status.state().name(),observation,now);
        if(r.desiredState().equals("RUNNING")) {
            if(!Set.of("DISPATCHING","RUNNING").contains(c.attempt().state()) || !c.task().state().equals("RUNNING") || !c.run().state().equals("RUNNING"))throw fenced();
            if(!now.isBefore(r.expiresAt()))recordFailure(c,r,"RUNTIME_TIMEOUT");
            else if(status.state()==RemoteStatus.State.FAILED)recordFailure(c,r,switch(status.failureReason()){
                case "LEASE_EXPIRED"->"RUNTIME_TIMEOUT";case "PROVIDER_RESTART"->"RUNTIME_LOST";default->status.failureReason();});
            else if(status.state()==RemoteStatus.State.CANCELLED || status.state()==RemoteStatus.State.CANCELLING)recordFailure(c,r,"RUNTIME_LOST");
            else {
                if(!offloads.canStart(r.attemptId(),now))throw fenced();
                boolean running=status.state()==RemoteStatus.State.RUNNING || status.state()==RemoteStatus.State.SUCCEEDED;
                runtimes.remoteObserved(r.id(),running,now);if(running)offloads.completedByClaim(r.attemptId(),now);
            }
        }
        if(runtime(r.attemptId()).desiredState().equals("STOPPED") && Set.of(RemoteStatus.State.SUCCEEDED,RemoteStatus.State.FAILED,RemoteStatus.State.CANCELLED).contains(status.state())) {
            runtimes.terminated(r.id(),now);executions.reconcileRunState(r.runId(),now);
        }
        return runtime(r.attemptId());
    }
    @Transactional(readOnly=true)
    public void validateDag(UUID versionId) {
        validateDag(versionId,false);
    }
    private void validateDag(UUID versionId,boolean stream) {
        var dag=storedDag(workflows.version(versionId).orElseThrow().dagJson());
        var specs=new HashMap<String,ServiceExecutionSpec>();
        for(var task:dag.tasks())specs.put(task.key(),ServiceExecutionInput.parseSpec(profiles.find(task.serviceProfileVersionId()).orElseThrow().specJson()));
        if(!stream && specs.values().stream().anyMatch(s -> s.stream() != null))
            throw error(501,"STREAM_NOT_IMPLEMENTED","스트림 실행 배정·완료 확인 연결 전에는 실행할 수 없습니다.");
        for(var edge:dag.dependencies()) {
            if(edge.mode()==Dag.Mode.STREAM){
                var source=specs.get(edge.fromTask()).stream();var target=specs.get(edge.toTask()).stream();
                if(!stream || source==null || target==null)throw new IllegalArgumentException("STREAM dependencies require stream SERVICE ports");
                var output=source.outputs().get(edge.fromPort());var input=target.inputs().get(edge.toPort());
                if(output==null || input==null || !output.mediaType().equals(input.mediaType()) || output.maxPayloadBytes()>input.maxPayloadBytes())
                    throw new IllegalArgumentException("Incompatible STREAM ports or budgets");
                continue;
            }
            var source=specs.get(edge.fromTask()).outputs().get(edge.fromPort());
            var target=specs.get(edge.toTask()).inputs().get(edge.toPort());
            if(source==null || target==null || !source.mediaType().equals(target.mediaType()) || source.maxBytes()>target.maxBytes())
                throw new IllegalArgumentException("Incompatible DAG artifact ports or budgets");
        }
        specs.forEach((key,spec)->spec.inputs().forEach((port,input)->{
            if(input.required() && dag.dependencies().stream().noneMatch(e->e.mode()==Dag.Mode.BATCH && e.toTask().equals(key)&&e.toPort().equals(port)))
                throw new IllegalArgumentException("Required input has no producer");
        }));
    }
    @Transactional
    public RuntimeInstance submitted(UUID attemptId,UUID jobUid) {
        Objects.requireNonNull(jobUid);var c=lock(attemptId);var r=runtime(attemptId);
        if(r.remote() || r.vd())throw fenced();
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
        if(r.remote() || r.vd())throw fenced();
        if(!pod.jobUid().equals(r.jobUid()) || (r.producerPodUid()!=null && !r.producerPodUid().equals(pod.podUid())))throw fenced();
        if(c.attempt().nodeId()!=null) {
            var expected=nodes.find(c.attempt().nodeId()).orElseThrow(RuntimeLifecycleService::fenced);
            if(!expected.id().equals(pod.nodeUid()) || !expected.name().equals(pod.nodeName()))throw fenced();
        }
        if(c.attempt().excludedNodeNames().contains(pod.nodeName()))throw fenced();
        if(r.producerPodUid()!=null && (!r.nodeUid().equals(pod.nodeUid()) || !r.nodeName().equals(pod.nodeName())))throw fenced();
        var spec=spec(c);var inputs=inputs(c,spec);var parameters=parameters(c);
        var transfers=offloads.forTask(c.task().id()).stream().filter(o->attemptId.equals(o.targetAttemptId()) ||
            o.members().stream().anyMatch(m->attemptId.equals(m.targetAttemptId()))).toList();
        if(transfers.size()>1)throw fenced();
        var transfer=transfers.isEmpty()?null:transfers.getFirst();
        if(transfer!=null && (transfer.startDeadline()==null || !Set.of("STARTING","SUCCEEDED").contains(transfer.state())))throw fenced();
        var inputIdentity=inputs.stream().map(i->{var a=i.artifact();return Map.of("port",i.port(),"bucket",a.bucket(),"key",a.objectKey(),
            "versionId",a.versionId(),"bytes",a.bytes(),"sha256",a.sha256(),"mediaType",a.mediaType());}).sorted(Comparator.comparing(i->(String)i.get("port"))).toList();
        var digest=JSON.digest("edgeai-runtime-start-work-v1",Map.of("spec",JSON.decode(profiles.find(definition(c).serviceProfileVersionId()).orElseThrow().specJson()),
            "parameters",JSON.decode(parameters),"inputs",inputIdentity));
        var now=clock.instant();runtimes.claimed(r.id(),pod,now);offloads.completedByClaim(attemptId,now);
        var claimed=runtime(attemptId);
        return new Assignment(claimed,spec,parameters,inputs,new RuntimeStartAuthority(claimed,digest,
            transfer==null?null:transfer.id(),transfer==null?null:transfer.startDeadline(),now));
    }
    @Transactional
    public Assignment authorize(UUID attemptId,long epoch,UUID podUid) {
        var c=lock(attemptId);var r=runtime(attemptId);producer(c,r,epoch,podUid);
        var spec=spec(c);return new Assignment(r,spec,parameters(c),inputs(c,spec));
    }
    /** Stream binding authorization does not resolve BATCH artifact inputs. */
    @Transactional
    public Instant authorizeProducerUntil(UUID attemptId,long epoch,UUID podUid){
        var c=lock(attemptId);var r=runtime(attemptId);producer(c,r,epoch,podUid);var until=r.expiresAt();
        if(r.vd()){
            var allocation=vdAuthority(r,podUid);var supervisor=vdRuntimes.runtime(allocation.vdRuntimeId()).orElseThrow();
            if(supervisor.leaseUntil().isBefore(until))until=supervisor.leaseUntil();
            if(supervisor.drainDeadline()!=null && supervisor.drainDeadline().isBefore(until))until=supervisor.drainDeadline();
        }
        return until;
    }
    /** Group planning/completion only: inspect peer authority under the Run lock without taking another VD mutex.
     * This does not issue credentials or commit a peer result; those paths still lock the caller's VD. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void validateStreamPeer(UUID attemptId,long epoch,UUID podUid){
        var c=lockRun(attemptId);producer(c,runtime(attemptId),epoch,podUid);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void validateStreamOffloadPeer(UUID attemptId,long epoch,UUID podUid){
        var c=lockRun(attemptId);var r=runtime(attemptId);producer(c,r,epoch,podUid);
        if(r.vd()){
            var a=vdAuthority(r,podUid);
            if(!vdRuntimes.runtime(a.vdRuntimeId()).orElseThrow().ready(clock.instant()))throw fenced();
        }
    }
    @Transactional
    public CommitPermit prepareCommit(UUID attemptId,long epoch,UUID podUid,ResultManifest manifest) {
        var c=lock(attemptId);var r=runtime(attemptId);String digest=digest(manifest);
        var replay=replay(r,epoch,podUid,null,digest);
        if(replay!=null)return new CommitPermit(r,manifest,digest,replay);
        producer(c,r,epoch,podUid);requireStreamGrant(c);validateManifest(spec(c),manifest);
        return new CommitPermit(r,manifest,digest,null);
    }
    private void requireStreamGrant(Context context) {
        if(spec(context).stream()!=null && streamExecutions.granted(context.attempt().id()).isEmpty())
            throw error(409,"STREAM_COMPLETION_REQUIRED","연결된 모든 스트림 참여자의 종료 확인 후 결과를 확정할 수 있습니다.");
    }
    @Transactional
    public CommitPermit prepareRemoteCommit(UUID attemptId,long epoch,UUID allocationId,ResultManifest manifest) {
        var c=lock(attemptId);var r=runtime(attemptId);String digest=digest(manifest);
        var replay=replay(r,epoch,null,allocationId,digest);if(replay!=null)return new CommitPermit(r,manifest,digest,replay);
        producer(c,r,epoch,null,allocationId);validateManifest(spec(c),manifest);validateRemoteManifest(r,manifest);
        return new CommitPermit(r,manifest,digest,null);
    }
    @Transactional
    public Creation<TaskResult> commitVerified(CommitPermit permit,List<TaskResult.Output> verified) {
        var c=lock(permit.runtime().attemptId());var r=runtime(c.attempt().id());
        var replay=replay(r,permit.runtime().epoch(),permit.runtime().producerPodUid(),permit.runtime().remoteAllocationId(),permit.digest());
        if(replay!=null)return new Creation<>(replay,false);
        producer(c,r,permit.runtime().epoch(),permit.runtime().producerPodUid(),permit.runtime().remoteAllocationId());
        requireStreamGrant(c);
        validateManifest(spec(c),permit.manifest());
        if(r.remote())validateRemoteManifest(r,permit.manifest());
        if(!digest(permit.manifest()).equals(permit.digest()) || verified.size()!=permit.manifest().outputs().size())throw new IllegalArgumentException("Invalid verification receipt");
        for(var output:permit.manifest().outputs()) {
            var matches=verified.stream().filter(v->v.port().equals(output.port())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Missing verified output");
            var artifact=matches.getFirst().artifact();var content=output.content(r.taskId(),r.attemptId());
            if(!artifact.objectKey().equals(content.objectKey()) || !artifact.versionId().equals(output.versionId()) ||
                    !artifact.sha256().equals(output.sha256()) || artifact.bytes()!=output.bytes() || !artifact.mediaType().equals(output.mediaType()))
                throw new IllegalArgumentException("Verification receipt differs from declared artifact");
        }
        var now=clock.instant();var result=new TaskResult(UUID.randomUUID(),r.taskId(),r.attemptId(),r.id(),r.epoch(),r.producerPodUid(),permit.digest(),now,verified,r.remoteAllocationId(),r.vd()?vdTasks.byRuntime(r.id()).orElseThrow().vdRuntimeId():null);
        runtimes.commit(result,now);
        if(!streams.releaseReady(r.runId()))runtimes.releaseReadyChildren(r.runId(),now);
        executions.reconcileRunState(r.runId(),now);
        if(autoDispatch)startRun(r.runId(),r.namespace());
        return new Creation<>(result,true);
    }
    @Transactional
    public void fail(UUID attemptId,long epoch,UUID podUid,String reason) {
        if(!Set.of("WORKLOAD_FAILED","TIMEOUT","INPUT_INVALID","OUTPUT_INVALID","STORAGE_FAILED","CANCELLED","RUNNER_FAILED").contains(reason))
            throw new IllegalArgumentException("Unknown Runner failure code");
        var c=lock(attemptId);var r=runtime(attemptId);
        if(r.remote())throw fenced();
        // A draining supervisor stops its children before its lease/deadline elapses. This is
        // runtime loss, not a user Task cancellation (which already fences producer authority).
        if(r.vd() && reason.equals("CANCELLED") && vdTasks.byRuntime(r.id())
                .map(a->vdRuntimes.runtime(a.vdRuntimeId()).orElseThrow().drainDeadline()!=null).orElse(false))reason="RUNTIME_LOST";
        if(c.attempt().state().equals("FAILED") && r.epoch()==epoch && Objects.equals(r.producerPodUid(),podUid) && Objects.equals(r.failureReason(),reason))return;
        producer(c,r,epoch,podUid);recordFailure(c,r,reason);
    }
    @Transactional
    public void confirmStopped(UUID attemptId) {
        var c=lock(attemptId);var r=runtime(attemptId);
        if(r.remote() || r.vd())throw fenced(); // Remote termination requires a validated terminal provider observation.
        if(!r.desiredState().equals("STOPPED"))throw fenced();
        runtimes.terminated(r.id(),clock.instant());executions.reconcileRunState(c.run().id(),clock.instant());
    }
    @Transactional
    public void observeFailure(UUID attemptId,String reason) {
        if(!Set.of("DISPATCH_TIMEOUT","RUNTIME_TIMEOUT","RUNTIME_LOST","JOB_FAILED","RESULT_MISSING","OWNERSHIP_CONFLICT","INPUT_INVALID","OUTPUT_INVALID","WORKLOAD_FAILED").contains(reason))
            throw new IllegalArgumentException("Unknown runtime observation failure");
        var c=lock(attemptId);var r=runtime(attemptId);
        if(!r.desiredState().equals("RUNNING") || !Set.of("DISPATCHING","RUNNING").contains(c.attempt().state()) || !c.task().state().equals("RUNNING"))return;
        recordFailure(c,r,reason);
    }
    private void recordFailure(Context c,RuntimeInstance r,String reason) {
        var now=clock.instant();boolean grouped=streamRecovery.manages(c.run(),c.task().id());
        boolean finalizing=grouped && streamRecovery.finalizing(c.attempt().id());
        boolean transferring=grouped && offloads.pendingStream(c.attempt().id());
        if(grouped && !finalizing && !transferring && streamRecovery.schedule(c.run(),c.task().id(),reason,now)){
            runtimes.stopForRun(r.runId(),now);executions.reconcileRunState(r.runId(),now);return;
        }
        runtimes.fail(r.id(),reason,now);offloads.failedAttempt(c.attempt().id(),now);
        var policy=c.run().retry();
        var first=executions.attempts(c.task().id()).stream().min(Comparator.comparingInt(TaskAttempt::number)).orElseThrow();
        var deadline=first.createdAt().plusSeconds(policy.maxElapsedSeconds());
        var availableAt=now.plusSeconds(policy.backoffSeconds());
        if(!transferring && (!grouped || finalizing) && policy.retryOn().contains(reason) && retryAttempts(c.task().id())<policy.maxAttempts() && availableAt.isBefore(deadline))
            executions.scheduleRetry(new TaskRetry(c.task().id(),c.attempt().id(),r.namespace(),availableAt,deadline),now);
        else failDescendants(c.run(),c.task(),now);
        if(finalizing)streamRecovery.fenceFinalizer(c.run(),c.task().id(),now);
        runtimes.stopForRun(r.runId(),now);executions.reconcileRunState(r.runId(),now);
    }
    @Transactional(readOnly=true)
    public List<UUID> dueRetries(String namespace) { return executions.dueRetries(namespace,clock.instant(),1000); }
    @Transactional
    public boolean retryTask(UUID taskId) {
        var initial=executions.task(taskId).orElseThrow();
        var run=executions.run(initial.runId(),true).orElseThrow();
        var task=executions.task(taskId).orElseThrow();var retry=executions.retry(taskId).orElse(null);
        if(retry==null)return false;
        if(!task.state().equals("RETRY_WAIT") || !run.state().equals("RUNNING")){executions.clearRetry(taskId);return false;}
        var now=clock.instant();
        if(!now.isBefore(retry.deadline())) {
            executions.failTask(taskId,now);failDescendants(run,task,now);
            runtimes.stopForRun(run.id(),now);executions.reconcileRunState(run.id(),now);return true;
        }
        if(streamRecovery.manages(run,taskId)){
            if(streamRecovery.finalizing(retry.failedAttemptId())){
                var next=streamRecovery.retryFinalizer(run,taskId,now);
                next.ifPresent(a->plan(a.id(),retry.namespace()));return next.isPresent();
            }
            var next=streamRecovery.retry(run,taskId,now);
            for(var attempt:next)plan(attempt.id(),retry.namespace());
            return !next.isEmpty();
        }
        if(now.isBefore(retry.availableAt()) || !runtimes.retryReady(taskId))return false;
        var previous=executions.attempt(retry.failedAttemptId()).orElseThrow();
        if(!previous.state().equals("FAILED") || retryAttempts(taskId)>=run.retry().maxAttempts())throw new IllegalStateException("Invalid pending retry");
        var next=executions.startRetry(taskId,now);plan(next.id(),retry.namespace());return true;
    }
    private long retryAttempts(UUID taskId){return executions.attempts(taskId).stream().filter(a->!a.cause().equals("OFFLOAD")).count();}
    private void failDescendants(WorkflowRun run,Task task,Instant now) {
        var descendants=streams.affected(run,task.key());
        for(var child:executions.tasks(run.id()))if(!child.id().equals(task.id()) && descendants.contains(child.key())){executions.cancelTask(child.id(),"SKIPPED","UPSTREAM_FAILED",now);offloads.cancelForTask(child.id(),now);}
    }
    private TaskResult replay(RuntimeInstance runtime,long epoch,UUID pod,UUID allocation,String digest) {
        var result=runtimes.result(runtime.taskId()).orElse(null);if(result==null)return null;
        if(!result.attemptId().equals(runtime.attemptId()) || result.epoch()!=epoch || !Objects.equals(result.producerPodUid(),pod) || !Objects.equals(result.remoteAllocationId(),allocation) || !result.manifestDigest().equals(digest))throw fenced();
        return result;
    }
    private Context lock(UUID attemptId) {
        var initial=executions.attempt(attemptId).orElseThrow(RuntimeLifecycleService::fenced);
        // Only the current producer's VD is locked. Planning children never acquires a peer VD.
        if(initial.vdId()!=null)vds.find(initial.vdId(),true).orElseThrow(RuntimeLifecycleService::fenced);
        return lockRun(attemptId);
    }
    private Context lockRun(UUID attemptId) {
        var initial=executions.attempt(attemptId).orElseThrow(RuntimeLifecycleService::fenced);
        var task=executions.task(initial.taskId()).orElseThrow();
        var run=executions.run(task.runId(),true).orElseThrow();
        return new Context(run,executions.task(initial.taskId()).orElseThrow(),executions.attempt(attemptId).orElseThrow());
    }
    private RuntimeInstance runtime(UUID attemptId) { return runtimes.byAttempt(attemptId).orElseThrow(RuntimeLifecycleService::fenced); }
    private void active(Context c,RuntimeInstance r,long epoch) {
        if(!offloads.canStart(c.attempt().id(),clock.instant()) || r.epoch()!=epoch || !r.desiredState().equals("RUNNING") || !Set.of("SUBMITTED","RUNNING").contains(r.observedState()) ||
                !Set.of("DISPATCHING","RUNNING").contains(c.attempt().state()) || !c.task().state().equals("RUNNING") || !c.run().state().equals("RUNNING") ||
                r.expiresAt()==null || !clock.instant().isBefore(r.expiresAt()))throw fenced();
    }
    private void producer(Context c,RuntimeInstance r,long epoch,UUID pod) {
        producer(c,r,epoch,pod,null);
    }
    private void producer(Context c,RuntimeInstance r,long epoch,UUID pod,UUID allocation) {
        active(c,r,epoch);
        if(r.vd())vdAuthority(r,pod);
        if(!c.attempt().state().equals("RUNNING") || r.remote()!=(allocation!=null) ||
            (r.remote() ? pod!=null || !r.remoteAllocationId().equals(allocation) : pod==null || !pod.equals(r.producerPodUid())))throw fenced();
    }
    private VDTaskAllocation vdAuthority(RuntimeInstance r,UUID podUid) {
        if(!r.vd())throw fenced();var a=vdTasks.byRuntime(r.id()).orElseThrow(RuntimeLifecycleService::fenced);
        var vr=vdRuntimes.runtime(a.vdRuntimeId()).orElseThrow(RuntimeLifecycleService::fenced);var now=clock.instant();
        if(!a.open() || !a.podUid().equals(podUid) || !a.vdId().equals(r.vdId()) || vr.terminal() ||
            !Set.of("RUNNING","DRAINING").contains(vr.desiredState()) || !Objects.equals(vr.sessionId(),a.sessionId()) ||
            vr.generation()!=a.generation() || !Objects.equals(vr.podUid(),a.podUid()) || vr.leaseUntil()==null || !now.isBefore(vr.leaseUntil()) ||
            vr.drainDeadline()!=null && !now.isBefore(vr.drainDeadline()))throw fenced();
        return a;
    }
    private static RemoteIdentity remoteIdentity(RuntimeInstance r){return new RemoteIdentity(r.remoteAllocationId(),r.runId(),r.taskId(),r.attemptId(),r.epoch());}
    private static void validateRemoteOutputs(ServiceExecutionSpec spec,List<RemoteFile> outputs) {
        if(!spec.outputs().keySet().equals(new HashSet<>(outputs.stream().map(RemoteFile::port).toList())))throw new IllegalArgumentException("Remote must produce all declared outputs");
        for(var output:outputs){var expected=spec.outputs().get(output.port());if(output.bytes()>expected.maxBytes() || !output.mediaType().equals(expected.mediaType()))throw new IllegalArgumentException("Remote output differs from SERVICE contract");}
    }
    private void validateRemoteManifest(RuntimeInstance r,ResultManifest manifest) {
        var a=remotes.find(r.remoteAllocationId()).orElseThrow();if(!a.providerState().equals("SUCCEEDED"))throw fenced();
        var observation=(Map<?,?>)JSON.decode(a.observationJson());var outputs=(List<?>)observation.get("outputs");
        for(var output:manifest.outputs()) {
            var matched=outputs.stream().map(v->(Map<?,?>)v).filter(f->output.port().equals(f.get("port"))).toList();
            if(matched.size()!=1)throw fenced();var f=matched.getFirst();
            if(!output.sha256().equals(f.get("sha256")) || output.bytes()!=new java.math.BigDecimal(f.get("bytes").toString()).longValueExact() || !output.mediaType().equals(f.get("mediaType")))throw fenced();
        }
    }
    private TaskDefinition definition(Context c) {
        return workflows.definitions(c.run().workflowVersionId()).stream().filter(d->d.id().equals(c.task().definitionId())).findFirst().orElseThrow();
    }
    private ServiceExecutionSpec spec(Context c) { return ServiceExecutionInput.parseSpec(profiles.find(definition(c).serviceProfileVersionId()).orElseThrow().specJson()); }
    private String parameters(Context c) {
        return mergeParameters(definition(c).parametersJson(),c.run().parametersJson());
    }
    private static String mergeParameters(String taskParameters,String runParameters) {
        var merged=new TreeMap<String,Object>();
        ((Map<?,?>)JSON.decode(taskParameters)).forEach((k,v)->merged.put((String)k,v));
        ((Map<?,?>)JSON.decode(runParameters)).forEach((k,v)->merged.put((String)k,v));
        return JSON.boundedCanonical(merged,65536);
    }
    private List<InputArtifact> inputs(Context c,ServiceExecutionSpec spec) {
        var tasks=new HashMap<String,Task>();executions.tasks(c.run().id()).forEach(t->tasks.put(t.key(),t));
        var result=new ArrayList<InputArtifact>();
        for(var edge:storedDag(workflows.version(c.run().workflowVersionId()).orElseThrow().dagJson()).dependencies()) {
            if(edge.mode()!=Dag.Mode.BATCH || !edge.toTask().equals(c.task().key()))continue;
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
    private static io.edgeai.app.exception.ControlPlaneException fenced() { return error(409,"PRODUCER_FENCED","현재 실행·epoch·producer 신원을 확인하세요."); }
}
