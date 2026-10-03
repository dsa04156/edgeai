package io.edgeai.app.service;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeInstance;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Run-lock serialized recovery of an unfinished stream component. No external I/O. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class StreamRecoveryService {
    private final StreamRunService streams;
    private final ExecutionRepository executions;
    private final RuntimeRepository runtimes;
    private final DataRouteRepository routes;
    private final StreamExecutionRepository completions;
    public StreamRecoveryService(StreamRunService streams,ExecutionRepository executions,RuntimeRepository runtimes,
            DataRouteRepository routes,StreamExecutionRepository completions){
        this.streams=streams;this.executions=executions;this.runtimes=runtimes;this.routes=routes;this.completions=completions;
    }
    public boolean manages(WorkflowRun run,UUID task){
        return streams.managed(run.id()) && !streams.plan(run).componentRoutes(task).isEmpty();
    }
    /** Atomically fence all peers and persist one shared retry window before returning. */
    public boolean schedule(WorkflowRun run,UUID failedTask,String reason,Instant now){
        var policy=run.retry();if(!run.state().equals("RUNNING") || !policy.retryOn().contains(reason))return false;
        var plan=streams.plan(run);var ids=plan.componentTasks(failedTask);
        var members=executions.tasks(run.id()).stream().filter(t->ids.contains(t.id())).toList();
        var previous=new ArrayList<TaskAttempt>();var producers=new ArrayList<RuntimeInstance>();Instant deadline=null;
        for(var task:members){
            var history=executions.attempts(task.id());
            if(!task.state().equals("RUNNING") || history.isEmpty() || retryAttempts(history)>=policy.maxAttempts())return false;
            var attempt=history.getFirst();var runtime=runtimes.byAttempt(attempt.id()).orElse(null);
            // Finalization recovery has a separate durable grant boundary. Never restart a sealed computation.
            if(completions.task(attempt.id()).filter(c->c.grantedAt()!=null).isPresent() || runtimes.result(task.id()).isPresent()
                || runtime==null || runtime.remote() || runtime.vd() || !runtime.desiredState().equals("RUNNING")
                || !Set.of("DISPATCHING","RUNNING").contains(attempt.state()))return false;
            var first=history.stream().min(Comparator.comparingInt(TaskAttempt::number)).orElseThrow();
            var until=first.createdAt().plusSeconds(policy.maxElapsedSeconds());
            if(deadline==null || until.isBefore(deadline))deadline=until;
            previous.add(attempt);producers.add(runtime);
        }
        var available=now.plusSeconds(policy.backoffSeconds());
        if(deadline==null || !available.isBefore(deadline) || producers.stream().map(RuntimeInstance::namespace).distinct().count()!=1)return false;
        // Revocation only takes Run/Route locks. It never acquires Device locks after the Run lock.
        for(var route:plan.componentRoutes(failedTask))routes.open(route.id()).filter(g->g.fencedAt()==null)
            .ifPresent(g->routes.fence(g.id(),"REPLACED",now));
        for(int i=0;i<previous.size();i++){
            var a=previous.get(i);var r=producers.get(i);
            runtimes.fail(r.id(),a.taskId().equals(failedTask)?reason:"STREAM_GROUP_RESTART",now);
            executions.scheduleRetry(new TaskRetry(a.taskId(),a.id(),r.namespace(),available,deadline),now);
        }
        return true;
    }
    /** All previous physical producers and broker grants must be gone before any new Attempt is created. */
    public List<TaskAttempt> retry(WorkflowRun run,UUID taskId,Instant now){
        var plan=streams.plan(run);var ids=plan.componentTasks(taskId);var members=executions.tasks(run.id()).stream()
            .filter(t->ids.contains(t.id())).toList();
        if(!run.state().equals("RUNNING"))return List.of();
        for(var task:members){
            var retry=executions.retry(task.id()).orElse(null);var history=executions.attempts(task.id());
            if(!task.state().equals("RETRY_WAIT") || retry==null || now.isBefore(retry.availableAt()) || !now.isBefore(retry.deadline())
                || history.isEmpty() || !history.getFirst().id().equals(retry.failedAttemptId()) || !history.getFirst().state().equals("FAILED")
                || retryAttempts(history)>=run.retry().maxAttempts() || !runtimes.retryReady(task.id()))return List.of();
        }
        if(plan.componentRoutes(taskId).stream().anyMatch(r->routes.open(r.id()).isPresent()))return List.of();
        var result=new ArrayList<TaskAttempt>();
        for(var task:members)result.add(executions.startRetry(task.id(),now));
        return List.copyOf(result);
    }
    private static long retryAttempts(List<TaskAttempt> history){return history.stream().filter(a->!a.cause().equals("OFFLOAD")).count();}
}
