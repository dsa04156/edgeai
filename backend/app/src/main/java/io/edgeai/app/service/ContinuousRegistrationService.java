package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;

@Service
public class ContinuousRegistrationService {
    private final VirtualDeviceRepository registrations;
    private final VirtualDeviceService vds;
    private final ManagedDeviceService sensors;
    private final io.edgeai.domain.repository.ProfileRepository profiles;
    private final boolean enabled;
    private final ProcessingEngineService engine;
    public ContinuousRegistrationService(VirtualDeviceRepository registrations,VirtualDeviceService vds,ManagedDeviceService sensors,
            io.edgeai.domain.repository.ProfileRepository profiles,ProcessingEngineService engine,@Value("${edgeai.continuous.enabled:false}") boolean enabled) {
        this.registrations=registrations;this.vds=vds;this.sensors=sensors;this.profiles=profiles;this.enabled=enabled;this.engine=engine;
    }
    public Map<String,Object> catalog(){return Map.of("enabled",enabled,"executionMode","CONTINUOUS_EKUIPER","operations",List.of("scalar","movingAverage","minimum","maximum","threshold","filter"),"maxInputs",16,"maxOutputs",16,"inputs",enabled?engine.inputCatalog():List.of());}
    @Transactional
    public VirtualDeviceRepository.Creation create(String body) {
        if(!enabled)throw new ControlPlaneException(503,"CONTINUOUS_DISABLED","연속 처리 기능이 비활성화되어 있습니다.");
        var input=parse(body,"key","displayName","sources","continuous");
        var request=new TreeMap<String,Object>();input.forEach((k,v)->{if(!k.equals("continuous"))request.put((String)k,v);});
        request.put("profileVersionId",new UUID(0,0).toString());request.put("placement",Map.of("mode","AUTO"));
        var parsed=VirtualDeviceInput.create(JSON.canonical(request));
        String config=ContinuousInput.parse(input.get("continuous"),parsed.configuration().sources().keySet());
        String digest=JSON.digest("edgeai.continuous-registration/v1",Map.of("registration",parsed.digest(),"continuous",JSON.decode(config)));
        registrations.lockProcessingKey(parsed.key());var previous=registrations.findByKey(parsed.key());
        if(previous.isPresent()) {
            if(!registrations.processingCreationDigest(previous.get().id()).filter(digest::equals).isPresent())throw new ControlPlaneException(409,"VD_CONFLICT","같은 키로 다른 가상디바이스가 등록되어 있습니다.");
            return new VirtualDeviceRepository.Creation(previous.get(),false);
        }
        sensors.prepareObservations(parsed.configuration().sources().values().stream().map(source->source.deviceId()).toList());engine.invalidateCatalog();
        var sources=new TreeMap<String,Object>();
        for(var entry:parsed.configuration().sources().entrySet()) {
            if(entry.getValue().properties().size()!=1)throw new IllegalArgumentException("Choose exactly one measurement per input alias");
            var device=sensors.get(entry.getValue().deviceId());
            sources.put(entry.getKey(),Map.of("deviceModel",device.get("model"),"required",true));
        }
        var spec=Map.of("apiVersion","edgeai.vd/v3","type","processing","executionMode","CONTINUOUS_EKUIPER","sources",sources,"continuous",JSON.decode(config));
        String profileKey="continuous-"+JSON.digest("edgeai.continuous-profile/v1",spec).substring(7);
        var profile=profiles.publish(new ProfileIdentity(ProfileIdentity.Kind.VD,profileKey,"1.0.0"),JSON.canonical(spec),JSON.digest("edgeai-profile-v1\nVD",spec)).version();
        request.put("profileVersionId",profile.id().toString());
        var created=vds.create(JSON.canonical(request));registrations.recordProcessingCreation(created.value().id(),digest);return created;
    }
    @Transactional
    public io.edgeai.domain.vd.VirtualDevice update(UUID id,String body) {
        if(!enabled)throw new ControlPlaneException(503,"CONTINUOUS_DISABLED","연속 처리 기능이 비활성화되어 있습니다.");
        var current=registrations.find(id,true).orElseThrow(()->new ControlPlaneException(404,"VD_NOT_FOUND","가상디바이스가 없습니다."));
        if(current.serviceProfileVersionId()!=null)throw new IllegalArgumentException("Continuous mode is required");
        var input=parse(body,"revision","displayName","sources","continuous");
        if(current.revision()!=VirtualDeviceInput.lifecycleRevision(JSON.canonical(Map.of("revision",input.get("revision")))))throw new ControlPlaneException(409,"VD_CONFLICT","최신 revision으로 수정하세요.");
        var request=new TreeMap<String,Object>();request.put("key",current.key());request.put("displayName",input.get("displayName"));request.put("sources",input.get("sources"));request.put("profileVersionId",new UUID(0,0).toString());request.put("placement",Map.of("mode","AUTO"));
        var parsed=VirtualDeviceInput.create(JSON.canonical(request));String config=ContinuousInput.parse(input.get("continuous"),parsed.configuration().sources().keySet());
        sensors.prepareObservations(parsed.configuration().sources().values().stream().map(source->source.deviceId()).toList());engine.invalidateCatalog();
        var sources=new TreeMap<String,Object>();for(var entry:parsed.configuration().sources().entrySet()) {
            if(entry.getValue().properties().size()!=1)throw new IllegalArgumentException("Choose exactly one measurement per input alias");
            sources.put(entry.getKey(),Map.of("deviceModel",sensors.get(entry.getValue().deviceId()).get("model"),"required",true));
        }
        var spec=Map.of("apiVersion","edgeai.vd/v3","type","processing","executionMode","CONTINUOUS_EKUIPER","sources",sources,"continuous",JSON.decode(config));
        String profileKey="continuous-"+JSON.digest("edgeai.continuous-profile/v1",spec).substring(7);
        var profile=profiles.publish(new ProfileIdentity(ProfileIdentity.Kind.VD,profileKey,"1.0.0"),JSON.canonical(spec),JSON.digest("edgeai-profile-v1\nVD",spec)).version();
        request.remove("key");request.put("revision",input.get("revision"));request.put("profileVersionId",profile.id().toString());return vds.update(id,JSON.canonical(request));
    }
}
