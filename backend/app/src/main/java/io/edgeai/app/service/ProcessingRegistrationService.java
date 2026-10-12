package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ProcessingInput;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.WorkflowInput.*;

/** Resolves the shared runtime and immutable profiles for the sensor selection form. */
@Service
public class ProcessingRegistrationService {
    private final ProfileService profiles;
    private final VirtualDeviceService vds;
    private final ManagedDeviceService sensors;
    private final String image;
    private final List<String> architectures;
    public ProcessingRegistrationService(ProfileService profiles,VirtualDeviceService vds,ManagedDeviceService sensors,
        @Value("${edgeai.vd.processing-image:}") String image,@Value("${edgeai.vd.processing-architectures:amd64}") String architectures) {
        this.profiles=profiles;this.vds=vds;this.sensors=sensors;this.image=image;
        this.architectures=Arrays.stream(architectures.split(",")).map(String::trim).filter(s->!s.isEmpty()).distinct().sorted().toList();
        if(!image.isBlank() && (!image.matches("[^\\s@]+@sha256:[a-f0-9]{64}") || this.architectures.isEmpty() || !Set.of("amd64","arm64").containsAll(this.architectures)))
            throw new IllegalArgumentException("Processing runtime requires a digest image and supported architectures");
    }
    public Map<String,Object> catalog() {
        return Map.of("ready",!image.isBlank(),"architectures",architectures,"operations",List.of("filter","movingAverage","rms","fft","threshold"));
    }
    @Transactional
    public VirtualDeviceRepository.Creation create(String body) {
        if(image.isBlank())throw new ControlPlaneException(503,"PROCESSING_RUNTIME_UNAVAILABLE","공통 처리 실행 이미지가 아직 설정되지 않았습니다.");
        var input=parse(body,"key","displayName","deviceId","maxAgeSeconds","processing","placement");
        String processing=ProcessingInput.parse(input.get("processing"),Set.of("input"));
        var config=parameters(JSON.decode(processing));
        if(!"output".equals(config.get("outputPort")))throw new IllegalArgumentException("Shared runtime output is output");
        var properties=new TreeSet<String>();
        for(var raw:(List<?>)config.get("pipelines")) {
            var source=parameters(parameters(raw).get("input"));
            if(!"input".equals(source.get("sourceKey")))throw new IllegalArgumentException("Sensor selection requires a physical sensor input");
            properties.add(text(source.get("property"),63));
        }
        var sensor=sensors.get(text(input.get("deviceId"),253));
        var serviceSpec=Map.of("apiVersion","edgeai/v1","image",image,"command",List.of("python3","-m","edgeai_runner.processing"),"args",List.of(),
            "resources",Map.of("requests",Map.of("cpu","100m","memory","64Mi"),"limits",Map.of("cpu","1","memory","256Mi")),
            "platform",Map.of("os","linux","architectures",architectures),"inputs",Map.of(),"outputs",Map.of("output",Map.of("mediaType","application/json","maxBytes",1048576)),"timeoutSeconds",120,"qos","Burstable");
        var service=publish(ProfileIdentity.Kind.SERVICE,"processing-runtime-",serviceSpec);
        var templateSpec=Map.of("apiVersion","edgeai.vd/v2","type","processing","serviceProfileVersionId",service.toString(),
            "sources",Map.of("input",Map.of("deviceModel",sensor.get("model"),"required",true)),"processing",config,
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60));
        var template=publish(ProfileIdentity.Kind.VD,"sensor-processing-",templateSpec);
        return vds.create(JSON.canonical(Map.of("key",input.get("key"),"displayName",input.get("displayName"),"profileVersionId",template.toString(),
            "sources",List.of(Map.of("sourceKey","input","deviceId",input.get("deviceId"),"properties",List.copyOf(properties),"maxAgeSeconds",input.get("maxAgeSeconds"))),"placement",input.get("placement"))));
    }
    private UUID publish(ProfileIdentity.Kind kind,String prefix,Object spec) {
        String key=prefix+JSON.digest("edgeai.processing-registration/v1",spec).substring(7);
        return profiles.publish(kind,JSON.canonical(Map.of("key",key,"version","1.0.0","spec",spec))).version().id();
    }
}
