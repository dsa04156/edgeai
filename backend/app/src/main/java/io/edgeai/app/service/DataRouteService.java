package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.RouteGeneration.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal control state only. Callers separately authenticate Device/Pod and broker receipts. */
@Service
public class DataRouteService {
    private final DataRouteRepository routes;private final ExecutionRepository executions;private final WorkflowRepository workflows;
    private final DeviceRepository devices;private final VirtualDeviceRepository vds;private final ProfileRepository profiles;private final Clock clock;
    private final JsonDocuments json=new JsonDocuments();
    public DataRouteService(DataRouteRepository routes,ExecutionRepository executions,WorkflowRepository workflows,DeviceRepository devices,
            VirtualDeviceRepository vds,ProfileRepository profiles,Clock clock){
        this.routes=routes;this.executions=executions;this.workflows=workflows;this.devices=devices;this.vds=vds;this.profiles=profiles;this.clock=clock;
    }
    @Transactional
    public DataRoute fromTask(UUID runId,UUID producerTask,String producerPort,UUID consumerTask,String consumerPort,int maxPayloadBytes){
        var run=lockRun(runId,null);activeRun(run);
        var source=definition(run,producerTask);var target=definition(run,consumerTask);
        var sourcePort=spec(source.serviceProfileVersionId()).outputs().get(producerPort);
        var input=spec(target.serviceProfileVersionId()).inputs().get(consumerPort);
        if(sourcePort==null || input==null || !sourcePort.mediaType().equals(input.mediaType())
            || maxPayloadBytes>sourcePort.maxBytes() || maxPayloadBytes>input.maxBytes())throw new IllegalArgumentException("Incompatible stream ports");
        var dag=WorkflowInput.storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson());
        if(dag.dependencies().stream().noneMatch(e->e.fromTask().equals(source.key()) && e.toTask().equals(target.key())
                && e.fromPort().equals(producerPort) && e.toPort().equals(consumerPort) && e.mode()==io.edgeai.domain.workflow.Dag.Mode.STREAM))
            throw new IllegalArgumentException("Published STREAM dependency required");
        return save(new DataRoute(UUID.randomUUID(),runId,producerTask,null,source.serviceProfileVersionId(),null,producerPort,
            consumerTask,consumerPort,input.mediaType(),maxPayloadBytes,now()));
    }
    @Transactional
    public DataRoute fromDevice(UUID runId,UUID deviceId,String producerPort,UUID consumerTask,String consumerPort,int maxPayloadBytes){
        var run=lockRun(runId,deviceId);activeRun(run);var device=devices.find(deviceId,false).orElseThrow();
        if(device.state()!=Device.State.ACTIVE)throw conflict("STREAM_SOURCE_INACTIVE");
        var target=definition(run,consumerTask);var input=spec(target.serviceProfileVersionId()).inputs().get(consumerPort);
        if(input==null || maxPayloadBytes>input.maxBytes())throw new IllegalArgumentException("Invalid stream input budget");
        var dag=WorkflowInput.storedDag(workflows.version(run.workflowVersionId()).orElseThrow().dagJson());
        if(dag.dependencies().stream().anyMatch(e->e.toTask().equals(target.key()) && e.toPort().equals(consumerPort)))
            throw new IllegalArgumentException("Task dependency already owns this input");
        return save(new DataRoute(UUID.randomUUID(),runId,null,deviceId,device.profileVersionId(),device.sourceMode().name(),producerPort,
            consumerTask,consumerPort,input.mediaType(),maxPayloadBytes,now()));
    }
    private DataRoute save(DataRoute next){
        var old=routes.input(next.runId(),next.consumerTaskId(),next.consumerPort());
        if(old.isPresent()){
            var r=old.get();var same=new DataRoute(r.id(),next.runId(),next.sourceTaskId(),next.sourceDeviceId(),next.sourceProfileVersionId(),
                next.sourceMode(),next.sourcePort(),next.consumerTaskId(),next.consumerPort(),next.mediaType(),next.maxPayloadBytes(),r.createdAt());
            if(!r.equals(same))throw conflict("STREAM_INPUT_BOUND");return r;
        }
        routes.create(next);return routes.route(next.id(),false).orElseThrow();
    }
    @Transactional
    public RouteGeneration prepare(UUID routeId,UUID requestId,Actor producer,Actor consumer,String brokerDigest,int leaseSeconds){
        Objects.requireNonNull(requestId);Objects.requireNonNull(producer);Objects.requireNonNull(consumer);RouteGeneration.digest(brokerDigest);ttl(leaseSeconds);
        var route=lock(routeId);
        String requestDigest=json.digest("edgeai-stream-prepare-v1",Map.of("routeId",routeId.toString(),"producerId",producer.id().toString(),"producerEpoch",producer.epoch(),
            "consumerId",consumer.id().toString(),"consumerEpoch",consumer.epoch(),"brokerDigest",brokerDigest,"leaseSeconds",leaseSeconds));
        var old=routes.generation(requestId);if(old.isPresent()){
            if(!old.get().routeId().equals(routeId) || !old.get().requestDigest().equals(requestDigest))throw conflict("STREAM_REQUEST_CONFLICT");return old.get();
        }
        if(routes.open(routeId).isPresent())throw conflict("STREAM_REVOCATION_PENDING");
        String reason=invalid(route,producer,consumer,false);if(reason!=null)throw conflict(reason);
        long number=Math.addExact(routes.lastGeneration(routeId),1);var now=now();
        String policy=json.digest("edgeai-stream-policy-v1",Map.of("requestDigest",requestDigest,"generation",number,"generationId",requestId.toString(),
            "framesTopic","edgeai/streams/"+routeId+"/"+number+"/frames","acksTopic","edgeai/streams/"+routeId+"/"+number+"/acks"));
        var next=new RouteGeneration(requestId,routeId,number,producer,consumer,brokerDigest,policy,requestDigest,now,now,now.plusSeconds(leaseSeconds),null,null,null,null);
        routes.prepare(route,next);return get(requestId);
    }
    @Transactional
    public RouteGeneration activate(BrokerReceipt receipt){
        var generation=lockedGeneration(receipt.generationId());verifyReceipt(generation,receipt);
        requireLive(generation,false);if(generation.activatedAt()==null)routes.activate(generation.id(),now(generation));return get(generation.id());
    }
    @Transactional
    public RouteGeneration renew(UUID id,Actor producer,Actor consumer,int leaseSeconds){
        ttl(leaseSeconds);var g=lockedGeneration(id);if(!g.producer().equals(producer) || !g.consumer().equals(consumer))throw conflict("STREAM_ACTOR_CHANGED");
        requireLive(g,true);var now=now(g);var until=now.plusSeconds(leaseSeconds);
        if(until.isAfter(g.leaseUntil()))routes.renew(id,until,now);return get(id);
    }
    @Transactional
    public RouteGeneration fence(UUID id,String reason){
        if(!Set.of("CANCELLED","PRODUCER_CHANGED","CONSUMER_CHANGED","LEASE_EXPIRED","REPLACED","BROKER_CHANGED","FAILED","COMPLETED").contains(reason))
            throw new IllegalArgumentException("Invalid stream fence reason");
        var g=lockedGeneration(id);if(g.fencedAt()==null)routes.fence(id,reason,now(g));return get(id);
    }
    @Transactional
    public RouteGeneration revoked(BrokerReceipt receipt){
        var g=lockedGeneration(receipt.generationId());verifyReceipt(g,receipt);
        if(g.fencedAt()==null)throw conflict("STREAM_FENCE_REQUIRED");if(g.closedAt()==null)routes.closed(g.id(),now(g));return get(g.id());
    }
    @Transactional
    public RouteGeneration reconcile(UUID id){
        var g=lockedGeneration(id);if(g.fencedAt()!=null)return g;
        var route=routes.route(g.routeId(),false).orElseThrow();
        String reason=!now(g).isBefore(g.leaseUntil())?"LEASE_EXPIRED":invalid(route,g.producer(),g.consumer(),false);
        if(reason!=null)routes.fence(id,reason,now(g));return get(id);
    }
    @Transactional
    public boolean accepts(UUID id,Actor producer,Actor consumer){
        var g=lockedGeneration(id);var route=routes.route(g.routeId(),false).orElseThrow();
        return g.producer().equals(producer) && g.consumer().equals(consumer) && g.usableAt(now(g)) && invalid(route,producer,consumer,true)==null;
    }
    /** Caller authentication is separate; locks are retained by a surrounding assignment transaction. */
    @Transactional
    public StreamBrokerGateway.Permission authorize(UUID id,StreamBrokerGateway.Principal caller){
        var g=lockedGeneration(id);requireLive(g,true);var route=routes.route(g.routeId(),false).orElseThrow();
        var permission=new StreamBrokerGateway.Permission(route,g);
        if(!caller.equals(permission.producer()) && !caller.equals(permission.consumer()))throw conflict("STREAM_FOREIGN_ACTOR");
        String reason=invalid(route,g.producer(),g.consumer(),true);if(reason!=null)throw conflict(reason);
        return permission;
    }
    private void requireLive(RouteGeneration g,boolean active){
        if(g.fencedAt()!=null || !now(g).isBefore(g.leaseUntil()) || (active && g.activatedAt()==null))throw conflict("STREAM_GENERATION_FENCED");
        String reason=invalid(routes.route(g.routeId(),false).orElseThrow(),g.producer(),g.consumer(),false);if(reason!=null)throw conflict(reason);
    }
    private String invalid(DataRoute r,Actor producer,Actor consumer,boolean running){
        var run=executions.run(r.runId(),false).orElseThrow();if(!Set.of("PENDING","RUNNING").contains(run.state()))return "CANCELLED";
        if(!currentAttempt(r.consumerTaskId(),consumer,running))return "CONSUMER_CHANGED";
        if(!r.deviceSource())return currentAttempt(r.sourceTaskId(),producer,running)?null:"PRODUCER_CHANGED";
        var device=devices.find(r.sourceDeviceId(),false).orElseThrow();var session=devices.activeSession(device.id());
        return device.state()==Device.State.ACTIVE && device.sessionEpoch()==producer.epoch() && session.isPresent()
            && session.get().id().equals(producer.id()) && session.get().epoch()==producer.epoch()?null:"PRODUCER_CHANGED";
    }
    private boolean currentAttempt(UUID taskId,Actor actor,boolean running){
        var task=executions.task(taskId);var attempt=executions.attempt(actor.id());
        return task.isPresent() && Set.of("READY","RUNNING").contains(task.get().state()) && attempt.isPresent()
            && attempt.get().taskId().equals(taskId) && attempt.get().epoch()==actor.epoch()
            && (running?attempt.get().state().equals("RUNNING"):Set.of("QUEUED","DISPATCHING","RUNNING").contains(attempt.get().state()));
    }
    private WorkflowRun lockRun(UUID id,UUID deviceId){
        var before=executions.run(id,false).orElseThrow(()->new ControlPlaneException(404,"RUN_NOT_FOUND","실행 요청을 찾을 수 없습니다."));
        if(before.vdId()!=null)vds.find(before.vdId(),true).orElseThrow();
        if(deviceId!=null)devices.find(deviceId,true).orElseThrow(()->new ControlPlaneException(404,"DEVICE_NOT_FOUND","원본 장치를 찾을 수 없습니다."));
        return executions.run(id,true).orElseThrow();
    }
    private DataRoute lock(UUID id){var r=routes.route(id,false).orElseThrow(()->new ControlPlaneException(404,"STREAM_ROUTE_NOT_FOUND","데이터 경로가 없습니다."));lockRun(r.runId(),r.sourceDeviceId());return routes.route(id,true).orElseThrow();}
    private RouteGeneration get(UUID id){return routes.generation(id).orElseThrow(()->new ControlPlaneException(404,"STREAM_GENERATION_NOT_FOUND","데이터 경로 실행 세대가 없습니다."));}
    private RouteGeneration lockedGeneration(UUID id){var g=get(id);lock(g.routeId());return get(id);}
    private io.edgeai.domain.workflow.TaskDefinition definition(WorkflowRun run,UUID taskId){
        var task=executions.task(taskId).filter(t->t.runId().equals(run.id())).orElseThrow(()->conflict("STREAM_FOREIGN_TASK"));
        return workflows.definitions(run.workflowVersionId()).stream().filter(d->d.id().equals(task.definitionId())).findFirst().orElseThrow();
    }
    private ServiceExecutionSpec spec(UUID profile){return ServiceExecutionInput.parseSpec(profiles.find(profile).orElseThrow().specJson());}
    private static void activeRun(WorkflowRun run){if(!Set.of("PENDING","RUNNING").contains(run.state()))throw conflict("STREAM_RUN_INACTIVE");}
    private static void verifyReceipt(RouteGeneration g,BrokerReceipt receipt){if(!g.matches(receipt))throw conflict("STREAM_BROKER_MISMATCH");}
    private static void ttl(int seconds){if(seconds<5 || seconds>120)throw new IllegalArgumentException("Stream lease must be 5–120 seconds");}
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    private Instant now(RouteGeneration g){var n=now();return n.isBefore(g.updatedAt())?g.updatedAt():n;}
    private static ControlPlaneException conflict(String code){return new ControlPlaneException(409,code,"데이터 경로의 현재 실행 주체·세대·권한 상태를 확인하세요.");}
}
