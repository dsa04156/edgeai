package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

/** Authenticated shared-engine control plane. No per-VD Pod or arbitrary SQL/sink requests. */
@Service
public class ProcessingEngineService {
    private final ContinuousRepository executions;
    private final VirtualDeviceRepository vds;
    private final ProfileRepository profiles;
    private final ManagedDeviceService sensors;
    private final Clock clock;
    private final ProcessingHistoryRepository history;
    @org.springframework.beans.factory.annotation.Autowired private ProcessingHistoryDelivery delivery;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowSourceFeedService feeds;
    private final Map<String,Object> overrides;
    private Instant catalogAt=Instant.EPOCH;
    private List<Map<String,Object>> inputs=List.of();
    public ProcessingEngineService(ContinuousRepository executions,VirtualDeviceRepository vds,ProfileRepository profiles,ManagedDeviceService sensors,Clock clock,ProcessingHistoryRepository history,
        @Value("${edgeai.continuous.broker-node-overrides:{}}") String overrides) {
        this.executions=executions;this.vds=vds;this.profiles=profiles;this.sensors=sensors;this.clock=clock;this.history=history;this.overrides=map(JSON.decode(overrides));
    }
    private synchronized List<Map<String,Object>> catalog() {
        if(!catalogAt.plusSeconds(5).isAfter(clock.instant())){
            var current=new ArrayList<Map<String,Object>>();
            for(var source:sensors.observationCatalog(overrides)){
                var route=new TreeMap<>(source);
                if(route.containsKey("type"))try{ContinuousRuleCompiler.inputType((String)route.get("type"));}catch(IllegalArgumentException e){route.put("reason","TYPE_UNSUPPORTED");}
                current.add(route);
            }
            inputs=List.copyOf(current);catalogAt=clock.instant();
        }
        return inputs;
    }
    public synchronized void invalidateCatalog(){catalogAt=Instant.EPOCH;}
    public List<Map<String,Object>> inputCatalog(){
        var result=new ArrayList<Map<String,Object>>();
        for(var route:catalog())if(route.containsKey("type")){
            try{ContinuousRuleCompiler.inputType((String)route.get("type"));}catch(IllegalArgumentException error){continue;}
            var input=new TreeMap<String,Object>();
            for(var field:List.of("deviceId","deviceUid","deviceModel","namespace","property","type","unit","targetNode"))input.put(field,route.get(field));
            result.add(input);
        }
        result.sort(Comparator.comparing(input->(String)input.get("deviceId")));return result;
    }
    private static long sequence(Object value) {
        try{long n=new java.math.BigDecimal(value.toString()).longValueExact();if(n<1 || n>9007199254740991L)throw new IllegalArgumentException();return n;}
        catch(RuntimeException e){throw new IllegalArgumentException("Invalid engine sequence");}
    }
    @Transactional
    public Map<String,Object> poll(ProcessingEngineGateway.Identity identity,String body) {
        var request=object(JSON.parse(body,262144),"sessionId","sequence","reports");UUID session=uuid(request.get("sessionId"));long sequence=sequence(request.get("sequence"));
        if(!(request.get("reports") instanceof List<?> reports) || reports.size()>200)throw new IllegalArgumentException("Too many engine reports");
        var routes=catalog();var now=clock.instant();ContinuousRepository.Session lease;
        try{lease=executions.renew("central",identity.podUid(),session,sequence,JSON.digest("edgeai.engine-poll/v1",request),now);}
        catch(IllegalArgumentException e){throw conflict("ENGINE_SESSION_CONFLICT","엔진 세션 또는 임대가 변경되었습니다.");}
        for(var raw:reports)report(map(raw),routes,now);
        var desired=new ArrayList<Map<String,Object>>();
        for(var execution:executions.desired()) {
            try{var publicPlan=new TreeMap<>(plan(execution,routes));publicPlan.remove("configuration");publicPlan.remove("profileVersionId");publicPlan.remove("vdRevision");desired.add(publicPlan);}
            catch(IllegalArgumentException | ControlPlaneException e) {
                executions.observe(execution.vdId(),execution.generation(),"ERROR","SOURCE_CONFIGURATION_UNAVAILABLE",now);
                var failed=new TreeMap<String,Object>();failed.put("vdId",execution.vdId().toString());failed.put("generation",execution.generation().toString());failed.put("running",false);failed.put("reason","SOURCE_CONFIGURATION_UNAVAILABLE");failed.put("rules",List.of());failed.put("sources",List.of());desired.add(failed);
            }
        }
        return Map.of("apiVersion","edgeai.processing-plan/v1","engineId","central","enginePodUid",identity.podUid().toString(),"sessionId",session.toString(),"leaseExpiresAt",lease.expiresAt().toString(),"serverTime",now.toString(),"inputs",routes,"executions",desired);
    }
    private Map<String,Object> plan(ContinuousRepository.Execution execution,List<Map<String,Object>> routes) {
        var result=new TreeMap<String,Object>();result.put("vdId",execution.vdId().toString());result.put("generation",execution.generation().toString());result.put("running",execution.running());
        if(!execution.running()){result.put("rules",List.of());result.put("sources",List.of());return result;}
        var vd=vds.find(execution.vdId(),false).orElseThrow();
        var config=map(JSON.decode(VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson()).continuousJson()));
        var sources=new ArrayList<Map<String,Object>>();var types=new TreeMap<String,ProcessingExpression.Type>();
        for(var binding:vds.activeSources(vd.id()).stream().sorted(Comparator.comparing(VDSourceBinding::sourceKey)).toList()) {
            var route=routes.stream().filter(r->binding.managedDeviceId().equals(r.get("deviceId")) && binding.deviceUid().toString().equals(r.get("deviceUid")) && binding.deviceModel().equals(r.get("deviceModel")) && binding.properties().equals(List.of(Objects.toString(r.get("property"),"")))).findFirst()
                .orElseThrow(()->conflict("VD_SOURCE_INCOMPATIBLE","센서 UID·규격 또는 출력 경로가 변경되었습니다."));
            var source=new TreeMap<>(route);source.put("alias",binding.sourceKey());source.put("column","x"+sources.size());source.put("maxAgeSeconds",binding.maxAgeSeconds());sources.add(source);
            types.put(binding.sourceKey(),ContinuousRuleCompiler.inputType((String)route.get("type")));
        }
        result.put("sources",sources);result.put("rules",ContinuousRuleCompiler.compile(vd.id(),execution.generation(),config,types));result.put("intervalSeconds",config.get("intervalSeconds"));result.put("retentionDays",config.get("retentionDays"));result.put("configuration",config);result.put("profileVersionId",vd.profileVersionId().toString());result.put("vdRevision",vd.revision());return result;
    }
    private void report(Map<String,Object> report,List<Map<String,Object>> routes,Instant now) {
        object(report,"vdId","generation","state","reason","ruleIds");UUID id=uuid(report.get("vdId")),generation=uuid(report.get("generation"));
        var execution=executions.find(id);if(execution.isEmpty() || !execution.get().generation().equals(generation))return;
        String state=text(report.get("state"),32),reason=text(report.get("reason"),100);
        if(!Set.of("RUNNING","WAITING_INPUT","STOPPED","ERROR").contains(state) || !Set.of("HEALTHY","INPUT_MISSING","INPUT_STALE","WINDOW_WARMING","RULE_UNAVAILABLE","SOURCE_CONFIGURATION_UNAVAILABLE","STOPPED","ENGINE_BACKPRESSURE","EVALUATION_TIMEOUT").contains(reason))throw new IllegalArgumentException("Invalid engine observation");
        if(!(report.get("ruleIds") instanceof List<?> ids) || ids.size()>16)throw new IllegalArgumentException("Invalid rule observations");
        if(!execution.get().running() && !state.equals("STOPPED") || execution.get().running() && state.equals("STOPPED"))return;
        if(state.equals("STOPPED") && !ids.isEmpty())throw new IllegalArgumentException("Stopped VD still owns rules");
        if(Set.of("RUNNING","WAITING_INPUT").contains(state)) {
            Map<String,Object> current;
            try{current=plan(execution.get(),routes);}catch(ControlPlaneException | IllegalArgumentException e){executions.observe(id,generation,"ERROR","SOURCE_CONFIGURATION_UNAVAILABLE",now);return;}
            var expected=((List<ContinuousRuleCompiler.Rule>)current.get("rules")).stream().map(ContinuousRuleCompiler.Rule::id).collect(java.util.stream.Collectors.toSet());
            if(ids.size()!=expected.size() || !new HashSet<>(ids).equals(expected))throw new IllegalArgumentException("Engine has not applied every owned rule");
        }
        executions.observe(id,generation,state,reason,now);
        executions.gap(id,generation,"INPUT_UNAVAILABLE",state.equals("WAITING_INPUT"),now);
        if(state.equals("RUNNING") || state.equals("STOPPED"))for(var gap:List.of("INPUT_UNAVAILABLE","WINDOW_RESET","ENGINE_UNAVAILABLE"))executions.gap(id,generation,gap,false,now);
        if(state.equals("ERROR"))executions.gap(id,generation,"ENGINE_UNAVAILABLE",true,now);
    }
    private ContinuousRepository.Session lease(ProcessingEngineGateway.Identity identity,UUID session) {
        var lease=executions.sessionLocked("central").orElseThrow(()->conflict("ENGINE_LEASE_EXPIRED","엔진 임대가 없습니다."));
        if(!lease.sessionId().equals(session) || !lease.podUid().equals(identity.podUid()) || !lease.expiresAt().isAfter(clock.instant()))throw conflict("ENGINE_LEASE_EXPIRED","유효한 엔진 임대가 필요합니다.");return lease;
    }
    @Transactional
    public Map<String,Object> reset(ProcessingEngineGateway.Identity identity,String body) {
        var request=parse(body,"sessionId","vdId","generation","reason");lease(identity,uuid(request.get("sessionId")));
        UUID id=uuid(request.get("vdId")),generation=uuid(request.get("generation"));String reason=text(request.get("reason"),100);
        if(!Set.of("INPUT_STALE","INPUT_MISSING","INPUT_CHANGED","ENGINE_BACKPRESSURE","EVALUATION_TIMEOUT","TICK_GAP","RULE_UNAVAILABLE").contains(reason))throw new IllegalArgumentException("Invalid reset reason");
        var execution=executions.find(id).orElseThrow(()->conflict("GENERATION_CHANGED","실행 세대가 없습니다."));
        if(!execution.generation().equals(generation))return Map.of("generation",execution.generation().toString());
        if(!execution.running())throw conflict("GENERATION_CHANGED","실행이 중지됐습니다.");
        var next=executions.reset(id,generation,reason,clock.instant());executions.gap(id,next.generation(),"WINDOW_RESET",true,clock.instant());return Map.of("generation",next.generation().toString());
    }
    @Transactional
    public Map<String,Object> results(ProcessingEngineGateway.Identity identity,String body) {
        var request=object(JSON.parse(body,262144),"sessionId","results");lease(identity,uuid(request.get("sessionId")));
        if(!(request.get("results") instanceof List<?> values) || values.isEmpty() || values.size()>64)throw new IllegalArgumentException("One to sixty-four results required");
        var ids=new TreeSet<UUID>();for(var raw:values)ids.add(uuid(map(raw).get("vdId")));
        for(var id:ids)vds.find(id,true).orElseThrow(()->conflict("GENERATION_CHANGED","등록된 실행이 없습니다."));
        var routes=catalog();var now=clock.instant();var samples=new ArrayList<ProcessingHistoryRepository.Sample>();var seen=new HashSet<String>();int accepted=0;
        var currentExecutions=new HashMap<UUID,ContinuousRepository.Execution>();
        var currentPlans=new HashMap<UUID,Map<String,Object>>();
        var fresh=new ArrayList<ContinuousRepository.Result>();
        for(var raw:values) {
            var value=object(raw,"vdId","outputId","generation","sequence","evaluationId","evaluationAt","value","sources");
            UUID id=uuid(value.get("vdId")),generation=uuid(value.get("generation"));String output=text(value.get("outputId"),63);long sequence=sequence(value.get("sequence"));
            var execution=currentExecutions.computeIfAbsent(id,key->executions.find(key).orElseThrow(()->conflict("GENERATION_CHANGED","실행 세대가 없습니다.")));
            if(!execution.running() || !execution.generation().equals(generation))throw conflict("GENERATION_CHANGED","실행 세대가 변경되었습니다.");
            var plan=currentPlans.computeIfAbsent(id,key->plan(execution,routes));int interval=((Number)plan.get("intervalSeconds")).intValue();Instant at=Instant.parse(text(value.get("evaluationAt"),40));
            if(at.toEpochMilli()!=Math.multiplyExact(sequence,interval*1000L) || at.isAfter(now.plusMillis(1000)) || at.isBefore(now.minusSeconds(15)) || !(generation+":"+sequence).equals(value.get("evaluationId")))throw new IllegalArgumentException("Invalid evaluation slot or identity");
            var rule=((List<ContinuousRuleCompiler.Rule>)plan.get("rules")).stream().filter(r->r.outputId().equals(output)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown output"));
            Object calculated=value.get("value");
            if(rule.type().equals("boolean")?!(calculated instanceof Boolean):!(calculated instanceof Number n) || !Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid computed value type");
            if(!(value.get("sources") instanceof List<?> proofs) || proofs.size()!=((List<?>)plan.get("sources")).size())throw new IllegalArgumentException("Incomplete input provenance");
            var aliases=new HashSet<String>();
            for(var proof:proofs) {
                var p=object(proof,"alias","deviceUid","sourceGeneration","configurationSha256","observedAt");String alias=text(p.get("alias"),32);
                if(!aliases.add(alias))throw new IllegalArgumentException("Duplicate input provenance");
                var source=((List<Map<String,Object>>)plan.get("sources")).stream().filter(s->alias.equals(s.get("alias"))).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown input provenance"));
                for(var key:List.of("deviceUid","sourceGeneration","configurationSha256"))if(!Objects.equals(p.get(key),source.get(key)))throw conflict("SOURCE_IDENTITY_CHANGED","센서 UID 또는 적용 설정이 변경되었습니다.");
                long observed=sequence(p.get("observedAt"));
                if(observed>at.toEpochMilli()+1000 || observed<at.toEpochMilli()-((Number)source.get("maxAgeSeconds")).longValue()*1000)throw new IllegalArgumentException("Stale or future input provenance");
            }
            String unique=id+":"+output+":"+generation+":"+sequence;if(!seen.add(unique))throw new IllegalArgumentException("Duplicate result in batch");
            var document=new TreeMap<String,Object>(map(value));document.put("unit",rule.unit());document.put("type",rule.type());document.put("historyStored",false);
            boolean retain=ResultStoragePolicy.stores(map(plan.get("configuration")),output,calculated);
            document.put("storageStatus",retain?"PENDING":"SKIPPED");document.put("profileVersionId",plan.get("profileVersionId"));document.put("vdRevision",plan.get("vdRevision"));
            var result=new ContinuousRepository.Result(id,output,generation,(String)value.get("evaluationId"),sequence,at,JSON.canonical(document));
            if(executions.accept(result,now)){accepted++;executions.appendLatest(result);fresh.add(result);if(retain)samples.add(new ProcessingHistoryRepository.Sample(result,((Number)plan.get("retentionDays")).intValue(),rule.type(),rule.unit(),calculated,JSON.canonical(proofs)));}
        }
        // Same transaction as the receipt: an engine retry after rollback re-appends, an accepted duplicate never does.
        feeds.acceptResults(fresh);
        boolean stored=false;
        delivery.reserve(samples.size());
        for(var sample:samples)delivery.enqueue(sample);
        stored=delivery.flush();
        if(!samples.isEmpty()) {
            for(var sample:samples) {
                var r=sample.result();executions.gap(r.vdId(),r.generation(),"HISTORY_UNAVAILABLE",!stored,stored?now:r.evaluationAt());
            }
        }
        return Map.of("accepted",values.size(),"newResults",accepted,"historyStored",stored,"replay",false);
    }
    private static ControlPlaneException conflict(String code,String message){return new ControlPlaneException(409,code,message);}
}
