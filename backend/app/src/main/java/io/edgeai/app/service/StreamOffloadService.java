package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.stream.RouteGeneration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.service.WorkflowService.error;

/** Same Run lock as completion/retry/cancel. No Device lock or external I/O after that lock. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class StreamOffloadService {
    private final StreamRunService streams;private final ExecutionRepository executions;private final RuntimeRepository runtimes;
    private final DataRouteRepository routes;private final StreamCheckpointRepository checkpoints;
    private final StreamExecutionRepository completions;private final OffloadRepository operations;
    public StreamOffloadService(StreamRunService streams,ExecutionRepository executions,RuntimeRepository runtimes,
            DataRouteRepository routes,StreamCheckpointRepository checkpoints,StreamExecutionRepository completions,OffloadRepository operations){
        this.streams=streams;this.executions=executions;this.runtimes=runtimes;this.routes=routes;
        this.checkpoints=checkpoints;this.completions=completions;this.operations=operations;
    }
    public List<OffloadMember> plan(WorkflowRun run,UUID selected,UUID target,Instant now){
        if(target==null)throw error(409,"OFFLOAD_RECOVERY_UNSUPPORTED","스트리밍 체크포인트 전환은 공개 STREAM의 NODE 대상으로 요청하세요.");
        return plan(run,selected,target,List.of(),now);
    }
    public record AutomaticPlan(List<OffloadMember> members,Instant eligibleSince){
        public AutomaticPlan{members=List.copyOf(members);}
    }
    /** An incomplete group/checkpoint is an ordinary deferred decision, with no writes or fencing. */
    public Optional<AutomaticPlan> automaticPlan(WorkflowRun run,UUID selected,List<String> excluded,OffloadPolicy policy,Instant now){
        if(excluded.isEmpty())throw new IllegalArgumentException("Automatic stream transfer requires excluded nodes");
        try{
            var members=plan(run,selected,null,excluded,now);Instant eligible=Instant.MIN;
            for(var member:members){
                var a=executions.attempt(member.sourceAttemptId()).orElseThrow();
                var history=operations.forTask(member.taskId());
                if(history.stream().filter(o->!o.trigger().equals("MANUAL")).count()>=policy.maxTransfers())return Optional.empty();
                var warmup=a.updatedAt().plusSeconds(policy.minRunningSeconds());if(warmup.isAfter(eligible))eligible=warmup;
                for(var operation:history){var cooldown=operation.updatedAt().plusSeconds(policy.cooldownSeconds());if(cooldown.isAfter(eligible))eligible=cooldown;}
            }
            return Optional.of(new AutomaticPlan(members,eligible));
        }catch(ControlPlaneException unavailable){return Optional.empty();}
    }
    private List<OffloadMember> plan(WorkflowRun run,UUID selected,UUID target,List<String> excluded,Instant now){
        if(!streams.managed(run.id()))throw error(409,"OFFLOAD_RECOVERY_UNSUPPORTED","공개 STREAM 실행의 체크포인트 전환만 지원합니다.");
        var plan=streams.plan(run);var ids=plan.componentTasks(selected);var connected=plan.componentRoutes(selected);
        if(connected.isEmpty())throw error(409,"OFFLOAD_RECOVERY_UNSUPPORTED","연결된 STREAM 그룹이 필요합니다.");
        var result=new ArrayList<OffloadMember>();var current=new HashMap<UUID,TaskAttempt>();
        for(var task:executions.tasks(run.id()))if(ids.contains(task.id())){
            var a=executions.attempts(task.id()).getFirst();var r=runtimes.byAttempt(a.id()).orElseThrow();
            if(!task.state().equals("RUNNING") || !a.state().equals("RUNNING") || r.remote() || r.vd()
                || !r.desiredState().equals("RUNNING") || !r.observedState().equals("RUNNING") || r.producerPodUid()==null
                || r.expiresAt()==null || !now.isBefore(r.expiresAt()) || runtimes.result(task.id()).isPresent()
                || completions.granted(a.id()).isPresent())
                throw error(409,"OFFLOAD_SOURCE_CHANGED","완료 허가 전이며 전체 STREAM 그룹이 실행 중이어야 합니다.");
            var history=operations.forTask(task.id());
            if(history.size()>=8 || history.stream().anyMatch(o->Set.of("DRAINING","STARTING","CANCELLING").contains(o.state())))
                throw error(409,"OFFLOAD_LIMIT","연결된 모든 작업의 전환 한도와 진행 중인 요청을 확인하세요.");
            var cp=checkpoints.latest(task.id()).orElseThrow(()->error(409,"STREAM_CHECKPOINT_MISSING","연결된 모든 작업의 외부 체크포인트가 확정된 뒤 전환하세요."));
            if(!cp.attemptId().equals(a.id()) || cp.epoch()!=a.epoch() || !cp.producerPodUid().equals(r.producerPodUid()))
                throw error(409,"STREAM_CHECKPOINT_STALE","현재 실행이 확정한 체크포인트를 기다린 뒤 전환하세요.");
            var generations=new HashSet<UUID>();
            for(var route:plan.taskRoutes(task.id())){
                var g=routes.open(route.id()).orElseThrow(()->error(409,"OFFLOAD_SOURCE_CHANGED","현재 STREAM 경로가 활성 상태여야 합니다."));
                if(!g.state().equals("ACTIVE") || g.fencedAt()!=null || !now.isBefore(g.leaseUntil()))
                    throw error(409,"OFFLOAD_SOURCE_CHANGED","현재 STREAM 경로가 활성 상태여야 합니다.");
                generations.add(g.id());
            }
            if(!generations.equals(new HashSet<>(cp.request().generationIds())))
                throw error(409,"STREAM_CHECKPOINT_STALE","현재 경로 세대의 체크포인트를 기다린 뒤 전환하세요.");
            result.add(new OffloadMember(task.id(),a.id(),null,cp.id(),task.id().equals(selected)?target:a.nodeId(),
                task.id().equals(selected)?excluded:a.excludedNodeNames()));current.put(task.id(),a);
        }
        if(result.stream().map(m->runtimes.byAttempt(m.sourceAttemptId()).orElseThrow().namespace()).distinct().count()!=1)
            throw error(409,"OFFLOAD_SOURCE_CHANGED","그룹 실행 namespace가 다릅니다.");
        for(var route:connected){var g=routes.open(route.id()).orElseThrow();var consumer=current.get(route.consumerTaskId());
            if(!g.consumer().equals(new RouteGeneration.Actor(consumer.id(),consumer.epoch()))
                || !route.deviceSource() && !g.producer().equals(new RouteGeneration.Actor(current.get(route.sourceTaskId()).id(),current.get(route.sourceTaskId()).epoch())))
                throw error(409,"OFFLOAD_SOURCE_CHANGED","현재 실행과 STREAM 경로 신원이 다릅니다.");
        }
        result.sort(Comparator.comparing(m->m.taskId().toString()));
        return List.copyOf(result);
    }
    public void fence(OffloadOperation operation,Instant now){
        fenceRoutes(operation,now);
        for(var member:operation.members())runtimes.offload(runtimes.byAttempt(member.sourceAttemptId()).orElseThrow().id(),now);
    }
    public void fenceRoutes(OffloadOperation operation,Instant now){
        var run=executions.run(operation.runId(),false).orElseThrow();
        for(var route:streams.plan(run).componentRoutes(operation.taskId()))routes.open(route.id()).filter(g->g.fencedAt()==null)
            .ifPresent(g->routes.fence(g.id(),"REPLACED",now));
    }
    public boolean stopped(OffloadOperation operation){
        return operation.members().stream().allMatch(m->runtimes.retryReady(m.taskId()))
            && streams.plan(executions.run(operation.runId(),false).orElseThrow()).componentRoutes(operation.taskId()).stream()
                .noneMatch(r->routes.open(r.id()).isPresent());
    }
    public boolean ready(OffloadOperation operation){
        return operation.members().stream().allMatch(m->executions.task(m.taskId()).orElseThrow().state().equals("OFFLOADING")
            && executions.attempts(m.taskId()).getFirst().id().equals(m.sourceAttemptId())) && stopped(operation);
    }
}
