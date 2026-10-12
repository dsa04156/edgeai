package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.vd.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.support.DeviceProfileCompiler.map;
import static io.edgeai.app.support.WorkflowInput.JSON;

@Service
public class VDSensorInputsService {
    private final VirtualDeviceRepository vds;
    private final ManagedDeviceService devices;
    private final Clock clock;
    private final io.edgeai.domain.repository.ProfileRepository profiles;
    private final SensorHistoryService history;
    public VDSensorInputsService(VirtualDeviceRepository vds,ManagedDeviceService devices,Clock clock,io.edgeai.domain.repository.ProfileRepository profiles,SensorHistoryService history){this.vds=vds;this.devices=devices;this.clock=clock;this.profiles=profiles;this.history=history;}

    @Transactional(propagation=Propagation.MANDATORY)
    public Map<UUID,String> capture(Collection<UUID> ids,boolean stream) {
        var selected=new TreeMap<UUID,List<VDSourceBinding>>();var identities=new HashMap<UUID,VirtualDevice>();
        for(var id:ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList()) {
            var vd=vds.find(id,true).orElseThrow();identities.put(id,vd);
            selected.put(id,vds.activeSources(id).stream().filter(b->b.managedDeviceId()!=null).toList());
        }
        // Lock all sensor names once in a global order, including requests containing different VD sets.
        vds.lockManagedSources(selected.values().stream().flatMap(List::stream).map(VDSourceBinding::managedDeviceId).toList());
        var readings=new HashMap<String,Map<String,Object>>();
        for(var bindings:selected.values())for(var b:bindings) {
            if(stream)throw error("VD_SENSOR_STREAM_UNSUPPORTED","센서 자동 입력은 BATCH 실행에서 지원합니다.");
            if(b.properties().isEmpty())throw error("VD_SOURCE_PROPERTIES_REQUIRED","VD 원본에서 사용할 측정 항목을 먼저 선택하세요.");
            var device=readings.computeIfAbsent(b.managedDeviceId(),id->devices.source(id,b.deviceModel(),b.deviceUid()));
            if(!b.deviceUid().toString().equals(device.get("uid")) || !b.deviceModel().equals(device.get("model")))throw error("VD_SOURCE_INCOMPATIBLE","VD 원본 센서 신원이 변경됐습니다.");
            if(!"ONLINE".equals(device.get("state")))throw error("VD_SOURCE_OFFLINE","VD 원본 센서가 오프라인입니다. 수신 상태를 확인하세요.");
        }
        var captured=clock.instant();var frames=new HashMap<UUID,String>();
        selected.forEach((id,bindings)->{
            var spec=VirtualDeviceInput.spec(profiles.find(identities.get(id).profileVersionId()).orElseThrow().specJson());
            if(bindings.isEmpty() && spec.processingJson()==null)return;
            var sources=new TreeMap<String,Object>();
            int count=spec.processingJson()==null?1:((Number)map(JSON.decode(spec.processingJson())).get("sampleCount")).intValue();
            for(var binding:bindings)sources.put(binding.sourceKey(),source(binding,readings.get(binding.managedDeviceId()),captured,count));
            var frame=new TreeMap<String,Object>(Map.of("apiVersion","edgeai.vd-input/v1","vdId",id.toString(),"vdRevision",identities.get(id).revision(),"capturedAt",captured.toString(),"sources",sources));
            if(spec.processingJson()!=null)frame.put("processing",JSON.decode(spec.processingJson()));
            try {frames.put(id,JSON.boundedCanonical(frame,spec.processingJson()==null?65536:1048576));}
            catch(io.edgeai.app.exception.ProfilePayloadTooLargeException e){throw error("VD_PROCESSING_INPUT_TOO_LARGE","처리 입력은 1 MiB 이하입니다. 선택 항목이나 표본 수를 줄여 다시 실행하세요.");}
        });
        return Map.copyOf(frames);
    }
    private Map<String,Object> source(VDSourceBinding b,Map<String,Object> device,Instant captured,int count) {
        var definitions=new HashMap<String,Map<String,Object>>();
        for(var item:(List<?>)map(device.get("profile")).getOrDefault("properties",List.of())){var p=map(item);definitions.put((String)p.get("name"),p);}
        var values=map(device.get("properties"));var measurements=new TreeMap<String,Object>();
        for(String property:b.properties()) {
            var definition=definitions.get(property);if(definition==null)throw error("VD_SOURCE_INCOMPATIBLE","선택한 VD 측정 항목이 센서 규격에 없습니다.");
            if(!values.containsKey(property))throw error("VD_SOURCE_VALUE_MISSING","선택한 VD 측정 항목이 아직 수신되지 않았습니다.");
            var reading=map(values.get(property));long timestamp=((Number)reading.get("timestamp")).longValue();
            if(!SensorHistoryService.currentConfiguration(device,reading,Instant.ofEpochMilli(timestamp)))
                throw error("VD_SOURCE_CONFIGURATION_PENDING","Mapper의 새 수집 설정 적용과 해당 설정의 관측값을 기다리세요.");
            if(timestamp<=0 || timestamp>captured.toEpochMilli()+5000 || captured.toEpochMilli()-timestamp>b.maxAgeSeconds()*1000L)
                throw error("VD_SOURCE_VALUE_STALE","선택한 VD 측정값의 수신 시각이 허용 범위를 벗어났습니다.");
            String type=(String)definition.get("dataType");
            Object value=value(Objects.toString(reading.get("value"),""),type);var observedAt=Instant.ofEpochMilli(timestamp);
            var measurement=new TreeMap<String,Object>(Map.of("value",value,"dataType",type,"unit",Objects.toString(definition.get("unit"),""),"observedAt",observedAt.toString()));
            if(count>1){String configuration=SensorHistoryService.configuration(device);history.append(b.deviceUid(),property,configuration,observedAt,value);measurement.put("configurationSha256",configuration);measurement.put("samples",history.window(b.deviceUid(),property,configuration,captured,b.maxAgeSeconds(),count));}
            measurements.put(property,measurement);
        }
        return Map.of("bindingId",b.id().toString(),"deviceId",b.managedDeviceId(),"deviceUid",b.deviceUid().toString(),"deviceModel",b.deviceModel(),
            "maxAgeSeconds",b.maxAgeSeconds(),"properties",measurements);
    }
    static Object value(String raw,String type) {
        if(raw.length()>4096)throw error("VD_SOURCE_VALUE_INVALID","VD 측정값이 허용 크기를 초과했습니다.");
        try {
            if("string".equals(type))return raw;
            if("bool".equals(type)){if(!Set.of("true","false").contains(raw))throw new IllegalArgumentException();return Boolean.valueOf(raw);}
            if(type==null || !Set.of("float32","float64","int16","uint16","int32","uint32").contains(type))throw new IllegalArgumentException();
            var value=new BigDecimal(raw);
            // DataType describes the wire value; Mapper has already applied scale/offset in double precision.
            if(!Double.isFinite(value.doubleValue()))throw new IllegalArgumentException();
            return value;
        } catch(IllegalArgumentException e){throw error("VD_SOURCE_VALUE_INVALID","VD 측정값의 자료형이나 숫자 형식이 올바르지 않습니다.");}
    }
    private static ControlPlaneException error(String code,String message){return new ControlPlaneException(409,code,message);}
}
