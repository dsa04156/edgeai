package io.edgeai.app.service;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.workflow.Dag;
import java.time.Clock;
import java.util.*;
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
    public ExecutionService(ExecutionRepository repository,WorkflowRepository workflows,NodeRepository nodes,RuntimeRepository runtimes,Clock clock) {
        this.repository=repository;this.workflows=workflows;this.nodes=nodes;this.runtimes=runtimes;this.clock=clock;
    }
    @Transactional
    public Creation<WorkflowRun> create(String key,String body) {
        UUID idempotency=uuid(key);var input=parse(body,"workflowVersionId","execution","parameters");
        UUID versionId=uuid(input.get("workflowVersionId"));var parameters=parameters(input.get("parameters"));
        if(!(input.get("execution") instanceof Map<?,?> policy)) throw new IllegalArgumentException("Execution policy required");
        String mode=text(policy.get("mode"),8);UUID nodeId;
        if(mode.equals("AUTO")) { object(policy,"mode");nodeId=null; }
        else if(mode.equals("NODE")) { object(policy,"mode","nodeId");nodeId=uuid(policy.get("nodeId")); }
        else throw new IllegalArgumentException("Execution mode must be AUTO or NODE");
        var normalized=Map.of("workflowVersionId",versionId.toString(),"parameters",parameters,
            "execution",nodeId==null?Map.of("mode",mode):Map.of("mode",mode,"nodeId",nodeId.toString()));
        String digest=JSON.digest("edgeai-run-create-v1",normalized);
        var existing=repository.byIdempotencyKey(idempotency);
        if(existing.isPresent()) return replay(existing.get(),digest);
        var version=workflows.version(versionId).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","발행된 DAG 버전이 없습니다."));
        var dag=storedDag(version.dagJson());
        if(dag.dependencies().stream().anyMatch(edge->edge.mode()==Dag.Mode.STREAM))
            throw error(501,"STREAM_NOT_IMPLEMENTED","STREAM 실행은 M7에서 구현합니다. 현재는 BATCH DAG 실행 요청을 사용하세요.");
        if(nodeId!=null && nodes.find(nodeId).isEmpty()) throw error(404,"NODE_NOT_FOUND","실행 정책에서 참조할 노드를 찾을 수 없습니다.");
        var now=clock.instant();var run=new WorkflowRun(UUID.randomUUID(),versionId,idempotency,digest,mode,nodeId,JSON.canonical(parameters),"PENDING",now,now);
        if(!repository.create(run)) return replay(repository.byIdempotencyKey(idempotency).orElseThrow(),digest);
        repository.initialize(run,workflows.definitions(versionId),dag.roots());
        return new Creation<>(run,true);
    }
    private Creation<WorkflowRun> replay(WorkflowRun run,String digest) {
        if(!run.requestDigest().equals(digest)) throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 실행 입력이 있습니다.");
        return new Creation<>(run,false);
    }
    public List<WorkflowRun> list(int limit,int offset) { WorkflowService.page(limit,offset);return repository.runs(limit+1,offset); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RunSnapshot detail(UUID id) { return new RunSnapshot(run(id,false),repository.tasks(id)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TaskSnapshot taskDetail(UUID id) { return new TaskSnapshot(task(id),repository.attempts(id)); }
    @Transactional
    public WorkflowRun cancelRun(UUID id,String body) {
        parse(body);var run=run(id,true);
        if(Set.of("SUCCEEDED","FAILED").contains(run.state())) throw error(409,"CANNOT_CANCEL","완료된 실행 결과는 취소로 덮어쓸 수 없습니다.");
        if(run.state().equals("CANCELLED")) return run;
        var now=clock.instant();
        for(var task:repository.tasks(id)) repository.cancelTask(task.id(),"CANCELLED","RUN_CANCELLED",now);
        runtimes.stopForRun(id,now);
        repository.reconcileRunState(id,now);return run(id,false);
    }
    @Transactional
    public TaskSnapshot cancelTask(UUID id,String body) {
        parse(body);var initial=task(id);var run=run(initial.runId(),true);var task=task(id);
        if(Set.of("SUCCEEDED","FAILED").contains(task.state())) throw error(409,"CANNOT_CANCEL","완료된 작업 결과는 취소로 덮어쓸 수 없습니다.");
        if(Set.of("CANCELLED","SKIPPED").contains(task.state())) return new TaskSnapshot(task,repository.attempts(id));
        var dag=storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson());var descendants=dag.descendants(task.key());
        var now=clock.instant();repository.cancelTask(id,"CANCELLED","TASK_CANCELLED",now);
        for(var child:repository.tasks(run.id())) if(descendants.contains(child.key())) repository.cancelTask(child.id(),"SKIPPED","UPSTREAM_CANCELLED",now);
        runtimes.stopForRun(run.id(),now);
        repository.reconcileRunState(run.id(),now);return new TaskSnapshot(task(id),repository.attempts(id));
    }
    private WorkflowRun run(UUID id,boolean lock) { return repository.run(id,lock).orElseThrow(()->error(404,"RUN_NOT_FOUND","실행 요청을 찾을 수 없습니다.")); }
    private Task task(UUID id) { return repository.task(id).orElseThrow(()->error(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.")); }
}
