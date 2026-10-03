package io.edgeai.app.service;

import io.edgeai.app.config.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.execution.WorkflowRun;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;

/** VD -> all Devices -> Run locks serialize membership, terminal reports and component grants. */
@Service
public class StreamExecutionService {
    private final StreamExecutionRepository store;private final StreamCheckpointRepository checkpoints;
    private final DataRouteRepository routeStore;private final DataRouteService routes;
    private final ExecutionRepository executions;private final RuntimeRepository runtimes;
    private final WorkflowRepository workflows;private final ProfileRepository profiles;
    private final DeviceRepository devices;private final VirtualDeviceRepository vds;
    private final RuntimeLifecycleService lifecycle;private final Clock clock;
    private record Context(WorkflowRun run,List<DataRoute> routes) {}
    public StreamExecutionService(StreamExecutionRepository store,StreamCheckpointRepository checkpoints,
            DataRouteRepository routeStore,DataRouteService routes,ExecutionRepository executions,RuntimeRepository runtimes,
            WorkflowRepository workflows,ProfileRepository profiles,DeviceRepository devices,VirtualDeviceRepository vds,
            RuntimeLifecycleService lifecycle,Clock clock) {
        this.store=store;this.checkpoints=checkpoints;this.routeStore=routeStore;this.routes=routes;this.executions=executions;
        this.runtimes=runtimes;this.workflows=workflows;this.profiles=profiles;this.devices=devices;this.vds=vds;this.lifecycle=lifecycle;this.clock=clock;
    }
    @Transactional
    public Object execution(RunnerPrincipal principal,String body) {
        RunnerInput.parse(body,principal);var r=runtime(principal);var c=lock(r.runId());authorize(principal);
        var plan=plan(c);if(plan.isEmpty())return Map.of("state","WAITING");var p=plan.get();
        if(p.specs().get(r.taskId()).stream()==null)throw conflict("STREAM_SERVICE_REQUIRED");
        freeze(c);
        if(store.granted(r.attemptId()).isPresent())return finalization(principal,r,p);
        var input=new TreeMap<String,Object>();var output=new TreeMap<String,List<String>>();var ids=new ArrayList<UUID>();
        for(var route:p.taskRoutes(r.taskId())) {
            var generation=routeStore.open(route.id()).orElse(null);
            if(generation==null || !ready(route,generation))return Map.of("state","WAITING");
            ids.add(generation.id());
            if(route.consumerTaskId().equals(r.taskId()))input.put(route.consumerPort(),generation.id().toString());
            else output.computeIfAbsent(route.sourcePort(),k->new ArrayList<>()).add(generation.id().toString());
        }
        routes.authorizeTask(r.taskId(),ids,new Principal("TASK",new RouteGeneration.Actor(principal.attemptId(),principal.epoch())));
        var latest=checkpoints.latest(r.taskId()).orElse(null);
        String recovery=latest==null?"NEW":latest.attemptId().equals(r.attemptId()) && new HashSet<>(latest.request().generationIds()).equals(new HashSet<>(ids))?"RESTORE":"HANDOVER";
        return Map.of("state","READY","runId",r.runId().toString(),"taskId",r.taskId().toString(),"attemptId",r.attemptId().toString(),
            "epoch",r.epoch(),"inputs",input,"outputs",output,"recovery",recovery);
    }
    /** Read-only authority for the sealed finalizer; does not renew any MQTT generation. */
    @Transactional
    public StreamCheckpoint finalized(RunnerPrincipal principal,String body) {
        var input=RunnerInput.parse(body,principal,"checkpointId");var id=uuid(input.get("checkpointId"));
        var r=runtime(principal);var c=lock(r.runId());authorize(principal);requiredPlan(c);
        var checkpoint=grantedCheckpoint(principal,r);
        if(!checkpoint.id().equals(id))throw conflict("STREAM_COMPLETION_CONFLICT");
        return checkpoint;
    }
    private StreamCheckpoint grantedCheckpoint(RunnerPrincipal principal,RuntimeInstance runtime) {
        var done=store.granted(runtime.attemptId()).orElseThrow(()->conflict("STREAM_COMPLETION_REQUIRED"));
        var checkpoint=checkpoints.latest(runtime.taskId()).orElseThrow(()->conflict("STREAM_CHECKPOINT_MISSING"));
        if(!checkpoint.id().equals(done.checkpointId()) || !checkpoint.attemptId().equals(done.attemptId()))throw conflict("STREAM_CHECKPOINT_STALE");
        if(done.attemptId().equals(runtime.attemptId())){
            if(checkpoint.epoch()!=principal.epoch() || !checkpoint.producerPodUid().equals(principal.podUid())
                || !checkpoint.runtimeId().equals(runtime.id()))throw conflict("STREAM_CHECKPOINT_STALE");
        }else{
            var source=runtimes.byAttempt(done.attemptId()).orElseThrow(()->conflict("STREAM_CHECKPOINT_STALE"));
            if(!source.taskId().equals(runtime.taskId()) || source.epoch()>=runtime.epoch()
                || !source.desiredState().equals("STOPPED") || !source.observedState().equals("TERMINATED"))throw conflict("STREAM_CHECKPOINT_STALE");
        }
        terminal(checkpoint);return checkpoint;
    }
    private Object finalization(RunnerPrincipal principal,RuntimeInstance runtime,StreamRunPlan plan) {
        var checkpoint=grantedCheckpoint(principal,runtime);var generations=new HashMap<UUID,RouteGeneration>();
        for(var id:checkpoint.request().generationIds()) {
            var g=routeStore.generation(id).orElseThrow(()->conflict("STREAM_COMPONENT_CHANGED"));
            if(generations.put(g.routeId(),g)!=null)throw conflict("STREAM_COMPONENT_CHANGED");
        }
        var inputs=new TreeMap<String,String>();var outputs=new TreeMap<String,List<String>>();
        for(var route:plan.taskRoutes(runtime.taskId())) {
            var g=generations.remove(route.id());if(g==null)throw conflict("STREAM_COMPONENT_CHANGED");
            boolean input=route.consumerTaskId().equals(runtime.taskId());
            if(!(input?g.consumer():g.producer()).equals(new RouteGeneration.Actor(checkpoint.attemptId(),checkpoint.epoch())))
                throw conflict("STREAM_COMPONENT_CHANGED");
            if(input)inputs.put(route.consumerPort(),route.id().toString());
            else outputs.computeIfAbsent(route.sourcePort(),k->new ArrayList<>()).add(route.id().toString());
        }
        if(!generations.isEmpty())throw conflict("STREAM_COMPONENT_CHANGED");
        outputs.values().forEach(Collections::sort);
        var reply=new TreeMap<String,Object>(Map.of("state","FINALIZE","runId",runtime.runId().toString(),"taskId",runtime.taskId().toString(),
            "attemptId",runtime.attemptId().toString(),"epoch",runtime.epoch(),"checkpointId",checkpoint.id().toString(),
            "inputRoutes",inputs,"outputRoutes",outputs,"generationIds",checkpoint.request().generationIds().stream().map(UUID::toString).toList()));
        if(!checkpoint.attemptId().equals(runtime.attemptId()))reply.put("checkpointActor",Map.of("attemptId",checkpoint.attemptId().toString(),"epoch",checkpoint.epoch()));
        return reply;
    }
    @Transactional
    public Object complete(RunnerPrincipal principal,String body) {
        var input=RunnerInput.parse(body,principal,"checkpointId");var id=uuid(input.get("checkpointId"));
        var r=runtime(principal);var c=lock(r.runId());authorize(principal);
        if(store.granted(r.attemptId()).isPresent()){
            if(!grantedCheckpoint(principal,r).id().equals(id))throw conflict("STREAM_COMPLETION_CONFLICT");
            return taskReply(id,true);
        }
        var prior=store.task(r.attemptId()).orElse(null);
        if(prior!=null && !prior.checkpointId().equals(id))throw conflict("STREAM_COMPLETION_CONFLICT");
        var plan=requiredPlan(c);var checkpoint=checkpoints.latest(r.taskId()).orElseThrow(()->conflict("STREAM_CHECKPOINT_MISSING"));
        if(!checkpoint.id().equals(id) || !checkpoint.attemptId().equals(r.attemptId()) || checkpoint.epoch()!=principal.epoch()
                || !checkpoint.producerPodUid().equals(principal.podUid()))throw conflict("STREAM_CHECKPOINT_STALE");
        routes.authorizeTask(r.taskId(),checkpoint.request().generationIds(),new Principal("TASK",new RouteGeneration.Actor(r.attemptId(),r.epoch())));
        terminal(checkpoint);
        if(prior==null)store.recordTask(r.attemptId(),id,now());
        grant(plan,r.taskId());
        return taskReply(id,store.task(r.attemptId()).orElseThrow().grantedAt()!=null);
    }
    @Transactional
    public Object deviceComplete(DeviceStreamPrincipal principal,String body) {
        var input=new DeviceInput(body,"epoch","generationId","sequence");
        if(input.number("epoch")!=principal.epoch())throw conflict("STREAM_FOREIGN_ACTOR");
        UUID id=input.uuid("generationId");long sequence=input.number("sequence");
        if(sequence<1 || sequence>9007199254740991L)throw new IllegalArgumentException("Invalid terminal sequence");
        var g=routeStore.generation(id).orElseThrow(()->conflict("STREAM_GENERATION_FENCED"));
        var route=routeStore.route(g.routeId(),false).orElseThrow();var c=lock(route.runId(),true);
        var device=devices.find(principal.deviceId(),false).orElseThrow(()->conflict("STREAM_FOREIGN_ACTOR"));
        var session=devices.activeSession(device.id()).orElseThrow(()->conflict("STREAM_FOREIGN_ACTOR"));
        if(!principal.deviceId().equals(route.sourceDeviceId()) || device.state()!=Device.State.ACTIVE
                || !session.id().equals(principal.sessionId()) || session.epoch()!=principal.epoch()
                || device.sessionEpoch()!=principal.epoch() || !g.producer().equals(new RouteGeneration.Actor(session.id(),session.epoch())))
            throw conflict("STREAM_FOREIGN_ACTOR");
        var prior=store.device(id).orElse(null);
        if(prior!=null && prior.sequence()!=sequence)throw conflict("STREAM_COMPLETION_CONFLICT");
        if(prior!=null && prior.grantedAt()!=null)return deviceReply(id,sequence,true);
        if(c.run().state().equals("SUCCEEDED"))throw conflict("STREAM_RUN_INACTIVE");
        var plan=requiredPlan(c);
        routes.authorize(id,new Principal("DEVICE",g.producer()));
        var checkpoint=checkpoints.latest(route.consumerTaskId()).orElseThrow(()->conflict("STREAM_CHECKPOINT_MISSING"));
        if(!checkpoint.attemptId().equals(g.consumer().id()) || !checkpoint.request().generationIds().contains(id)
                || cursor(checkpoint,route.id())!=sequence)throw conflict("STREAM_END_NOT_CONFIRMED");
        if(prior==null)store.recordDevice(id,sequence,now());
        grant(plan,route.consumerTaskId());
        return deviceReply(id,sequence,store.device(id).orElseThrow().grantedAt()!=null);
    }
    private void grant(StreamRunPlan plan,UUID task) {
        var actors=new HashMap<UUID,RouteGeneration.Actor>();var deviceGenerations=new ArrayList<UUID>();
        var generations=new HashMap<UUID,RouteGeneration>();
        for(var route:plan.componentRoutes(task)) {
            var g=routeStore.open(route.id()).orElse(null);if(g==null || !ready(route,g))return;
            generations.put(route.id(),g);actor(actors,route.consumerTaskId(),g.consumer());
            if(route.deviceSource()) {
                var done=store.device(g.id()).orElse(null);if(done==null)return;deviceGenerations.add(g.id());
            } else actor(actors,route.sourceTaskId(),g.producer());
        }
        if(!actors.keySet().equals(plan.componentTasks(task)))throw conflict("STREAM_COMPONENT_CHANGED");
        var terminal=new HashMap<UUID,StreamCheckpoint>();
        for(var entry:actors.entrySet()) {
            var actor=entry.getValue();var done=store.task(actor.id()).orElse(null);if(done==null)return;
            var checkpoint=checkpoints.latest(entry.getKey()).orElseThrow();
            if(!checkpoint.id().equals(done.checkpointId()) || !checkpoint.attemptId().equals(actor.id()) || checkpoint.epoch()!=actor.epoch())
                throw conflict("STREAM_COMPONENT_CHANGED");
            var ids=plan.taskRoutes(entry.getKey()).stream().map(r->generations.get(r.id()).id()).collect(java.util.stream.Collectors.toSet());
            if(!ids.equals(new HashSet<>(checkpoint.request().generationIds())))throw conflict("STREAM_COMPONENT_CHANGED");
            terminal(checkpoint);lifecycle.authorizeProducerUntil(actor.id(),actor.epoch(),checkpoint.producerPodUid());
            terminal.put(entry.getKey(),checkpoint);
        }
        for(var route:plan.componentRoutes(task)) {
            var g=generations.get(route.id());long consumed=cursor(terminal.get(route.consumerTaskId()),route.id());
            long produced=route.deviceSource()?store.device(g.id()).orElseThrow().sequence():cursor(terminal.get(route.sourceTaskId()),route.id());
            if(consumed!=produced)throw conflict("STREAM_TERMINAL_CURSOR_MISMATCH");
        }
        store.grant(actors.values().stream().map(RouteGeneration.Actor::id).toList(),deviceGenerations,now());
    }
    private boolean ready(DataRoute route,RouteGeneration g) {
        if(!routes.accepts(g.id(),g.producer(),g.consumer()))return false;
        var ids=new ArrayList<UUID>();ids.add(g.consumer().id());if(!route.deviceSource())ids.add(g.producer().id());
        for(var id:ids) {
            var runtime=runtimes.byAttempt(id).orElse(null);
            if(runtime==null || runtime.remote() || !runtime.desiredState().equals("RUNNING") || !runtime.observedState().equals("RUNNING")
                    || runtime.producerPodUid()==null || runtime.expiresAt()==null || !clock.instant().isBefore(runtime.expiresAt()))return false;
        }
        return true;
    }
    private static void actor(Map<UUID,RouteGeneration.Actor> map,UUID task,RouteGeneration.Actor actor) {
        var previous=map.putIfAbsent(task,actor);if(previous!=null && !previous.equals(actor))throw conflict("STREAM_COMPONENT_CHANGED");
    }
    private static long cursor(StreamCheckpoint checkpoint,UUID route) {
        var summary=parameters(JSON.decode(checkpoint.summaryJson()));
        for(var raw:(List<?>)summary.get("routes")) {
            var row=parameters(raw);
            if(route.toString().equals(row.get("routeId"))) {
                long received=RunnerInput.integer(row.get("received")),committed=RunnerInput.integer(row.get("committed"));
                if(!Boolean.TRUE.equals(row.get("ended")) || received<1 || received!=committed)throw conflict("STREAM_END_NOT_CONFIRMED");
                return received;
            }
        }
        throw conflict("STREAM_END_NOT_CONFIRMED");
    }
    private static void terminal(StreamCheckpoint checkpoint) {
        var summary=parameters(JSON.decode(checkpoint.summaryJson()));
        for(var raw:(List<?>)summary.get("routes"))cursor(checkpoint,uuid(parameters(raw).get("routeId")));
    }
    private Context lock(UUID runId) {
        return lock(runId,false);
    }
    private Context lock(UUID runId,boolean completedReplay) {
        var before=executions.run(runId,false).orElseThrow();var initial=routeStore.forRun(runId,StreamRunPlan.MAX_ROUTES+1,0);
        if(initial.size()>StreamRunPlan.MAX_ROUTES)throw conflict("STREAM_ROUTE_LIMIT");
        if(before.vdId()!=null)vds.find(before.vdId(),true).orElseThrow();
        initial.stream().map(DataRoute::sourceDeviceId).filter(Objects::nonNull).distinct().sorted().forEach(id->devices.find(id,true).orElseThrow());
        var run=executions.run(runId,true).orElseThrow();
        if(!Set.of("PENDING","RUNNING").contains(run.state()) && !(completedReplay && run.state().equals("SUCCEEDED")))throw conflict("STREAM_RUN_INACTIVE");
        var current=routeStore.forRun(runId,StreamRunPlan.MAX_ROUTES+1,0);if(!initial.equals(current))throw conflict("STREAM_MEMBERSHIP_CHANGED");
        return new Context(run,current);
    }
    private Optional<StreamRunPlan> plan(Context c) {
        var tasks=executions.tasks(c.run().id());var definitions=workflows.definitions(c.run().workflowVersionId());
        var specs=new HashMap<UUID,ServiceExecutionSpec>();
        for(var task:tasks) {
            var definition=definitions.stream().filter(d->d.id().equals(task.definitionId())).findFirst().orElseThrow();
            specs.put(task.id(),ServiceExecutionInput.parseSpec(profiles.find(definition.serviceProfileVersionId()).orElseThrow().specJson()));
        }
        return StreamRunPlan.compile(tasks,specs,storedDag(workflows.version(c.run().workflowVersionId()).orElseThrow().dagJson()),c.routes());
    }
    private StreamRunPlan requiredPlan(Context c) {
        if(store.bindingDigest(c.run().id()).isEmpty())throw conflict("STREAM_EXECUTION_NOT_ASSIGNED");
        var plan=plan(c).orElseThrow(()->conflict("STREAM_BINDINGS_INCOMPLETE"));freeze(c);return plan;
    }
    private void freeze(Context c) {
        var digest=JSON.digest("edgeai-stream-membership-v1",c.routes().stream().map(r->r.id().toString()).sorted().toList());
        var prior=store.bindingDigest(c.run().id());
        if(prior.isPresent() && !prior.get().equals(digest))throw conflict("STREAM_MEMBERSHIP_CHANGED");
        if(prior.isEmpty())store.freeze(c.run().id(),digest,now());
    }
    private RuntimeInstance runtime(RunnerPrincipal principal) { return runtimes.byAttempt(principal.attemptId()).orElseThrow(()->conflict("STREAM_FOREIGN_ACTOR")); }
    private void authorize(RunnerPrincipal p) { lifecycle.authorizeProducerUntil(p.attemptId(),p.epoch(),p.podUid()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Object taskReply(UUID checkpoint,boolean granted) { return Map.of("state",granted?"FINALIZE":"WAITING","checkpointId",checkpoint.toString()); }
    private static Object deviceReply(UUID generation,long sequence,boolean granted) { return Map.of("state",granted?"FINALIZE":"WAITING","generationId",generation.toString(),"sequence",sequence); }
    private static ControlPlaneException conflict(String code) { return new ControlPlaneException(409,code,"현재 스트림 배정·확정 체크포인트·모든 참여자의 종료 확인을 확인하세요."); }
}
