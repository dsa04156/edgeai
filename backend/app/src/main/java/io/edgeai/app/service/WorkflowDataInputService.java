package io.edgeai.app.service;

import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.VirtualDevice;
import io.edgeai.domain.workflow.*;
import java.util.*;
import java.time.*;
import io.edgeai.app.config.RunnerPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.support.DeviceProfileCompiler.map;
import static io.edgeai.app.service.WorkflowService.error;

/** Pins typed workflow input identity independently of legacy Device sessions. */
@Service
public class WorkflowDataInputService {
    public static final String BINDINGS="workflowSourceBindings";
    private final ManagedDeviceService sensors;
    private final VirtualDeviceRepository vds;
    private final ProfileRepository profiles;
    private final ContinuousRepository continuous;
    private final ContinuousOutputService outputs;
    private final SensorTelemetryRepository archive;
    private final ExecutionRepository executions;
    private final RuntimeLifecycleService lifecycle;
    private final WorkflowSourceFeedService feeds;
    private final SensorArchiveCollector collector;
    private final Clock clock;
    public WorkflowDataInputService(ManagedDeviceService sensors,VirtualDeviceRepository vds,ProfileRepository profiles,ContinuousRepository continuous,
            ContinuousOutputService outputs,SensorTelemetryRepository archive,ExecutionRepository executions,RuntimeLifecycleService lifecycle,WorkflowSourceFeedService feeds,SensorArchiveCollector collector,Clock clock) {
        this.sensors=sensors;this.vds=vds;this.profiles=profiles;this.continuous=continuous;
        this.outputs=outputs;this.archive=archive;this.executions=executions;this.lifecycle=lifecycle;this.feeds=feeds;this.collector=collector;this.clock=clock;
    }
    public Map<String,Object> catalog() {
        var items=new ArrayList<Map<String,Object>>();
        for(var sensor:sensors.observationCatalog(Map.of())) {
            if(sensor.containsKey("reason") || !sensor.containsKey("sourceGeneration"))continue;
            items.add(Map.of("selection",Map.of("kind","SENSOR","deviceId",sensor.get("deviceId"),"property",sensor.get("property")),
                "label",sensor.get("deviceId")+" · "+sensor.get("property"),"unit",sensor.get("unit"),"type",sensor.get("type"),"available",true));
        }
        var devices=vds.list(501,0);
        for(var vd:devices.stream().limit(500).toList()) {
            if(vd.state()==VirtualDevice.State.RELEASED)continue;
            var config=VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson());
            if(!config.continuous())continue;
            var result=outputs.outputs(vd.id());
            for(var raw:(List<?>)result.get("items")) {
                var output=map(raw);var latest=output.get("latest") instanceof Map<?,?> ? map(output.get("latest")) : Map.<String,Object>of();
                items.add(Map.of("selection",Map.of("kind","CONTINUOUS_VD","vdId",vd.id().toString(),"outputId",output.get("outputId")),
                    "label",vd.displayName()+" · "+output.get("outputId"),"unit",output.getOrDefault("unit",""),"type",latest.getOrDefault("type",""),
                    "available","VALID".equals(output.get("quality"))));
            }
        }
        return Map.of("items",items,"truncated",devices.size()>500,"samplingIntervalSeconds",1,"asOf",clock.instant().toString());
    }
    public Map<String,Object> pin(List<TaskDefinition> definitions,Dag dag,Map<?,?> parameters,boolean runtimeEnabled) {
        var result=new TreeMap<String,Object>();parameters.forEach((k,v)->result.put((String)k,v));
        var bindings=new TreeMap<String,Object>();
        for(var definition:definitions) {
            String document=profiles.find(definition.serviceProfileVersionId()).orElseThrow().specJson();
            var raw=map(JSON.decode(document));
            if(!(raw.get("stream") instanceof Map<?,?> stream) || !"WORKFLOW_DATA".equals(stream.get("source")))continue;
            var spec=ServiceExecutionInput.parseSpec(document);
            if(!runtimeEnabled)throw error(503,"RUNTIME_DISABLED","실시간 입력 실행에는 실행 worker와 스트림 설정이 필요합니다.");
            if(!spec.inputs().isEmpty() || dag.dependencies().stream().anyMatch(e->e.toTask().equals(definition.key()))
                    || dag.dependencies().stream().noneMatch(e->e.fromTask().equals(definition.key()) && e.mode()==Dag.Mode.STREAM))
                throw error(400,"WORKFLOW_SOURCE_INVALID","데이터 입력은 입력 연결 없이 다음 처리 작업에 연결해야 합니다.");
            var intent=WorkflowSourceInput.parse(map(JSON.decode(definition.parametersJson())).get("dataSource"));
            var binding=new TreeMap<>(intent);
            if(intent.get("kind").equals("SENSOR")) {
                var route=sensors.workflowObservation((String)intent.get("deviceId"));
                if(!intent.get("property").equals(route.get("property")))throw error(409,"WORKFLOW_SOURCE_INVALID","선택한 센서 측정 항목이 없습니다.");
                for(String key:List.of("deviceUid","sourceGeneration","configurationSha256","type","unit"))binding.put(key,route.get(key));
                uuid(binding.get("deviceUid"));uuid(binding.get("sourceGeneration"));
                if(!(binding.get("configurationSha256") instanceof String hash) || !hash.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Qualified sensor identity required");
                if(WorkflowSourceFeedService.all(binding) && "string".equals(binding.get("type")))
                    throw error(409,"WORKFLOW_SOURCE_UNSUPPORTED","문자열 측정 항목은 전체 관측 전달을 지원하지 않습니다.");
                if(WorkflowSourceFeedService.all(binding) && !collector.collecting())
                    throw error(409,"WORKFLOW_SOURCE_UNAVAILABLE","센서 원본 수집이 활성화되지 않아 전체 관측을 전달할 수 없습니다.");
            } else {
                UUID id=uuid(intent.get("vdId"));var vd=vds.find(id,false).orElseThrow(()->error(404,"VD_NOT_FOUND","가상디바이스가 없습니다."));
                if(vd.state()==VirtualDevice.State.RELEASED)throw error(409,"WORKFLOW_SOURCE_UNAVAILABLE","삭제된 가상디바이스는 입력으로 사용할 수 없습니다.");
                var config=VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson());
                if(!config.continuous())throw error(409,"CONTINUOUS_MODE_REQUIRED","연속 계산 결과를 입력으로 선택하세요.");
                var output=((List<?>)map(JSON.decode(config.continuousJson())).get("outputs")).stream().map(DeviceProfileCompiler::map)
                    .filter(o->intent.get("outputId").equals(o.get("name"))).findFirst().orElseThrow(()->error(404,"VD_OUTPUT_NOT_FOUND","계산 출력이 없습니다."));
                var execution=continuous.find(id).filter(e->e.running() && e.state().equals("RUNNING"))
                    .orElseThrow(()->error(409,"WORKFLOW_SOURCE_UNAVAILABLE","입력으로 사용할 가상디바이스를 먼저 시작하세요."));
                binding.put("revision",vd.revision());binding.put("profileVersionId",vd.profileVersionId().toString());
                var live=((List<?>)outputs.outputs(id).get("items")).stream().map(DeviceProfileCompiler::map)
                    .filter(o->intent.get("outputId").equals(o.get("outputId")) && "VALID".equals(o.get("quality"))).findFirst()
                    .orElseThrow(()->error(409,"WORKFLOW_SOURCE_UNAVAILABLE","가상디바이스의 현재 계산 결과를 확인한 뒤 실행하세요."));
                var latest=map(live.get("latest"));
                binding.put("generation",execution.generation().toString());binding.put("type",latest.get("type"));binding.put("unit",output.getOrDefault("unit",""));
            }
            bindings.put(definition.key(),binding);
        }
        if(!bindings.isEmpty())result.put(BINDINGS,bindings);
        JSON.boundedCanonical(result,65536);return result;
    }
    @Transactional
    public Map<String,Object> drain(UUID runId,String body) {
        parse(body);var run=executions.run(runId,true).orElseThrow(()->error(404,"RUN_NOT_FOUND","실행이 없습니다."));
        if(!map(JSON.decode(run.parametersJson())).containsKey(BINDINGS))throw error(409,"WORKFLOW_SOURCE_REQUIRED","데이터 입력이 있는 실행만 정상 종료할 수 있습니다.");
        if(!Set.of("PENDING","RUNNING").contains(run.state()))throw error(409,"RUN_TERMINAL","실행이 이미 종료 중이거나 완료됐습니다.");
        // ALL_OBSERVATIONS consumers deliver every position up to the committed head frozen here before END.
        executions.requestInputDrain(runId,clock.instant());feeds.drain(runId);return Map.of("runId",runId.toString(),"state","DRAINING");
    }
    /** Called in the run-creation transaction after tasks exist; pins each ALL_OBSERVATIONS start boundary with the run. */
    public void subscribe(UUID runId,Map<?,?> parameters) {
        if(parameters.get(BINDINGS) instanceof Map<?,?> bindings)feeds.subscribe(runId,bindings);
    }
    public Map<String,Object> input(RunnerPrincipal principal,String body) {
        var fields=new ArrayList<String>();
        if(JSON.parse(body,262144) instanceof Map<?,?> raw)for(String optional:List.of("cursor","maxItems"))if(raw.containsKey(optional))fields.add(optional);
        var request=RunnerInput.parse(body,principal,fields.toArray(String[]::new));
        var assignment=lifecycle.authorize(principal.attemptId(),principal.epoch(),principal.podUid());
        if(assignment.spec().stream()==null || !"WORKFLOW_DATA".equals(assignment.spec().stream().source()))
            throw error(409,"WORKFLOW_SOURCE_REQUIRED","이 작업은 데이터 입력 작업이 아닙니다.");
        var task=executions.task(assignment.runtime().taskId()).orElseThrow();
        var run=executions.run(task.runId(),false).orElseThrow();
        var binding=map(map(JSON.decode(run.parametersJson())).get(BINDINGS));
        var source=map(binding.get(task.key()));
        long limit=request.containsKey("maxItems")?RunnerInput.integer(request.get("maxItems")):64;
        if(limit<1 || limit>256)throw new IllegalArgumentException("maxItems must be between 1 and 256");
        if(WorkflowSourceFeedService.all(source))return all(principal,run.id(),task.key(),source,request.get("cursor"),(int)limit);
        if(request.containsKey("cursor"))throw new IllegalArgumentException("Latest-value inputs do not accept a feed cursor");
        if(executions.inputDrainRequested(run.id()))return Map.of("state","END");
        Map<String,Object> observation;String observedAt;
        if(source.get("kind").equals("SENSOR")) {
            current(source);
            if(!archive.enabled())return waiting();
            var now=clock.instant();Map<String,Object> values;
            try{values=archive.readSensor(uuid(source.get("deviceUid")),(String)source.get("property"),now.minusSeconds(30),now,1,null,true);}
            catch(RuntimeException e){return waiting();}
            var rows=(List<?>)values.get("items");if(rows.isEmpty())return waiting();observation=map(rows.getFirst());
            for(String key:List.of("deviceUid","property","sourceGeneration","configurationSha256","unit","type"))
                if(!Objects.equals(source.get(key),observation.get(key)))return waiting();
            if(!"QUALIFIED".equals(observation.get("quality")))return waiting();
            observedAt=(String)observation.get("observedAt");
        } else {
            UUID id=current(source);
            var result=outputs.outputs(id);
            var selected=((List<?>)result.get("items")).stream().map(DeviceProfileCompiler::map)
                .filter(o->source.get("outputId").equals(o.get("outputId"))).findFirst().orElseThrow(WorkflowDataInputService::sourceChanged);
            if(!"VALID".equals(selected.get("quality")))return waiting();
            observation=map(selected.get("latest"));observedAt=(String)observation.get("evaluationAt");
            if(!source.get("generation").equals(observation.get("generation")))return waiting();
        }
        var at=Instant.parse(observedAt);var now=clock.instant();
        if(at.isAfter(now.plusSeconds(2)) || !at.plusSeconds(30).isAfter(now))return waiting();
        Object value=observation.get("value");
        if(!(value instanceof Number) && !(value instanceof Boolean))return waiting();
        var sample=new TreeMap<String,Object>();sample.put("value",value);sample.put("unit",source.get("unit"));sample.put("type",source.get("type"));
        sample.put("observedAt",observedAt);sample.put("quality","VALID");sample.put("source",source);
        String identity=sampleId(source,observation);
        JSON.boundedCanonical(sample,4096);
        // HTTP reads never extend the producer lease; check authority again after external I/O.
        lifecycle.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        return Map.of("state","DATA","sampleId",identity,"sample",sample);
    }
    private static String sampleId(Map<String,Object> source,Map<String,Object> observation) {
        var cursor=new TreeMap<String,Object>();cursor.put("source",source);
        for(String key:List.of("sessionId","sequence","evaluationId","observedAt","evaluationAt"))if(observation.containsKey(key))cursor.put(key,observation.get(key));
        return JSON.digest("edgeai-workflow-source-sample-v1",cursor).substring(7);
    }
    /** Rejects a changed sensor route or VD registration/generation; returns the VD id for continuous sources. */
    private UUID current(Map<String,Object> source) {
        if(source.get("kind").equals("SENSOR")) {
            var current=sensors.workflowObservation((String)source.get("deviceId"));
            for(String key:List.of("deviceUid","property","sourceGeneration","configurationSha256","unit","type"))
                if(!Objects.equals(source.get(key),current.get(key)))throw sourceChanged();
            return null;
        }
        UUID id=uuid(source.get("vdId"));var vd=vds.find(id,false).orElseThrow(WorkflowDataInputService::sourceChanged);
        var generation=continuous.find(id).orElseThrow(WorkflowDataInputService::sourceChanged);
        if(vd.state()==VirtualDevice.State.RELEASED || vd.revision()!=RunnerInput.integer(source.get("revision"))
                || !vd.profileVersionId().toString().equals(source.get("profileVersionId"))
                || !generation.generation().toString().equals(source.get("generation")))throw sourceChanged();
        return id;
    }
    /**
     * ALL_OBSERVATIONS: every feed position after the presented cursor, in acceptance order. The cursor names this
     * run/task subscription; a missing cursor means the start boundary pinned with the run.
     */
    private Map<String,Object> all(RunnerPrincipal principal,UUID runId,String taskKey,Map<String,Object> source,Object cursor,int limit) {
        var subscription=feeds.subscription(runId,taskKey).orElseThrow(WorkflowDataInputService::noSubscription);
        current(source);
        if(subscription.state().equals("GAP"))throw gap(subscription.gapReason());
        long after=subscription.startPosition();
        if(cursor!=null) {
            if(!(cursor instanceof String text) || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]{0,15})"))
                throw new IllegalArgumentException("Invalid feed cursor");
            int split=text.indexOf(':');after=Long.parseLong(text.substring(split+1));
            if(!UUID.fromString(text.substring(0,split)).equals(subscription.id()) || after<subscription.startPosition())
                throw error(409,"WORKFLOW_CURSOR_INVALID","이 실행 작업의 입력 위치가 아닙니다.");
        }
        var page=feeds.read(subscription,after,limit).orElseThrow(WorkflowDataInputService::noSubscription);
        // A GAP committed after the subscription read above must still win over DATA or END.
        if(page.state().equals("GAP"))throw gap(page.gapReason());
        // Only the page snapshot's watermark is used; the subscription read above may predate a concurrent drain.
        long bound=page.bound();
        if(after>bound)throw error(409,"WORKFLOW_CURSOR_INVALID","아직 확정되지 않은 입력 위치입니다.");
        if(after<page.floor())throw gap("BACKLOG_EXCEEDED");
        var items=new ArrayList<Map<String,Object>>();long expected=after;
        for(var entry:page.entries()) {
            if(entry.position()!=++expected)throw gap("BACKLOG_EXCEEDED");
            var observation=map(JSON.decode(entry.documentJson()));
            boolean sensor=source.get("kind").equals("SENSOR");
            for(String key:sensor?List.of("deviceUid","property","sourceGeneration","configurationSha256","unit","type"):List.of("generation","unit","type"))
                if(!Objects.equals(source.get(key),observation.get(key)))throw sourceChanged();
            var sample=new TreeMap<String,Object>();sample.put("value",observation.get("value"));sample.put("unit",source.get("unit"));sample.put("type",source.get("type"));
            sample.put("observedAt",observation.get(sensor?"observedAt":"evaluationAt"));sample.put("quality","VALID");sample.put("source",source);sample.put("feedPosition",entry.position());
            JSON.boundedCanonical(sample,4096);
            items.add(Map.of("cursor",subscription.id()+":"+entry.position(),"sampleId",sampleId(source,observation),"sample",sample));
        }
        // Feed reads never extend the producer lease; authority is checked before retention or END changes,
        // and again after them because those writes can wait on other transactions.
        lifecycle.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        feeds.acknowledge(subscription,after);
        boolean end=items.isEmpty() && page.drainPosition()!=null && after>=page.drainPosition();
        if(end)feeds.close(subscription);
        lifecycle.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        if(!items.isEmpty())return Map.of("state","DATA","items",items,"more",expected<bound);
        return end?Map.of("state","END"):waiting();
    }
    private static io.edgeai.app.exception.ControlPlaneException noSubscription(){
        return error(409,"WORKFLOW_SOURCE_UNAVAILABLE","전체 관측 입력의 시작 위치가 없습니다. 새 실행을 시작하세요.");
    }
    private static io.edgeai.app.exception.ControlPlaneException gap(String reason){
        return error(409,"WORKFLOW_SOURCE_GAP","전체 관측 입력의 보관 범위를 넘어 일부 관측을 전달할 수 없습니다("+reason+"). 새 실행을 시작하세요.");
    }
    private static Map<String,Object> waiting(){return Map.of("state","WAITING");}
    private static io.edgeai.app.exception.ControlPlaneException sourceChanged(){return error(409,"WORKFLOW_SOURCE_CHANGED","입력 원본의 식별자나 실행이 변경됐습니다. 새 실행을 시작하세요.");}

}
