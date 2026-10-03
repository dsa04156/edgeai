package io.edgeai.app.service;

import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.repository.DeviceRepository;
import io.edgeai.domain.stream.RouteGeneration.Actor;
import io.edgeai.domain.stream.StreamBrokerGateway.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name={"edgeai.stream.enabled","edgeai.stream.bindings-enabled"},havingValue="true")
public class StreamBindingService {
    private final DeviceRepository devices;private final DeviceStreamTokenService tokens;private final DataRouteService routes;
    private final RuntimeLifecycleService runtimes;private final MosquittoStreamBroker broker;private final StreamConnectionSettings connection;
    private final String digest;private final Clock clock;
    private final StreamExecutionService execution;
    private final StreamRunService runs;
    public StreamBindingService(DeviceRepository devices,DeviceStreamTokenService tokens,DataRouteService routes,RuntimeLifecycleService runtimes,
            MosquittoStreamBroker broker,StreamConnectionSettings connection,@Value("${edgeai.stream.broker-digest}") String digest,Clock clock,StreamExecutionService execution,StreamRunService runs){
        this.devices=devices;this.tokens=tokens;this.routes=routes;this.runtimes=runtimes;this.broker=broker;this.connection=connection;this.digest=digest;this.clock=clock;
        this.execution=execution;
        this.runs=runs;
    }
    public Object execution(RunnerPrincipal principal,String body){return execution.execution(principal,body);}
    public Object complete(RunnerPrincipal principal,String body){return execution.complete(principal,body);}
    public Object deviceComplete(DeviceStreamPrincipal principal,String body){return execution.deviceComplete(principal,body);}
    public Object deviceRoutes(DeviceStreamPrincipal principal,String body){return runs.deviceRoutes(principal,body);}
    @Transactional
    public Object deviceToken(UUID deviceId,UUID sessionId,String body){
        new DeviceInput(body);var device=devices.find(deviceId,true).orElseThrow(()->new ControlPlaneException(404,"DEVICE_NOT_FOUND","장치를 찾을 수 없습니다."));
        var session=devices.activeSession(deviceId).orElseThrow(StreamBindingService::fenced);
        if(device.state()!=Device.State.ACTIVE || !session.id().equals(sessionId) || device.sessionEpoch()!=session.epoch())throw fenced();
        return Map.of("deviceId",deviceId.toString(),"sessionId",sessionId.toString(),"epoch",session.epoch(),"tokenType","Bearer","token",tokens.issue(session));
    }
    @Transactional
    public Object device(DeviceStreamPrincipal principal,String body){
        var input=new DeviceInput(body,"epoch","generationId");if(input.number("epoch")!=principal.epoch())throw fenced();
        var caller=new Principal("DEVICE",new Actor(principal.sessionId(),principal.epoch()));var permission=routes.authorize(input.uuid("generationId"),caller);
        if(!principal.deviceId().equals(permission.route().sourceDeviceId()))throw fenced();
        return response(permission,caller,permission.generation().leaseUntil());
    }
    @Transactional
    public Object runner(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"generationId");var id=WorkflowInput.uuid(input.get("generationId"));
        var caller=new Principal("TASK",new Actor(principal.attemptId(),principal.epoch()));
        // Acquire VD -> Device -> Run -> Route before the runtime's reentrant Run lock.
        var permission=routes.authorize(id,caller);var until=runtimes.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        return response(permission,caller,until.isBefore(permission.generation().leaseUntil())?until:permission.generation().leaseUntil());
    }
    private Object response(Permission permission,Principal caller,Instant until){
        var g=permission.generation();var route=permission.route();var now=clock.instant();
        if(!digest.equals(g.brokerDigest()) || !now.isBefore(until))throw fenced();
        var credential=broker.credential(caller);
        var mqtt=new TreeMap<String,Object>(Map.of("host",connection.host(),"port",connection.port(),"tls",connection.tls(),"caPem",connection.caPem(),
            "clientId",credential.username(),"username",credential.username(),"password",new String(credential.password(),StandardCharsets.US_ASCII),"framesTopic",permission.topic("frames"),"acksTopic",permission.topic("acks")));
        Object producer=route.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",route.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
            :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
        var value=new TreeMap<String,Object>();value.put("apiVersion","edgeai.stream-assignment/v1");value.put("generationId",g.id().toString());value.put("routeId",route.id().toString());
        value.put("runId",route.runId().toString());value.put("generation",g.generation());value.put("direction",caller.equals(permission.producer())?"PRODUCER":"CONSUMER");
        value.put("producer",producer);value.put("consumer",Map.of("attemptId",g.consumer().id().toString(),"epoch",g.consumer().epoch()));
        value.put("mediaType",route.mediaType());value.put("maxPayloadBytes",route.maxPayloadBytes());value.put("serverTime",now.toString());value.put("leaseUntil",until.toString());value.put("mqtt",mqtt);return value;
    }
    @Transactional
    public Object deviceHeartbeat(DeviceStreamPrincipal principal,String body){
        var input=new DeviceInput(body,"epoch","generationId","sequence");if(input.number("epoch")!=principal.epoch())throw fenced();
        var caller=new Principal("DEVICE",new Actor(principal.sessionId(),principal.epoch()));var id=input.uuid("generationId");
        var permission=routes.authorize(id,caller);if(!principal.deviceId().equals(permission.route().sourceDeviceId()))throw fenced();
        var heartbeat=routes.heartbeat(id,caller,input.number("sequence"));
        return Map.of("sequence",heartbeat.sequence(),"assignment",response(heartbeat.permission(),caller,heartbeat.permission().generation().leaseUntil()));
    }
    @Transactional
    public Object runnerHeartbeat(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"generationId","sequence");var id=WorkflowInput.uuid(input.get("generationId"));
        var caller=new Principal("TASK",new Actor(principal.attemptId(),principal.epoch()));
        routes.authorize(id,caller);
        // Pod, offload, runtime and VD lease authority must pass before observing liveness.
        var until=runtimes.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        var heartbeat=routes.heartbeat(id,caller,RunnerInput.integer(input.get("sequence")));var permission=heartbeat.permission();
        return Map.of("sequence",heartbeat.sequence(),"assignment",response(permission,caller,
            until.isBefore(permission.generation().leaseUntil())?until:permission.generation().leaseUntil()));
    }
    private static ControlPlaneException fenced(){return new ControlPlaneException(409,"STREAM_BINDING_FENCED","현재 장치·실행 주체와 스트림 세대·lease를 확인하세요.");}
}
