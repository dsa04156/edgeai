package io.edgeai.app.service;

import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

/** Explicit running transfer; state transitions share the Run lock with cancel/retry/result. */
@Service
public class OffloadService {
    private final OffloadRepository operations;
    private final ExecutionRepository executions;
    private final RuntimeRepository runtimes;
    private final WorkflowRepository workflows;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final RuntimeLifecycleService lifecycle;
    private final Clock clock;
    private final TelemetryRepository telemetry;
    private final RemoteProvider remoteProvider;
    public OffloadService(OffloadRepository operations,ExecutionRepository executions,RuntimeRepository runtimes,WorkflowRepository workflows,
            ProfileRepository profiles,NodeRepository nodes,RuntimeLifecycleService lifecycle,Clock clock,TelemetryRepository telemetry,RemoteProvider remoteProvider) {
        this.operations=operations;this.executions=executions;this.runtimes=runtimes;this.workflows=workflows;this.profiles=profiles;this.nodes=nodes;this.lifecycle=lifecycle;this.clock=clock;this.telemetry=telemetry;
        this.remoteProvider=remoteProvider;
    }
    @Transactional
    public Creation<OffloadOperation> request(UUID taskId,String key,String body) {
        var raw=parameters(JSON.parse(body,65536));boolean remote=raw.containsKey("targetProviderKey");
        var input=object(raw,"sourceAttemptId",remote?"targetProviderKey":"targetNodeId","drainTimeoutSeconds","startTimeoutSeconds");
        UUID idempotency=uuid(key),source=uuid(input.get("sourceAttemptId")),target=remote?null:uuid(input.get("targetNodeId"));
        String providerKey=remote?text(input.get("targetProviderKey"),63):null;
        int drain=seconds(input.get("drainTimeoutSeconds")),start=seconds(input.get("startTimeoutSeconds"));
        String digest=JSON.digest("edgeai-task-offload-v1",Map.of("taskId",taskId.toString(),"sourceAttemptId",source.toString(),remote?"targetProviderKey":"targetNodeId",remote?providerKey:target.toString(),"drainTimeoutSeconds",drain,"startTimeoutSeconds",start));
        var previous=operations.byKey(idempotency);if(previous.isPresent())return replay(previous.get(),digest);
        var remoteTarget=remote?remoteProvider.select(providerKey):null;
        var initial=executions.task(taskId).orElseThrow(()->error(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다."));
        var run=executions.run(initial.runId(),true).orElseThrow();var task=executions.task(taskId).orElseThrow();
        previous=operations.byKey(idempotency);if(previous.isPresent())return replay(previous.get(),digest);
        var attempt=executions.attempt(source).orElseThrow(()->error(409,"OFFLOAD_SOURCE_CHANGED","현재 실행 중인 Attempt를 선택하세요."));
        var runtime=runtimes.byAttempt(source).orElse(null);
        if(!attempt.taskId().equals(taskId) || !attempt.state().equals("RUNNING") || !task.state().equals("RUNNING") || !run.state().equals("RUNNING") ||
                runtime==null || !runtime.desiredState().equals("RUNNING") || !runtime.observedState().equals("RUNNING") || (!runtime.remote() && runtime.producerPodUid()==null) ||
                runtime.expiresAt()==null || !clock.instant().isBefore(runtime.expiresAt()))
            throw error(409,"OFFLOAD_SOURCE_CHANGED","실제 producer가 실행 중인 현재 Attempt만 전환할 수 있습니다.");
        var history=operations.forTask(taskId);
        if(history.size()>=8 || history.stream().anyMatch(o->Set.of("DRAINING","STARTING","CANCELLING").contains(o.state())))
            throw error(409,"OFFLOAD_LIMIT","진행 중인 전환을 확인하세요. 작업별 전환 요청 한도는 8회입니다.");
        var definition=workflows.definitions(run.workflowVersionId()).stream().filter(d->d.id().equals(task.definitionId())).findFirst().orElseThrow();
        var spec=ServiceExecutionInput.parseSpec(profiles.find(definition.serviceProfileVersionId()).orElseThrow().specJson());
        if(!spec.recoveryMode().equals("RESTART"))throw error(409,"OFFLOAD_RECOVERY_UNSUPPORTED","SERVICE Profile에 recovery.mode=RESTART를 선언한 작업만 재시작 전환할 수 있습니다.");
        if(remoteTarget!=null) {
            if(remoteTarget.equals(attempt.remoteTarget()))throw error(409,"OFFLOAD_TARGET_INVALID","현재 제공자와 다른 실행 위치를 선택하세요.");
        } else {
            var node=nodes.find(target).orElseThrow(()->error(404,"NODE_NOT_FOUND","대상 노드를 찾을 수 없습니다."));
            var labels=parameters(JSON.decode(node.labelsJson()));
            if(target.equals(runtime.nodeUid()) || !node.status(clock.instant()).equals("READY") || !node.operatingSystem().equals("linux") ||
                !spec.architectures().contains(node.architecture()) || spec.nodeSelector().entrySet().stream().anyMatch(e->!e.getValue().equals(labels.get(e.getKey()))))
            throw error(409,"OFFLOAD_TARGET_INVALID","현재 노드와 다르고 SERVICE 요구조건에 맞는 최근 READY 노드를 선택하세요.");
        }
        var now=clock.instant();var operation=new OffloadOperation(UUID.randomUUID(),taskId,run.id(),source,null,target,idempotency,digest,runtime.namespace(),"DRAINING",null,now.plusSeconds(drain),start,null,now,now,"MANUAL",List.of(),null,remoteTarget);
        if(!operations.create(operation))return replay(operations.byKey(idempotency).orElseThrow(),digest);
        runtimes.offload(runtime.id(),now);return new Creation<>(operation,true);
    }
    @Transactional(readOnly=true)
    public OffloadOperation find(UUID id){return operations.find(id).orElseThrow(()->error(404,"OPERATION_NOT_FOUND","작업 상태를 찾을 수 없습니다."));}
    @Transactional(readOnly=true)
    public List<UUID> active(String namespace){return operations.active(namespace,1000);}
    @Transactional
    public void advance(UUID id) {
        var initial=find(id);executions.run(initial.runId(),true).orElseThrow();var operation=find(id);
        if(!Set.of("DRAINING","STARTING","CANCELLING").contains(operation.state()))return;
        var task=executions.task(operation.taskId()).orElseThrow();var now=clock.instant();
        if(Set.of("CANCELLING","CANCELLED","SKIPPED").contains(task.state()) || operation.state().equals("CANCELLING")) {
            operations.cancelForTask(task.id(),now);
            if(runtimes.retryReady(task.id()))operations.terminal(id,"CANCELLED",null,now);
            return;
        }
        if(task.state().equals("FAILED")){operations.terminal(id,"FAILED","TARGET_FAILED",now);return;}
        if(operation.state().equals("DRAINING")) {
            if(!now.isBefore(operation.drainDeadline())){fail(operation,"SOURCE_DRAIN_TIMEOUT",now);return;}
            if(!task.state().equals("OFFLOADING") || !runtimes.retryReady(task.id()))return;
            var attempt=operation.remoteTarget()==null?executions.startOffload(task.id(),operation.targetNodeId(),operation.excludedNodeNames(),now):executions.startRemoteOffload(task.id(),operation.remoteTarget(),now);
            lifecycle.plan(attempt.id(),operation.namespace());operations.starting(id,attempt.id(),now.plusSeconds(operation.startTimeoutSeconds()),now);
        } else if(operation.state().equals("STARTING") && !now.isBefore(operation.startDeadline()))fail(operation,"TARGET_START_TIMEOUT",now);
    }
    @Transactional(readOnly=true)
    public List<UUID> automaticCandidates(String namespace){return operations.automaticCandidates(namespace,10000);}
    @Transactional
    public Optional<OffloadOperation> evaluate(UUID taskId,String namespace) {
        var initial=executions.task(taskId).orElseThrow();var run=executions.run(initial.runId(),true).orElseThrow();
        var task=executions.task(taskId).orElseThrow();var now=clock.instant();
        if(run.offloadPolicyJson()==null || !run.state().equals("RUNNING") || !task.state().equals("RUNNING"))return Optional.empty();
        var policy=offloadPolicy(JSON.decode(run.offloadPolicyJson()));var attempt=executions.attempts(taskId).getFirst();
        var runtime=runtimes.byAttempt(attempt.id()).orElse(null);
        if(!attempt.state().equals("RUNNING") || runtime==null || !runtime.namespace().equals(namespace) || !runtime.desiredState().equals("RUNNING") ||
            !runtime.observedState().equals("RUNNING") || runtime.producerPodUid()==null || runtime.expiresAt()==null || !now.isBefore(runtime.expiresAt()))return Optional.empty();
        var history=operations.forTask(taskId);
        if(history.size()>=8 || history.stream().filter(o->!o.trigger().equals("MANUAL")).count()>=policy.maxTransfers() ||
            history.stream().anyMatch(o->Set.of("DRAINING","STARTING","CANCELLING").contains(o.state())))return Optional.empty();
        var eligible=attempt.updatedAt().plusSeconds(policy.minRunningSeconds());
        for(var operation:history)if(operation.updatedAt().plusSeconds(policy.cooldownSeconds()).isAfter(eligible))eligible=operation.updatedAt().plusSeconds(policy.cooldownSeconds());
        var samples=telemetry.recent(attempt.id(),policy.consecutiveSamples());var trigger=policy.trigger(samples,now,eligible);
        if(trigger.isEmpty())return Optional.empty();
        var definition=workflows.definitions(run.workflowVersionId()).stream().filter(d->d.id().equals(task.definitionId())).findFirst().orElseThrow();
        var spec=ServiceExecutionInput.parseSpec(profiles.find(definition.serviceProfileVersionId()).orElseThrow().specJson());
        if(!spec.recoveryMode().equals("RESTART"))return Optional.empty();
        var excluded=new TreeSet<String>();
        for(var previous:executions.attempts(taskId))runtimes.byAttempt(previous.id()).ifPresent(r->{if(r.nodeName()!=null)excluded.add(r.nodeName());});
        if(excluded.isEmpty() || excluded.size()>16)return Optional.empty();
        boolean alternative=false;
        for(int offset=0;!alternative;offset+=1000) {
            var page=nodes.list(1000,offset);
            alternative=page.stream().anyMatch(n->!excluded.contains(n.name()) && n.status(now).equals("READY") && n.operatingSystem().equals("linux") &&
                spec.architectures().contains(n.architecture()) && spec.nodeSelector().entrySet().stream().allMatch(e->e.getValue().equals(parameters(JSON.decode(n.labelsJson())).get(e.getKey()))));
            if(page.size()<1000)break;
        }
        if(!alternative)return Optional.empty();
        var evidence=new TreeMap<String,Object>();evidence.put("policy",JSON.decode(run.offloadPolicyJson()));evidence.put("evaluatedAt",now.toString());
        evidence.put("eligibleSince",eligible.toString());evidence.put("samples",samples.stream().map(OffloadService::measurement).toList());
        var id=UUID.randomUUID();var operation=new OffloadOperation(id,taskId,run.id(),attempt.id(),null,null,id,
            JSON.digest("edgeai-automatic-offload-v1",Map.of("sourceAttemptId",attempt.id().toString(),"evidence",evidence)),namespace,"DRAINING",null,
            now.plusSeconds(policy.drainTimeoutSeconds()),policy.startTimeoutSeconds(),null,now,now,trigger.get(),List.copyOf(excluded),JSON.canonical(evidence));
        if(!operations.create(operation))throw new IllegalStateException("Automatic operation identity collision");
        runtimes.offload(runtime.id(),now);return Optional.of(operation);
    }
    private static Map<String,Object> measurement(io.edgeai.domain.runtime.RuntimeTelemetry s) {
        var m=new TreeMap<String,Object>();m.put("attemptId",s.attemptId().toString());m.put("sequence",s.sequence());m.put("observedAt",s.observedAt().toString());
        m.put("receivedAt",s.receivedAt().toString());m.put("intervalMillis",s.intervalMillis());m.put("cpuUsageMicros",s.cpuUsageMicros());m.put("cpuLimitMillicores",s.cpuLimitMillicores());
        m.put("resourceSource",s.cpuUsageMicros()==null && s.memoryBytes()==null?null:"CGROUP_V2");m.put("latencySource",s.latencyMicros()==null?null:"WORKLOAD");
        m.put("memoryBytes",s.memoryBytes());m.put("memoryLimitBytes",s.memoryLimitBytes());m.put("latencyMicros",s.latencyMicros());
        m.put("latencyObservedAt",s.latencyObservedAt()==null?null:s.latencyObservedAt().toString());return m;
    }
    private void fail(OffloadOperation operation,String reason,Instant now) {
        if(operation.targetAttemptId()!=null)runtimes.byAttempt(operation.targetAttemptId()).ifPresent(r->runtimes.fail(r.id(),reason,now));
        executions.failTask(operation.taskId(),now);
        var task=executions.task(operation.taskId()).orElseThrow();var run=executions.run(task.runId(),false).orElseThrow();
        var descendants=storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson()).descendants(task.key());
        for(var child:executions.tasks(run.id()))if(descendants.contains(child.key())){executions.cancelTask(child.id(),"SKIPPED","UPSTREAM_FAILED",now);operations.cancelForTask(child.id(),now);}
        runtimes.stopForRun(run.id(),now);executions.reconcileRunState(run.id(),now);operations.terminal(operation.id(),"FAILED",reason,now);
    }
    private static int seconds(Object value) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Integer timeout required");
        try {int number=new BigDecimal(value.toString()).intValueExact();if(number<1 || number>600)throw new IllegalArgumentException("Timeout must be 1..600");return number;}
        catch(ArithmeticException e){throw new IllegalArgumentException("Integer timeout required");}
    }
    private static Creation<OffloadOperation> replay(OffloadOperation operation,String digest) {
        if(!operation.requestDigest().equals(digest))throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 전환 입력이 있습니다.");
        return new Creation<>(operation,false);
    }
}
