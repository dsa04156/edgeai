package io.edgeai.app.service;

import io.edgeai.app.dto.OffloadLabResponse;
import io.edgeai.app.dto.OffloadOperationResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.domain.execution.OffloadLabSession;
import io.edgeai.domain.node.NodeMetricsSource;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.OffloadLabGateway;
import io.edgeai.domain.runtime.RuntimeScope;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static io.edgeai.app.support.WorkflowInput.*;

/**
 * Offload test console: one ordinary Run of the configured lab workflow on a chosen edge (the node policy moves it, not this
 * service), plus one owned, bounded CPU load Job generation on that edge. Kubernetes calls happen after the database commit;
 * the reconciler repeats them, expires load and finishes stops, so a reload or API restart never leaves a running burner.
 */
@Service
public class OffloadLabService {
    private static final Set<String> FINISHED=Set.of("SUCCEEDED","FAILED","CANCELLED");
    private final OffloadLabRepository sessions;
    private final ExecutionService executions;
    private final ExecutionRepository runs;
    private final RuntimeRepository runtimes;
    private final WorkflowRepository workflows;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final NodeMetricsService metrics;
    private final OffloadRepository offloads;
    private final TelemetryRepository telemetry;
    private final Optional<OffloadLabGateway> gateway;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String workflowKey;
    private final boolean executes;
    private final ScheduledExecutorService scheduler;

    public OffloadLabService(OffloadLabRepository sessions,ExecutionService executions,ExecutionRepository runs,RuntimeRepository runtimes,WorkflowRepository workflows,
            ProfileRepository profiles,NodeRepository nodes,NodeMetricsService metrics,OffloadRepository offloads,TelemetryRepository telemetry,
            Optional<OffloadLabGateway> gateway,TransactionTemplate transactions,RuntimeScope scope,Clock clock,
            @Value("${edgeai.offload-lab.workflow-key:offload-lab}") String workflowKey,@Value("${edgeai.runtime.enabled:false}") boolean runtimeEnabled,
            @Value("${edgeai.runtime.worker-enabled:true}") boolean workerEnabled,@Value("${edgeai.recovery.inspect-only:false}") boolean inspection,
            @Value("${edgeai.offload-lab.poll-ms:3000}") long pollMillis) {
        this.sessions=sessions;this.executions=executions;this.runs=runs;this.runtimes=runtimes;this.workflows=workflows;this.profiles=profiles;this.nodes=nodes;
        this.metrics=metrics;this.offloads=offloads;this.telemetry=telemetry;this.gateway=gateway;this.transactions=transactions;this.clock=clock;
        this.workflowKey=workflowKey;this.executes=runtimeEnabled && scope.allows(workflowKey);
        if(gateway.isPresent() && workerEnabled && !inspection) {
            scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("offload-lab").daemon().factory());
            scheduler.scheduleWithFixedDelay(this::reconcile,pollMillis,pollMillis,TimeUnit.MILLISECONDS);
        } else scheduler=null;
    }
    @jakarta.annotation.PreDestroy void close(){if(scheduler!=null)scheduler.shutdownNow();}

    private static ControlPlaneException error(int status,String code,String message){return new ControlPlaneException(status,code,message);}
    private static UUID key(String value) {
        try{var id=UUID.fromString(value);if(id.toString().equals(value))return id;}catch(IllegalArgumentException ignored){}
        throw error(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key는 UUID여야 합니다.");
    }
    private String disabledReason() {
        if(!executes)return "RUNTIME_DISABLED";
        if(gateway.isEmpty())return "KUBERNETES_DISABLED";
        return latestVersion().isEmpty()?"WORKFLOW_NOT_PUBLISHED":null;
    }
    private Optional<io.edgeai.domain.workflow.WorkflowVersion> latestVersion() {
        return workflows.findByKey(workflowKey).flatMap(w->workflows.versions(w.id(),1,0).stream().findFirst());
    }

    // ---- Session (model) ----
    public Creation<OffloadLabSession> start(String idempotencyKey,String body) {
        var idempotency=key(idempotencyKey);
        var input=object(parameters(JSON.parse(body,16384)),"edgeNodeId","offload");
        UUID edge=uuid(input.get("edgeNodeId"));
        var policy=offloadPolicy(input.get("offload"));
        if(policy.nodePlacement()==null)throw new IllegalArgumentException("nodePlacement required");
        String digest=JSON.digest("edgeai-offload-lab-session-v1",Map.of("edgeNodeId",edge.toString(),"offload",input.get("offload")));
        var previous=sessions.byKey(idempotency);if(previous.isPresent())return replay(previous.get(),digest);
        String reason=disabledReason();
        if(reason!=null)throw error(503,"OFFLOAD_LAB_"+reason,"오프로딩 테스트를 실행할 수 없습니다 ("+reason+").");
        var version=latestVersion().orElseThrow();
        var node=nodes.find(edge).orElseThrow(()->error(404,"NODE_NOT_FOUND","edge 노드를 찾을 수 없습니다."));
        var created=transactions.execute(status->{
            var open=sessions.latest().filter(s->!s.state().equals("STOPPED"));
            if(open.isPresent())throw error(409,"OFFLOAD_LAB_BUSY","진행 중인 오프로딩 테스트가 있습니다. 모델 중지 후 다시 시작하세요.");
            var request=new TreeMap<String,Object>();request.put("workflowVersionId",version.id().toString());
            request.put("execution",Map.of("mode","NODE","nodeId",edge.toString()));request.put("parameters",Map.of());request.put("offload",input.get("offload"));
            // Same session key ⇒ same Run key, so a replayed start never creates a second Run.
            var runKey=UUID.nameUUIDFromBytes(("edgeai-offload-lab:"+idempotency).getBytes(StandardCharsets.UTF_8));
            var run=executions.create(runKey.toString(),JSON.canonical(request)).value();
            var now=clock.instant();
            var session=new OffloadLabSession(UUID.randomUUID(),idempotency,digest,run.id(),node.id(),node.name(),"ACTIVE",0,"NONE",null,null,null,null,null,null,null,now,now,null);
            if(!sessions.create(session)){status.setRollbackOnly();return null;}
            return session;
        });
        if(created==null) {
            var raced=sessions.byKey(idempotency);if(raced.isPresent())return replay(raced.get(),digest);
            throw error(409,"OFFLOAD_LAB_BUSY","진행 중인 오프로딩 테스트가 있습니다. 모델 중지 후 다시 시작하세요.");
        }
        return new Creation<>(created,true);
    }
    private static Creation<OffloadLabSession> replay(OffloadLabSession session,String digest) {
        if(!session.requestDigest().equals(digest))throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 시험 입력이 있습니다.");
        return new Creation<>(session,false);
    }
    /** User stop: cancels the Run (and with it any transition) and the load. Works without metrics, workflow or Kubernetes. */
    public OffloadLabSession stop(UUID id,String body) {
        object(parameters(JSON.parse(body,64)));
        int generation=transactions.execute(status->{
            var session=sessions.find(id,true).orElseThrow(OffloadLabService::notFound);
            if(session.state().equals("ACTIVE")){cancelRun(session.runId());sessions.stopping(id,clock.instant());}
            return session.loadGeneration(); // Not ACTIVE any more: no later generation can start.
        });
        release(id,generation);
        return sessions.find(id,false).orElseThrow();
    }
    private void cancelRun(UUID runId) {
        var run=runs.run(runId,true).orElseThrow();
        if(!FINISHED.contains(run.state()))executions.cancelRun(runId,"{}");
    }
    private static ControlPlaneException notFound(){return error(404,"OFFLOAD_LAB_NOT_FOUND","오프로딩 테스트를 찾을 수 없습니다.");}

    // ---- Load ----
    public Creation<OffloadLabSession> startLoad(UUID id,String idempotencyKey,String body) {
        var idempotency=key(idempotencyKey);
        var input=object(parameters(JSON.parse(body,1024)),"workers","durationSeconds");
        int workers=bounded(input.get("workers"),1,16),seconds=bounded(input.get("durationSeconds"),30,1800);
        String digest=JSON.digest("edgeai-offload-lab-load-v1",Map.of("sessionId",id.toString(),"workers",workers,"durationSeconds",seconds));
        var result=transactions.execute(status->{
            var session=sessions.find(id,true).orElseThrow(OffloadLabService::notFound);var now=clock.instant();
            if(idempotency.equals(session.loadKey())) {
                if(!digest.equals(session.loadDigest()))throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 부하 입력이 있습니다.");
                return new Creation<>(session,false);
            }
            if(!session.state().equals("ACTIVE"))throw error(409,"OFFLOAD_LAB_STOPPED","중지된 시험에는 부하를 시작할 수 없습니다.");
            if(FINISHED.contains(runs.run(session.runId(),false).orElseThrow().state()))
                throw error(409,"OFFLOAD_LAB_RUN_FINISHED","모델 실행이 이미 끝났습니다. 모델 중지 후 새 시험을 시작하세요.");
            if(session.loadRunning(now))throw error(409,"OFFLOAD_LAB_LOAD_RUNNING","이 시험의 부하가 이미 실행 중입니다. 부하 중지 후 다시 시작하세요.");
            if(gateway.isEmpty())throw error(503,"OFFLOAD_LAB_KUBERNETES_DISABLED","부하 Job을 만들 Kubernetes 연결이 없습니다.");
            if(session.loadGeneration()>=1000)throw error(409,"OFFLOAD_LAB_LOAD_LIMIT","시험당 부하 시작 한도를 넘었습니다.");
            sessions.startLoad(id,session.loadGeneration()+1,idempotency,digest,workers,seconds,now);
            return new Creation<>(sessions.find(id,false).orElseThrow(),true);
        });
        if(result.created())submit(result.value());
        return new Creation<>(sessions.find(id,false).orElseThrow(),result.created());
    }
    private static int bounded(Object value,int min,int max) {
        if(!(value instanceof Number n) || n.doubleValue()!=Math.rint(n.doubleValue()) || n.doubleValue()<min || n.doubleValue()>max)
            throw new IllegalArgumentException("Value out of range");
        return n.intValue();
    }
    public OffloadLabSession stopLoad(UUID id,String body) {
        object(parameters(JSON.parse(body,64)));
        // Only the generation running at this moment is stopped; a load started afterwards is a new, independent generation.
        int generation=transactions.execute(status->{
            var session=sessions.find(id,true).orElseThrow(OffloadLabService::notFound);sessions.stopLoad(id,session.loadGeneration(),clock.instant());return session.loadGeneration();
        });
        release(id,generation);
        return sessions.find(id,false).orElseThrow();
    }
    /**
     * Best effort now; the reconciler retries until the Job exists. If this generation was stopped or replaced while the Job was
     * being created (the caller's view is stale), the Job just created is removed again rather than left running.
     */
    private void submit(OffloadLabSession s) {
        if(gateway.isEmpty() || s.loadJobUid()!=null)return;
        try {
            var uid=gateway.get().ensureLoad(new OffloadLabGateway.Load(s.id(),s.loadGeneration(),s.edgeNodeName(),s.loadWorkers(),s.loadSeconds()));
            sessions.loadSubmitted(s.id(),s.loadGeneration(),uid);
            var current=sessions.find(s.id(),false).orElseThrow();
            if(current.loadGeneration()!=s.loadGeneration() || !current.loadRunning(clock.instant()))release(s.id(),s.loadGeneration());
        } catch(RuntimeException e){LoggerFactory.getLogger(OffloadLabService.class).warn("Offload lab load submission deferred ({})",e.getClass().getSimpleName());}
    }
    /** Removes load generations up to {@code generation} only; a newer generation started concurrently is never touched. */
    private boolean release(UUID id,int generation) {
        if(generation<1)return true; // No load was ever requested: nothing to observe.
        if(gateway.isEmpty())return false; // Unobservable is not stopped.
        try{return gateway.get().deleteLoad(id,generation);}
        catch(RuntimeException e){LoggerFactory.getLogger(OffloadLabService.class).warn("Offload lab load removal deferred ({})",e.getClass().getSimpleName());return false;}
    }

    // ---- Reconciliation ----
    public void reconcile() {
        try {
            // Kubernetes first, then the database: a Job listed here was created after its session row committed.
            var owners=gateway.isPresent()?gateway.get().loadSessions():Set.<UUID>of();
            var now=clock.instant();
            for(var id:sessions.unfinished()) {
                try{advance(id,now);}
                catch(RuntimeException e){LoggerFactory.getLogger(OffloadLabService.class).warn("Offload lab session reconciliation deferred ({})",e.getClass().getSimpleName());}
            }
            // Bound each removal by the generation read now: a load started after this read is newer and kept.
            for(var owner:owners) {
                var session=sessions.find(owner,false).orElse(null);
                int through=session==null?Integer.MAX_VALUE:session.loadRunning(clock.instant())?session.loadGeneration()-1:session.loadGeneration();
                release(owner,through);
            }
        } catch(RuntimeException e){LoggerFactory.getLogger(OffloadLabService.class).warn("Offload lab reconciliation deferred ({})",e.getClass().getSimpleName());}
    }
    /**
     * One step for one session. Every effect is bounded by the generation read at the start: removals and the expiry update
     * never reach a generation that a concurrent startLoad created after that read.
     */
    void advance(UUID id,Instant now) {
        var session=sessions.find(id,false).orElseThrow();
        if(session.state().equals("ACTIVE") && FINISHED.contains(runs.run(session.runId(),false).orElseThrow().state())) {
            // Model ended on its own (duration, failure), also while load runs: STOPPING stops the load and admits no new generation.
            transactions.executeWithoutResult(status->sessions.stopping(id,now));session=sessions.find(id,false).orElseThrow();
        }
        int generation=session.loadGeneration();
        if(session.loadRunning(now)){submit(session);return;}
        boolean loadGone=release(id,generation);
        if(session.loadDesired().equals("RUNNING")) // Expired: the program already exited or is past its bound.
            transactions.executeWithoutResult(status->sessions.stopLoad(id,generation,now));
        var latest=sessions.find(id,false).orElseThrow();
        if(latest.state().equals("STOPPING")) {
            if(latest.loadGeneration()!=generation)loadGone=release(id,latest.loadGeneration()); // Started before STOPPING; none can follow it.
            transactions.executeWithoutResult(status->cancelRun(latest.runId()));
            var run=runs.run(latest.runId(),false).orElseThrow();
            boolean modelGone=FINISHED.contains(run.state()) && runs.tasks(run.id()).stream().allMatch(t->runtimes.retryReady(t.id()));
            if(modelGone && loadGone)transactions.executeWithoutResult(status->sessions.stopped(id,clock.instant()));
        }
    }

    // ---- View ----
    public OffloadLabResponse view() {
        var now=clock.instant();
        NodeMetricsSource.Snapshot snapshot;
        try{snapshot=metrics.snapshot();}catch(RuntimeException e){snapshot=null;}
        var nodeViews=new ArrayList<OffloadLabResponse.NodeView>();
        for(int offset=0;;offset+=1000) {
            var page=nodes.list(1000,offset);
            for(var node:page) {
                String role=PlacementPolicyService.edge(node)?"EDGE":PlacementPolicyService.worker(node)?"WORKER":null;
                if(role==null || node.status(now).equals("REMOVED"))continue;
                var m=snapshot==null || !snapshot.status().equals("AVAILABLE")?null:snapshot.items().stream().filter(i->i.nodeName().equals(node.name())).findFirst().orElse(null);
                var cpu=measurement(m,"CPU_USAGE",false);var memory=measurement(m,"MEMORY_USAGE",false);var gpu=measurement(m,"GPU_USAGE",true);
                Instant observed=cpu==null?memory==null?null:memory.observedAt():cpu.observedAt();
                nodeViews.add(new OffloadLabResponse.NodeView(node.id(),node.name(),role,node.architecture(),node.status(now),value(cpu),value(memory),value(gpu),observed));
            }
            if(page.size()<1000)break;
        }
        nodeViews.sort(Comparator.comparing(OffloadLabResponse.NodeView::role).thenComparing(OffloadLabResponse.NodeView::name));
        var session=sessions.latest().map(s->session(s,now)).orElse(null);
        return new OffloadLabResponse(disabledReason()==null,disabledReason(),model(),gateway.map(OffloadLabGateway::loadImage).orElse(null),
            snapshot==null?new OffloadLabResponse.Metrics("UNAVAILABLE",null,0):new OffloadLabResponse.Metrics(snapshot.status(),snapshot.fetchedAt(),snapshot.maxAgeSeconds()),
            nodeViews,defaults(),session);
    }
    /** Fresh values only; a GPU value is the busiest fresh GPU. */
    private static NodeMetricsSource.Measurement measurement(NodeMetricsSource.NodeMetrics m,String key,boolean gpu) {
        if(m==null)return null;
        return m.measurements().stream().filter(x->x.key().equals(key) && x.fresh() && Double.isFinite(x.value()) && (gpu || x.device().isEmpty()))
            .max(Comparator.comparingDouble(NodeMetricsSource.Measurement::value)).orElse(null);
    }
    private static Double value(NodeMetricsSource.Measurement m){return m==null?null:m.value();}
    private OffloadLabResponse.Model model() {
        var version=latestVersion().orElse(null);if(version==null)return null;
        var definitions=workflows.definitions(version.id());if(definitions.size()!=1)return null;
        var definition=definitions.getFirst();var profile=profiles.find(definition.serviceProfileVersionId()).orElse(null);if(profile==null)return null;
        io.edgeai.domain.runtime.ServiceExecutionSpec spec;
        try{spec=ServiceExecutionInput.parseSpec(profile.specJson());}catch(IllegalArgumentException e){return null;} // Unknown, not invented.
        var command=new ArrayList<>(spec.command());command.addAll(spec.args());
        return new OffloadLabResponse.Model(workflowKey,version.id(),version.version(),profile.identity().key(),profile.identity().version(),spec.image(),command,
            spec.architectures(),spec.resources().requests(),taskParameters(definition.parametersJson()));
    }
    private static Map<String,Object> taskParameters(String json){var result=new TreeMap<String,Object>();parameters(JSON.decode(json)).forEach((k,v)->result.put(String.valueOf(k),v));return result;}
    /** Lab preset only (visible and editable before start); production policies are unaffected. */
    private static Map<String,Object> defaults() {
        var back=new TreeMap<String,Object>();back.put("cpuPercent",20);back.put("memoryPercent",null);back.put("gpuPercent",null);
        var node=new TreeMap<String,Object>();node.put("strategy","EDGE_TO_WORKER");node.put("sourceCpuPercent",50);node.put("sourceMemoryPercent",null);
        node.put("sourceGpuPercent",null);node.put("targetMaxCpuPercent",60);node.put("targetMaxMemoryPercent",80);node.put("targetMaxGpuPercent",80);node.put("returnToEdge",back);
        var offload=new TreeMap<String,Object>();offload.put("cpuPercent",null);offload.put("memoryPercent",null);offload.put("latencyMicros",null);
        offload.put("consecutiveSamples",2);offload.put("maxSampleAgeSeconds",90);offload.put("maxGapSeconds",75);offload.put("minRunningSeconds",20);
        offload.put("cooldownSeconds",30);offload.put("maxTransfers",2);offload.put("drainTimeoutSeconds",120);offload.put("startTimeoutSeconds",300);offload.put("nodePlacement",node);
        return Map.of("offload",offload,"load",Map.of("workers",3,"durationSeconds",300));
    }
    private OffloadLabResponse.Session session(OffloadLabSession s,Instant now) {
        var run=runs.run(s.runId(),false).orElseThrow();
        var task=runs.tasks(run.id()).stream().findFirst().orElse(null);
        var attempts=new ArrayList<OffloadLabResponse.Attempt>();var latency=new ArrayList<OffloadLabResponse.Latency>();
        String current=null;var operations=List.<OffloadOperationResponse>of();
        if(task!=null) {
            for(var a:runs.attempts(task.id())) {
                var r=runtimes.byAttempt(a.id()).orElse(null);
                String nodeName=r!=null && r.nodeName()!=null?r.nodeName():a.nodeId()==null?null:nodes.find(a.nodeId()).map(n->n.name()).orElse(null);
                attempts.add(new OffloadLabResponse.Attempt(a.id(),a.number(),a.cause(),a.state(),a.nodeId(),nodeName,r==null?null:r.jobName(),r==null?null:r.producerPodUid(),
                    r==null?null:r.desiredState(),r==null?null:r.observedState(),r==null?null:r.failureReason(),a.createdAt(),a.updatedAt()));
                if(current==null && r!=null && r.observedState().equals("RUNNING") && r.desiredState().equals("RUNNING"))current=r.nodeName();
            }
            if(!attempts.isEmpty())for(var t:telemetry.recent(attempts.getFirst().id(),20))
                if(t.latencyMicros()!=null)latency.add(new OffloadLabResponse.Latency(t.attemptId(),t.sequence(),t.latencyMicros(),t.latencyObservedAt()));
            operations=offloads.forTask(task.id()).stream().map(OffloadOperationResponse::from).toList();
        }
        OffloadLabResponse.Pods pods;
        if(gateway.isEmpty())pods=new OffloadLabResponse.Pods("UNAVAILABLE",null,List.of());
        else try {
            pods=new OffloadLabResponse.Pods("OBSERVED",clock.instant(),gateway.get().pods(s.id(),s.runId()).stream().map(p->new OffloadLabResponse.Pod(p.role(),p.name(),p.uid(),p.nodeName(),
                p.phase(),p.ready(),p.attemptId(),p.generation(),p.createdAt(),p.startedAt(),p.deletingAt(),p.reason())).toList());
        } catch(RuntimeException e){pods=new OffloadLabResponse.Pods("UNAVAILABLE",null,List.of());}
        var load=new OffloadLabResponse.Load(s.loadDesired().equals("RUNNING") && !s.loadRunning(now)?"EXPIRED":s.loadDesired(),s.loadGeneration(),s.loadWorkers(),s.loadSeconds(),
            s.loadStartedAt(),s.loadExpiresAt(),s.loadJobUid());
        return new OffloadLabResponse.Session(s.id(),s.state(),run.id(),run.state(),s.edgeNodeId(),s.edgeNodeName(),
            run.offloadPolicyJson()==null?null:JSON.decode(run.offloadPolicyJson()),s.createdAt(),s.stoppedAt(),task==null?null:task.id(),task==null?null:task.state(),
            current,attempts,operations,latency,load,pods);
    }
}
