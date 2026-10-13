package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.ProcessingExpression;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

@Service
public class ProcessingDefinitionService {
    private final VirtualDeviceRepository vds;
    private final ProfileRepository profiles;
    private final ProcessingDefinitionRepository definitions;
    private final ManagedDeviceService devices;
    private final Clock clock;
    public ProcessingDefinitionService(VirtualDeviceRepository vds,ProfileRepository profiles,ProcessingDefinitionRepository definitions,ManagedDeviceService devices,Clock clock){this.vds=vds;this.profiles=profiles;this.definitions=definitions;this.devices=devices;this.clock=clock;}
    @Transactional public void capture(UUID id) {
        var vd=vds.find(id,true).orElseThrow();
        var config=WorkflowInput.JSON.decode(VirtualDeviceInput.spec(profiles.find(vd.profileVersionId()).orElseThrow().specJson()).continuousJson());
        var sources=new ArrayList<Map<String,Object>>();var types=new TreeMap<String,ProcessingExpression.Type>();
        for(var binding:vds.activeSources(id).stream().sorted(Comparator.comparing(b->b.sourceKey())).toList()) {
            var device=devices.get(binding.managedDeviceId());var profile=map(device.get("profile"));
            var property=((List<?>)profile.get("properties")).stream().map(DeviceProfileCompiler::map).filter(p->binding.properties().equals(List.of(p.get("name")))).findFirst().orElseThrow();
            types.put(binding.sourceKey(),ContinuousRuleCompiler.inputType((String)property.get("dataType")));
            sources.add(Map.of("alias",binding.sourceKey(),"deviceId",binding.managedDeviceId(),"deviceUid",binding.deviceUid().toString(),"deviceModel",binding.deviceModel(),"property",property.get("name"),"type",property.get("dataType"),"unit",property.getOrDefault("unit",""),"maxAgeSeconds",binding.maxAgeSeconds(),"configurationSha256",DeviceProfileCompiler.configuration(profile)));
        }
        // A neutral generation produces an inert template; execution generations are compiled by the manager.
        var rules=ContinuousRuleCompiler.compile(id,new UUID(0,0),config,types);
        var documents=rules.stream().map(r->Map.of("id",r.id(),"outputId",r.outputId(),"sql",r.sql(),"type",r.type(),"unit",r.unit(),"warmupTicks",r.warmupTicks(),"conditional",r.conditional())).toList();
        definitions.append(id,vd.revision(),vd.profileVersionId(),WorkflowInput.JSON.canonical(config),WorkflowInput.JSON.canonical(sources),WorkflowInput.JSON.canonical(documents),clock.instant());
    }
    public Map<String,Object> versions(UUID id,int limit,int offset) {
        vds.find(id,false).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","가상 디바이스가 없습니다."));NodeService.page(limit,offset);
        var rows=definitions.versions(id,limit+1,offset);var result=new TreeMap<String,Object>();result.put("items",rows.stream().limit(limit).toList());result.put("nextOffset",rows.size()>limit?offset+limit:null);return result;
    }
}
