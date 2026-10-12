package io.edgeai.app.service;

import io.edgeai.domain.repository.SensorSampleRepository;
import io.edgeai.app.support.WorkflowInput;
import io.edgeai.app.exception.ControlPlaneException;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

/** One shared sampler for existing Mapper twins; never creates sensor processes or Pods. */
@Service
public class SensorHistoryService {
    private final SensorSampleRepository samples;
    private final ManagedDeviceService devices;
    private final Clock clock;
    public SensorHistoryService(SensorSampleRepository samples,ManagedDeviceService devices,Clock clock){this.samples=samples;this.devices=devices;this.clock=clock;}
    @Scheduled(fixedDelayString="${edgeai.devices.processing-sample-interval-ms:1000}")
    public void collect() {
        var readings=new HashMap<String,Map<String,Object>>();
        for(var binding:samples.watchedSources()) {
            try {
                var device=readings.computeIfAbsent(binding.managedDeviceId(),id->devices.source(id,binding.deviceModel(),binding.deviceUid()));
                if(!binding.deviceUid().toString().equals(device.get("uid")) || !binding.deviceModel().equals(device.get("model")))continue;
                if(!"ONLINE".equals(device.get("state")))continue;
                for(var property:binding.properties()) {
                    var reading=map(map(device.get("properties")).get(property));
                    if(reading.isEmpty())continue;
                    if(!(reading.get("timestamp") instanceof Number observed))continue;
                    long timestamp=observed.longValue();
                    if(timestamp<=0 || timestamp>clock.millis()+5000 || clock.millis()-timestamp>binding.maxAgeSeconds()*1000L || !currentConfiguration(device,reading,Instant.ofEpochMilli(timestamp)))continue;
                    var definitions=(List<?>)map(device.get("profile")).getOrDefault("properties",List.of());
                    var definition=definitions.stream().map(SensorHistoryService::definition).filter(v->property.equals(v.get("name"))).findFirst().orElseThrow();
                    Object value=VDSensorInputsService.value(Objects.toString(reading.get("value"),""),(String)definition.get("dataType"));
                    append(binding.deviceUid(),property,configuration(device),Instant.ofEpochMilli(timestamp),value);
                }
            } catch(ControlPlaneException | IllegalArgumentException | NoSuchElementException e) {
                // An unavailable sensor contributes no observation; Run creation checks readiness.
            }
        }
    }
    private static Map<String,Object> definition(Object value){return map(value);}
    public static String configuration(Map<String,Object> device) {
        return WorkflowInput.JSON.digest("edgeai.sensor-configuration/v1",Map.of("model",device.get("model"),"profile",device.get("profile"),
            "targetNode",Objects.toString(device.get("targetNode"),""),"updatedAt",Objects.toString(device.get("configurationUpdatedAt"),"")));
    }
    public static boolean currentConfiguration(Map<String,Object> device,Map<String,Object> reading,Instant observed) {
        String updated=Objects.toString(device.get("configurationUpdatedAt"),"");
        String applied=Objects.toString(reading.get("configurationSha256"),"");
        boolean required=device.containsKey("configurationSha256") || !updated.isEmpty() || !applied.isEmpty();
        return (!required || io.edgeai.app.support.DeviceProfileCompiler.configuration(device.get("profile")).equals(applied))
            && (updated.isEmpty() || observed.isAfter(Instant.parse(updated)));
    }
    public void append(UUID uid,String property,String configuration,Instant observedAt,Object value) {
        samples.append(uid,property,configuration,new SensorSampleRepository.Sample(observedAt,WorkflowInput.JSON.canonical(value)));
    }
    public List<Map<String,Object>> window(UUID uid,String property,String configuration,Instant captured,int maxAgeSeconds,int count) {
        var window=samples.recent(uid,property,configuration,captured.minusSeconds(maxAgeSeconds),captured.plusSeconds(5),count);
        if(window.size()!=count)throw new ControlPlaneException(409,"VD_PROCESSING_WINDOW_PENDING","처리에 필요한 새 표본을 수집 중입니다. 표본 수와 허용 시간을 확인하세요.");
        return window.stream().map(s->Map.<String,Object>of("value",WorkflowInput.JSON.decode(s.valueJson()),"observedAt",s.observedAt().toString())).toList();
    }
}
