package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.VirtualDevice;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

@Service
public class ContinuousOutputService {
    private final VirtualDeviceRepository vds;private final ProfileRepository profiles;
    private final ContinuousRepository executions;private final ProcessingHistoryRepository history;private final Clock clock;
    public ContinuousOutputService(VirtualDeviceRepository vds,ProfileRepository profiles,ContinuousRepository executions,ProcessingHistoryRepository history,Clock clock){this.vds=vds;this.profiles=profiles;this.executions=executions;this.history=history;this.clock=clock;}
    private Map<String,Object> config(UUID id) {
        var vd=vds.find(id,false).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","가상디바이스가 없습니다."));
        var spec=VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson());
        if(!spec.continuous())throw new ControlPlaneException(409,"CONTINUOUS_MODE_REQUIRED","연속 처리 가상디바이스에서 조회할 수 있습니다.");return map(WorkflowInput.JSON.decode(spec.continuousJson()));
    }
    public Map<String,Object> outputs(UUID id) {
        var config=config(id);var latest=new HashMap<String,Map<String,Object>>();for(var raw:executions.latest(id)){var result=map(WorkflowInput.JSON.decode(raw));latest.put((String)result.get("outputId"),result);}
        var execution=executions.find(id);var now=clock.instant();boolean engineAvailable=executions.session("central").map(s->s.expiresAt().isAfter(now)).orElse(false);
        var items=new ArrayList<Map<String,Object>>();
        for(var raw:(List<?>)config.get("outputs")) {
            var definition=map(raw);var output=new TreeMap<>(definition);String name=(String)definition.get("name");output.put("outputId",name);
            var sample=latest.get(name);output.put("latest",sample);boolean current=sample!=null && engineAvailable && execution.map(e->e.running() && e.generation().toString().equals(sample.get("generation")) && e.state().equals("RUNNING")).orElse(false)
                && Instant.parse((String)sample.get("evaluationAt")).plusSeconds(((Number)config.get("intervalSeconds")).longValue()*2+2).isAfter(now);
            output.put("quality",sample==null?"NOT_AVAILABLE":current?"VALID":"STALE");items.add(output);
        }
        return Map.of("vdId",id.toString(),"executionMode","CONTINUOUS_EKUIPER","asOf",now.toString(),"intervalSeconds",config.get("intervalSeconds"),"retentionDays",config.get("retentionDays"),"historyEnabled",history.enabled(),"resultStorage",ResultStoragePolicy.policy(config),"items",items);
    }
    public Map<String,Object> history(UUID id,String output,String fromText,String toText,int limit,String cursor,Integer bucket) {
        var config=config(id);if(((List<?>)config.get("outputs")).stream().map(DeviceProfileCompiler::map).noneMatch(o->output.equals(o.get("name"))))throw new ControlPlaneException(404,"VD_OUTPUT_NOT_FOUND","출력 측정 항목이 없습니다.");
        Instant to=toText==null?clock.instant():Instant.parse(toText),from=fromText==null?to.minusSeconds(3600):Instant.parse(fromText);
        if(!from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0 || limit<1 || limit>2000 || cursor!=null && cursor.length()>256 || bucket!=null && (bucket<1||bucket>86400||Duration.between(from,to).getSeconds()/bucket>2000))throw new IllegalArgumentException("Invalid history period, limit or chart bucket");
        Map<String,Object> rows;
        try{rows=history.read(id,output,from,to,limit,cursor,bucket);}catch(IllegalArgumentException e){throw e;}catch(RuntimeException e){throw new ControlPlaneException(503,"HISTORY_STORE_UNAVAILABLE","이력 저장소에 연결할 수 없습니다.");}
        var response=new TreeMap<>(rows);response.put("vdId",id.toString());response.put("outputId",output);response.put("from",from.toString());response.put("to",to.toString());response.put("gaps",executions.gaps(id,from,to));response.put("retentionDays",config.get("retentionDays"));return response;
    }
}
