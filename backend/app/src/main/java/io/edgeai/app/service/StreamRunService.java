package io.edgeai.app.service;

import io.edgeai.app.support.*;
import io.edgeai.app.config.DeviceStreamPrincipal;
import io.edgeai.app.dto.StreamRouteResponse;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.workflow.Dag;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

/** Public immutable source intent and simultaneous stream groups. No external I/O. */
@Service
public class StreamRunService {
    private final StreamRunRepository store;
    private final ExecutionRepository executions;
    private final WorkflowRepository workflows;
    private final ProfileRepository profiles;
    private final DeviceRepository devices;
    private final DataRouteRepository routes;
    private final DataRouteService lifecycle;
    private final RuntimeRepository runtimes;
    private final StreamExecutionRepository completions;
    private final Clock clock;
    private final boolean enabled;
    private final String brokerDigest;
    private final int leaseSeconds;
    public StreamRunService(StreamRunRepository store,ExecutionRepository executions,WorkflowRepository workflows,
            ProfileRepository profiles,DeviceRepository devices,DataRouteRepository routes,DataRouteService lifecycle,
            RuntimeRepository runtimes,StreamExecutionRepository completions,Clock clock,
            @Value("${edgeai.stream.runs-enabled:false}") boolean enabled,@Value("${edgeai.stream.enabled:false}") boolean authority,
            @Value("${edgeai.stream.bindings-enabled:false}") boolean bindings,@Value("${edgeai.runtime.enabled:false}") boolean runtime,
            @Value("${edgeai.stream.broker-digest:}") String brokerDigest,@Value("${edgeai.stream.lease-seconds:30}") int leaseSeconds){
        this.store=store;this.executions=executions;this.workflows=workflows;this.profiles=profiles;this.devices=devices;
        this.routes=routes;this.lifecycle=lifecycle;this.runtimes=runtimes;this.completions=completions;this.clock=clock;
        this.enabled=enabled && authority && bindings && runtime;this.brokerDigest=brokerDigest;this.leaseSeconds=leaseSeconds;
        if(enabled){RouteGeneration.digest(brokerDigest);if(!this.enabled || leaseSeconds<5 || leaseSeconds>120)
            throw new IllegalArgumentException("Public stream execution requires runtime, authority, bindings and a 5–120 second lease");}
    }
    public boolean streaming(Dag dag){
        return dag.dependencies().stream().anyMatch(e->e.mode()==Dag.Mode.STREAM) || dag.tasks().stream().anyMatch(t->
            ((Map<?,?>)JSON.decode(profiles.find(t.serviceProfileVersionId()).orElseThrow().specJson())).containsKey("stream"));
    }
    public boolean managed(UUID run){return store.find(run).isPresent();}
    /** Device-scoped metadata only. Reading never issues broker credentials or renews a lease. */
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object deviceRoutes(DeviceStreamPrincipal principal,String body){
        var input=new DeviceInput(body,"epoch","runId","limit","offset");
        if(input.number("epoch")!=principal.epoch())throw error(409,"STREAM_DEVICE_SCOPE_CHANGED","현재 장치 세션과 고정된 원본 세션을 확인하세요.");
        long pageLimit=input.number("limit"),pageOffset=input.number("offset");
        if(pageLimit<1 || pageLimit>100 || pageOffset>1000000)throw new IllegalArgumentException("Invalid device route page");
        int limit=(int)pageLimit,offset=(int)pageOffset;UUID id=input.uuid("runId");
        var device=devices.find(principal.deviceId(),false).orElseThrow();var session=devices.activeSession(device.id());
        if(device.state()!=Device.State.ACTIVE || session.isEmpty() || !session.get().id().equals(principal.sessionId())
            || session.get().epoch()!=principal.epoch() || device.sessionEpoch()!=principal.epoch())
            throw error(409,"STREAM_DEVICE_SCOPE_CHANGED","현재 장치 세션과 고정된 원본 세션을 확인하세요.");
        var owned=store.bindings(id).stream().filter(b->b.deviceId().equals(principal.deviceId())).toList();
        if(owned.isEmpty())throw error(404,"STREAM_DEVICE_RUN_NOT_FOUND","이 장치에 배정된 스트림 실행이 없습니다.");
        if(owned.stream().anyMatch(b->!b.sessionId().equals(principal.sessionId()) || b.epoch()!=principal.epoch()))
            throw error(409,"STREAM_DEVICE_SCOPE_CHANGED","현재 장치 세션과 고정된 원본 세션을 확인하세요.");
        var run=executions.run(id,false).orElseThrow();var ids=new HashSet<UUID>();owned.forEach(b->ids.add(b.routeId()));
        var all=routes.forRun(id,StreamRunPlan.MAX_ROUTES+1,0);
        if(all.size()>StreamRunPlan.MAX_ROUTES)throw new IllegalArgumentException("Too many stream routes");
        var selected=all.stream().filter(r->ids.contains(r.id())).sorted(Comparator.comparing(r->r.id().toString())).toList();
        if(selected.size()!=owned.size())throw new IllegalStateException("Incomplete immutable device bindings");
        var items=new ArrayList<Object>();
        for(var route:selected.stream().skip(offset).limit(limit).toList()){
            if(!principal.deviceId().equals(route.sourceDeviceId()))throw new IllegalStateException("Foreign immutable device binding");
            var generation=routes.history(route.id(),1,0).stream().findFirst().orElse(null);Object status=null;
            if(generation!=null){
                if(!generation.producer().equals(new RouteGeneration.Actor(principal.sessionId(),principal.epoch())))
                    throw error(409,"STREAM_DEVICE_SCOPE_CHANGED","현재 장치 세션과 고정된 원본 세션을 확인하세요.");
                status=Map.of("id",generation.id().toString(),"number",generation.generation(),"state",generation.state(),"leaseUntil",generation.leaseUntil().toString());
            }
            var row=new TreeMap<String,Object>();row.put("routeId",route.id().toString());row.put("sourcePort",route.sourcePort());
            row.put("consumerTaskId",route.consumerTaskId().toString());row.put("consumerPort",route.consumerPort());row.put("sourceMode",route.sourceMode());
            row.put("mediaType",route.mediaType());row.put("maxPayloadBytes",route.maxPayloadBytes());row.put("generation",status);items.add(row);
        }
        var result=new TreeMap<String,Object>();result.put("apiVersion","edgeai.device-routes/v1");result.put("runId",id.toString());result.put("runState",run.state());
        result.put("deviceId",principal.deviceId().toString());result.put("sessionId",principal.sessionId().toString());result.put("epoch",principal.epoch());
        result.put("items",items);result.put("limit",limit);result.put("offset",offset);result.put("nextOffset",offset+items.size()<selected.size()?offset+items.size():null);
        return result;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<StreamRouteResponse> list(UUID id,int limit,int offset){
        WorkflowService.page(limit,offset);var run=executions.run(id,false).orElseThrow(()->error(404,"RUN_NOT_FOUND","실행 요청이 없습니다."));
        var components=managed(id)?plan(run).components():Map.<UUID,UUID>of();var pins=new HashMap<UUID,StreamRunRepository.DeviceBinding>();
        store.bindings(id).forEach(b->pins.put(b.routeId(),b));
        return routes.forRun(id,limit+1,offset).stream().map(r->StreamRouteResponse.from(r,components.get(r.consumerTaskId()),pins.get(r.id()),
            routes.history(r.id(),1,0).stream().findFirst().orElse(null))).toList();
    }
    /** Called before inserting a new Run; locks are retained by the creation transaction. */
    @Transactional
    public Map<UUID,DeviceSession> pin(List<StreamRunInput> inputs,String mode,boolean offload){
        if(!enabled)throw error(501,"STREAM_NOT_IMPLEMENTED","공개 STREAM 실행 설정과 운영 연결이 아직 활성화되지 않았습니다.");
        if(!Set.of("AUTO","NODE").contains(mode))throw error(409,"STREAM_EXECUTION_POLICY_UNSUPPORTED","현재 공개 STREAM은 AUTO 또는 NODE 실행을 사용하세요.");
        if(offload)throw error(409,"STREAM_RECOVERY_UNSUPPORTED","현재 STREAM 자동 실행 위치 전환 정책은 지원하지 않습니다. 장애 재시도 정책을 사용하세요.");
        var result=new HashMap<UUID,DeviceSession>();
        for(var id:inputs.stream().map(StreamRunInput::deviceId).distinct().sorted().toList()){
            var device=devices.find(id,true).orElseThrow(()->error(404,"DEVICE_NOT_FOUND","스트림 원본 장치가 없습니다."));
            var session=devices.activeSession(id).orElseThrow(()->error(409,"STREAM_SOURCE_INACTIVE","원본 장치의 활성 세션이 필요합니다."));
            if(device.state()!=Device.State.ACTIVE || session.epoch()!=device.sessionEpoch())throw error(409,"STREAM_SOURCE_INACTIVE","현재 활성 원본 장치가 필요합니다.");
            result.put(id,session);
        }
        return Map.copyOf(result);
    }
    @Transactional
    public void configure(WorkflowRun run,String namespace,List<StreamRunInput> inputs,Map<UUID,DeviceSession> sessions){
        var tasks=executions.tasks(run.id());var byKey=new HashMap<String,Task>();tasks.forEach(t->byKey.put(t.key(),t));
        var specs=specs(run,tasks);var dag=dag(run);
        store.create(new StreamRunRepository.Configuration(run.id(),namespace,brokerDigest,leaseSeconds,clock.instant()));
        for(var edge:dag.dependencies())if(edge.mode()==Dag.Mode.STREAM){
            var source=byKey.get(edge.fromTask());var target=byKey.get(edge.toTask());
            var stream=specs.get(source.id()).stream();
            if(stream==null || !stream.outputs().containsKey(edge.fromPort()))throw new IllegalArgumentException("STREAM output missing");
            lifecycle.fromTask(run.id(),source.id(),edge.fromPort(),target.id(),edge.toPort(),(int)stream.outputs().get(edge.fromPort()).maxPayloadBytes());
        }
        for(var input:inputs){
            var target=byKey.get(input.toTask());if(target==null)throw new IllegalArgumentException("Unknown stream input task");
            var stream=specs.get(target.id()).stream();if(stream==null || !stream.inputs().containsKey(input.toPort()))throw new IllegalArgumentException("Unknown stream input port");
            var route=lifecycle.fromDevice(run.id(),input.deviceId(),input.sourcePort(),target.id(),input.toPort(),input.maxPayloadBytes());
            var session=Objects.requireNonNull(sessions.get(input.deviceId()));
            store.bind(new StreamRunRepository.DeviceBinding(route.id(),run.id(),input.deviceId(),session.id(),session.epoch()));
        }
        var plan=plan(run);
        completions.freeze(run.id(),JSON.digest("edgeai-stream-membership-v1",plan.routes().stream().map(r->r.id().toString()).sorted().toList()),clock.instant());
        releaseReady(run.id());
    }
    /** Every BATCH predecessor of every member must be sealed before releasing the whole group. */
    @Transactional
    public boolean releaseReady(UUID runId){
        if(!managed(runId))return false;
        var run=executions.run(runId,true).orElseThrow();if(!Set.of("PENDING","RUNNING").contains(run.state()))return true;
        var plan=plan(run);var tasks=executions.tasks(runId);var byKey=new HashMap<String,Task>();tasks.forEach(t->byKey.put(t.key(),t));
        var seen=new HashSet<UUID>();
        for(var task:tasks){
            if(!seen.add(plan.components().get(task.id())))continue;
            var members=plan.componentTasks(task.id());
            if(tasks.stream().filter(t->members.contains(t.id())).anyMatch(t->!t.state().equals("WAITING")))continue;
            boolean ready=dag(run).dependencies().stream().filter(e->e.mode()==Dag.Mode.BATCH && members.contains(byKey.get(e.toTask()).id()))
                .allMatch(e->byKey.get(e.fromTask()).state().equals("SUCCEEDED") && runtimes.result(byKey.get(e.fromTask()).id()).isPresent());
            if(ready)store.release(runId,members,clock.instant());
        }
        return true;
    }
    /** Failed/cancelled stream peers and their downstream groups cannot remain waiting for END. */
    public Set<String> affected(WorkflowRun run,String key){
        var dag=dag(run);var result=new HashSet<String>();result.add(key);result.addAll(dag.descendants(key));
        if(!managed(run.id()))return result;
        var plan=plan(run);var tasks=executions.tasks(run.id());boolean changed;
        do{int before=result.size();var groups=new HashSet<UUID>();
            tasks.stream().filter(t->result.contains(t.key())).forEach(t->groups.add(plan.components().get(t.id())));
            tasks.stream().filter(t->groups.contains(plan.components().get(t.id()))).forEach(t->result.add(t.key()));
            for(var member:List.copyOf(result))result.addAll(dag.descendants(member));changed=result.size()!=before;
        }while(changed);
        return Set.copyOf(result);
    }
    public record Failure(UUID attemptId,String reason){}
    /** Prepare each group's generations only once all current producers have claimed. */
    @Transactional
    public List<Failure> prepare(UUID runId){
        var bindings=store.bindings(runId);
        bindings.stream().map(StreamRunRepository.DeviceBinding::deviceId).distinct().sorted().forEach(id->devices.find(id,true).orElseThrow());
        var run=executions.run(runId,true).orElseThrow();var config=store.find(runId).orElseThrow();
        if(!Set.of("PENDING","RUNNING").contains(run.state()))return List.of();
        var plan=plan(run);var tasks=executions.tasks(runId);var seen=new HashSet<UUID>();var failures=new ArrayList<Failure>();
        var pins=new HashMap<UUID,StreamRunRepository.DeviceBinding>();bindings.forEach(b->pins.put(b.routeId(),b));
        for(var task:tasks){
            if(!seen.add(plan.components().get(task.id())))continue;
            var group=plan.componentTasks(task.id());var connected=plan.componentRoutes(task.id());if(connected.isEmpty())continue;
            var members=tasks.stream().filter(t->group.contains(t.id())).toList();var attempts=new HashMap<UUID,TaskAttempt>();
            for(var member:members){var history=executions.attempts(member.id());if(!history.isEmpty())attempts.put(member.id(),history.getFirst());}
            if(attempts.size()!=members.size())continue;
            if(attempts.values().stream().allMatch(a->completions.granted(a.id()).isPresent()))continue;
            if(members.stream().anyMatch(t->!t.state().equals("RUNNING")))continue;
            var first=attempts.values().stream().filter(a->Set.of("DISPATCHING","RUNNING").contains(a.state())).findFirst();if(first.isEmpty())continue;
            boolean changed=!config.brokerDigest().equals(brokerDigest);
            for(var route:connected)if(route.deviceSource()){
                var pin=pins.get(route.id());var d=devices.find(route.sourceDeviceId(),false).orElseThrow();var session=devices.activeSession(d.id());
                changed |= pin==null || d.state()!=Device.State.ACTIVE || session.isEmpty() || !session.get().id().equals(pin.sessionId()) || session.get().epoch()!=pin.epoch();
            }
            if(changed){failures.add(new Failure(first.get().id(),"INPUT_INVALID"));continue;}
            if(attempts.values().stream().anyMatch(a->!a.state().equals("RUNNING") || runtimes.byAttempt(a.id()).filter(r->
                r.desiredState().equals("RUNNING") && r.producerPodUid()!=null && r.expiresAt()!=null && clock.instant().isBefore(r.expiresAt())).isEmpty()))continue;
            boolean stale=false;
            for(var route:connected){var open=routes.open(route.id());
                if(open.isPresent()){var g=open.get();var producer=producer(route,attempts,pins);var consumer=actor(attempts.get(route.consumerTaskId()));
                    stale |= g.fencedAt()!=null || !clock.instant().isBefore(g.leaseUntil()) || !g.producer().equals(producer) || !g.consumer().equals(consumer);
                }else{
                    var old=routes.history(route.id(),1,0).stream().findFirst().orElse(null);
                    stale |= old!=null && !retrySuccessor(route,old,attempts,pins);
                }
            }
            if(stale){failures.add(new Failure(first.get().id(),"RUNTIME_LOST"));continue;}
            for(var route:connected)if(routes.open(route.id()).isEmpty())lifecycle.prepare(route.id(),UUID.randomUUID(),
                producer(route,attempts,pins),actor(attempts.get(route.consumerTaskId())),config.brokerDigest(),config.leaseSeconds());
        }
        return List.copyOf(failures);
    }
    private boolean retrySuccessor(DataRoute route,RouteGeneration old,Map<UUID,TaskAttempt> attempts,Map<UUID,StreamRunRepository.DeviceBinding> pins){
        if(old.closedAt()==null || !retrySuccessor(attempts.get(route.consumerTaskId()),old.consumer()))return false;
        return route.deviceSource()?old.producer().equals(producer(route,attempts,pins)):retrySuccessor(attempts.get(route.sourceTaskId()),old.producer());
    }
    private boolean retrySuccessor(TaskAttempt current,RouteGeneration.Actor old){
        return current.cause().equals("RETRY") && current.epoch()>old.epoch() && !current.id().equals(old.id())
            && runtimes.byAttempt(old.id()).filter(r->r.desiredState().equals("STOPPED") && r.observedState().equals("TERMINATED")).isPresent();
    }
    private static RouteGeneration.Actor actor(TaskAttempt a){return new RouteGeneration.Actor(a.id(),a.epoch());}
    private static RouteGeneration.Actor producer(DataRoute route,Map<UUID,TaskAttempt> attempts,Map<UUID,StreamRunRepository.DeviceBinding> pins){
        if(!route.deviceSource())return actor(attempts.get(route.sourceTaskId()));
        var pin=pins.get(route.id());return new RouteGeneration.Actor(pin.sessionId(),pin.epoch());
    }
    public StreamRunPlan plan(WorkflowRun run){var tasks=executions.tasks(run.id());
        return StreamRunPlan.compile(tasks,specs(run,tasks),dag(run),routes.forRun(run.id(),StreamRunPlan.MAX_ROUTES+1,0))
            .orElseThrow(()->new IllegalArgumentException("Every SERVICE stream port requires an exact Run binding"));}
    private Map<UUID,ServiceExecutionSpec> specs(WorkflowRun run,List<Task> tasks){
        var definitions=new HashMap<UUID,UUID>();workflows.definitions(run.workflowVersionId()).forEach(d->definitions.put(d.id(),d.serviceProfileVersionId()));
        var result=new HashMap<UUID,ServiceExecutionSpec>();tasks.forEach(t->result.put(t.id(),ServiceExecutionInput.parseSpec(profiles.find(definitions.get(t.definitionId())).orElseThrow().specJson())));return result;
    }
    private Dag dag(WorkflowRun run){return storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson());}
}
