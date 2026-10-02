package io.edgeai.app.service;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.workflow.Dag;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

@Service
public class ExecutionService {
    private final ExecutionRepository repository;
    private final WorkflowRepository workflows;
    private final NodeRepository nodes;
    private final RuntimeRepository runtimes;
    private final Clock clock;
    private final OffloadRepository offloads;
    private final TelemetryRepository telemetry;
    private final RuntimeLifecycleService lifecycle;
    private final boolean runtimeEnabled;
    private final String runtimeNamespace;
    private final RemoteProvider remoteProvider;
    public ExecutionService(ExecutionRepository repository,WorkflowRepository workflows,NodeRepository nodes,RuntimeRepository runtimes,OffloadRepository offloads,TelemetryRepository telemetry,Clock clock,
            RuntimeLifecycleService lifecycle,@Value("${edgeai.runtime.enabled:false}") boolean runtimeEnabled,@Value("${edgeai.runtime.namespace:edgeai-runtimes}") String runtimeNamespace,RemoteProvider remoteProvider) {
        this.repository=repository;this.workflows=workflows;this.nodes=nodes;this.runtimes=runtimes;this.offloads=offloads;this.telemetry=telemetry;this.clock=clock;
        this.lifecycle=lifecycle;this.runtimeEnabled=runtimeEnabled;this.runtimeNamespace=runtimeNamespace;
        this.remoteProvider=remoteProvider;
    }
    @Transactional
    public Creation<WorkflowRun> create(String key,String body) {
        UUID idempotency=uuid(key);var input=runRequest(body);
        var retry=input.containsKey("retry")?retryPolicy(input.get("retry")):RetryPolicy.disabled();
        var offload=offloadPolicy(input.get("offload"));String offloadJson=offload==null?null:JSON.canonical(input.get("offload"));
        UUID versionId=uuid(input.get("workflowVersionId"));var parameters=parameters(input.get("parameters"));
        if(!(input.get("execution") instanceof Map<?,?> policy)) throw new IllegalArgumentException("Execution policy required");
        String mode=text(policy.get("mode"),8);UUID nodeId;String providerKey=null;
        if(mode.equals("AUTO")) { object(policy,"mode");nodeId=null; }
        else if(mode.equals("NODE")) { object(policy,"mode","nodeId");nodeId=uuid(policy.get("nodeId")); }
        else if(mode.equals("REMOTE")){object(policy,"mode","providerKey");providerKey=text(policy.get("providerKey"),63);nodeId=null;}
        else throw new IllegalArgumentException("Execution mode must be AUTO, NODE or REMOTE");
        var normalized=new TreeMap<String,Object>(Map.of("workflowVersionId",versionId.toString(),"parameters",parameters,
            "execution",nodeId==null?Map.of("mode",mode):Map.of("mode",mode,"nodeId",nodeId.toString())));
        if(providerKey!=null)normalized.put("execution",Map.of("mode",mode,"providerKey",providerKey));
        if(!retry.equals(RetryPolicy.disabled()))normalized.put("retry",document(retry));
        if(offload!=null)normalized.put("offload",JSON.decode(offloadJson));
        String digest=JSON.digest("edgeai-run-create-v1",normalized);
        var existing=repository.byIdempotencyKey(idempotency);
        if(existing.isPresent()) return replay(existing.get(),digest);
        if(providerKey!=null && !runtimeEnabled)throw error(503,"RUNTIME_DISABLED","Remote 실행은 실행 worker와 저장소 설정을 먼저 활성화해야 합니다.");
        var remoteTarget=providerKey==null?null:remoteProvider.select(providerKey);
        if(remoteTarget!=null && offload!=null)throw error(409,"REMOTE_TELEMETRY_UNSUPPORTED","현재 자동 전환 정책은 Kubernetes의 실행 측정을 사용합니다. Remote는 명시적 전환을 사용하세요.");
        var version=workflows.version(versionId).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","발행된 DAG 버전이 없습니다."));
        var dag=storedDag(version.dagJson());
        if(dag.dependencies().stream().anyMatch(edge->edge.mode()==Dag.Mode.STREAM))
            throw error(501,"STREAM_NOT_IMPLEMENTED","STREAM 실행은 M7에서 구현합니다. 현재는 BATCH DAG 실행 요청을 사용하세요.");
        if(nodeId!=null && nodes.find(nodeId).isEmpty()) throw error(404,"NODE_NOT_FOUND","실행 정책에서 참조할 노드를 찾을 수 없습니다.");
        if(runtimeEnabled)lifecycle.validateRequest(versionId,JSON.canonical(parameters));
        if(offload!=null)lifecycle.validateAutomaticOffload(versionId);
        var now=clock.instant();var run=new WorkflowRun(UUID.randomUUID(),versionId,idempotency,digest,mode,nodeId,JSON.canonical(parameters),retry,offloadJson,"PENDING",now,now,remoteTarget);
        if(!repository.create(run)) return replay(repository.byIdempotencyKey(idempotency).orElseThrow(),digest);
        repository.initialize(run,workflows.definitions(versionId),dag.roots());
        if(runtimeEnabled)lifecycle.startRun(run.id(),runtimeNamespace);
        return new Creation<>(repository.run(run.id(),false).orElseThrow(),true);
    }
    private Creation<WorkflowRun> replay(WorkflowRun run,String digest) {
        if(!run.requestDigest().equals(digest)) throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 실행 입력이 있습니다.");
        return new Creation<>(run,false);
    }
    public List<WorkflowRun> list(int limit,int offset) { WorkflowService.page(limit,offset);return repository.runs(limit+1,offset); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RunSnapshot detail(UUID id) { return new RunSnapshot(run(id,false),repository.tasks(id)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TaskSnapshot taskDetail(UUID id) { return snapshot(task(id)); }
    private TaskSnapshot snapshot(Task task) {
        var attempts=repository.attempts(task.id());
        var latest=attempts.isEmpty()?null:telemetry.recent(attempts.getFirst().id(),1).stream().findFirst().orElse(null);
        return new TaskSnapshot(task,attempts,offloads.forTask(task.id()),latest);
    }
    @Transactional
    public WorkflowRun cancelRun(UUID id,String body) {
        parse(body);var run=run(id,true);
        if(Set.of("SUCCEEDED","FAILED").contains(run.state())) throw error(409,"CANNOT_CANCEL","완료된 실행 결과는 취소로 덮어쓸 수 없습니다.");
        if(run.state().equals("CANCELLED")) return run;
        var now=clock.instant();
        for(var task:repository.tasks(id)) {repository.cancelTask(task.id(),"CANCELLED","RUN_CANCELLED",now);offloads.cancelForTask(task.id(),now);}
        runtimes.stopForRun(id,now);
        repository.reconcileRunState(id,now);return run(id,false);
    }
    @Transactional
    public TaskSnapshot cancelTask(UUID id,String body) {
        parse(body);var initial=task(id);var run=run(initial.runId(),true);var task=task(id);
        if(Set.of("SUCCEEDED","FAILED").contains(task.state())) throw error(409,"CANNOT_CANCEL","완료된 작업 결과는 취소로 덮어쓸 수 없습니다.");
        if(Set.of("CANCELLED","SKIPPED").contains(task.state())) return snapshot(task);
        var dag=storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson());var descendants=dag.descendants(task.key());
        var now=clock.instant();repository.cancelTask(id,"CANCELLED","TASK_CANCELLED",now);offloads.cancelForTask(id,now);
        for(var child:repository.tasks(run.id())) if(descendants.contains(child.key())) {repository.cancelTask(child.id(),"SKIPPED","UPSTREAM_CANCELLED",now);offloads.cancelForTask(child.id(),now);}
        runtimes.stopForRun(run.id(),now);
        repository.reconcileRunState(run.id(),now);return snapshot(task(id));
    }
    private WorkflowRun run(UUID id,boolean lock) { return repository.run(id,lock).orElseThrow(()->error(404,"RUN_NOT_FOUND","실행 요청을 찾을 수 없습니다.")); }
    private Task task(UUID id) { return repository.task(id).orElseThrow(()->error(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.")); }
}
