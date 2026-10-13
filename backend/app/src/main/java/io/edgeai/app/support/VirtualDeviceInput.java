package io.edgeai.app.support;

import io.edgeai.domain.device.Device;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.vd.*;
import java.math.BigInteger;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

public final class VirtualDeviceInput {
    private VirtualDeviceInput() {}
    public record Configuration(String displayName, Map<String,VDSourceSelection> sources, VirtualDevice.Placement placement) {}
    public record Create(String key, UUID profileVersionId, Configuration configuration, String digest) {}
    public record Update(long revision, UUID profileVersionId, Configuration configuration) {}
    public static long lifecycleRevision(String body) { return number(parse(body,"revision").get("revision"),0,9007199254740991L); }
    public static Create create(String body) {
        var map=parse(body,"key","displayName","profileVersionId","sources","placement");
        String key=key(map.get("key"));UUID profile=uuid(map.get("profileVersionId"));var configuration=configuration(map);
        String digest=JSON.digest("edgeai-vd-create-v1",Map.of("key",key,"profileVersionId",profile.toString(),
            "displayName",configuration.displayName(),"sources",configuration.sources().entrySet().stream()
                .map(e->sourceDocument(e.getKey(),e.getValue())).toList(),"placement",document(configuration.placement())));
        return new Create(key,profile,configuration,digest);
    }
    public static Update update(String body) {
        var map=parameters(JSON.parse(body,65536));
        object(map,map.containsKey("profileVersionId")?new String[]{"revision","displayName","sources","placement","profileVersionId"}:new String[]{"revision","displayName","sources","placement"});
        return new Update(number(map.get("revision"),0,9007199254740991L),map.containsKey("profileVersionId")?uuid(map.get("profileVersionId")):null,configuration(map));
    }
    private static Configuration configuration(Map<?,?> map) {
        String name=text(map.get("displayName"),128);var sources=new TreeMap<String,VDSourceSelection>();
        if (!(map.get("sources") instanceof List<?> list) || list.size()>16) throw new IllegalArgumentException("At most 16 sources");
        for(var item:list) {
            var source=parameters(item);
            var fields=new ArrayList<>(List.of("sourceKey","deviceId"));
            if(source.containsKey("properties"))fields.add("properties");if(source.containsKey("maxAgeSeconds"))fields.add("maxAgeSeconds");
            object(source,fields.toArray(String[]::new));
            var selection=new VDSourceSelection(deviceName(source.get("deviceId")),properties(source.containsKey("properties")?source.get("properties"):List.of()),
                (int)number(source.containsKey("maxAgeSeconds")?source.get("maxAgeSeconds"):BigInteger.valueOf(30),1,300));
            if(sources.put(key(source.get("sourceKey")),selection)!=null) throw new IllegalArgumentException("Duplicate source key");
        }
        var placement=parameters(map.get("placement"));var mode=VirtualDevice.Mode.valueOf(text(placement.get("mode"),8));
        object(placement,mode==VirtualDevice.Mode.NODE?new String[]{"mode","nodeId"}:new String[]{"mode"});
        return new Configuration(name,Collections.unmodifiableSortedMap(sources),new VirtualDevice.Placement(mode,
            mode==VirtualDevice.Mode.NODE?uuid(placement.get("nodeId")):null));
    }
    private static List<String> properties(Object value) {
        if(!(value instanceof List<?> list) || list.size()>64)throw new IllegalArgumentException("At most 64 measurement properties");
        var result=new TreeSet<String>();
        for(var item:list){String name=text(item,63);if(!name.matches("[a-z][a-z0-9_]*") || name.equals("connection") || !result.add(name))throw new IllegalArgumentException("Invalid or duplicate measurement property");}
        return List.copyOf(result);
    }
    private static Map<String,Object> sourceDocument(String key,VDSourceSelection value) {
        var result=new TreeMap<String,Object>(Map.of("sourceKey",key,"deviceId",value.deviceId()));
        if(!value.properties().isEmpty() || value.maxAgeSeconds()!=30){result.put("properties",value.properties());result.put("maxAgeSeconds",value.maxAgeSeconds());}
        return result;
    }
    public static Map<String,Object> document(VirtualDevice.Placement placement) {
        return placement.mode()==VirtualDevice.Mode.AUTO?Map.of("mode","AUTO"):Map.of("mode","NODE","nodeId",placement.nodeId().toString());
    }
    public static VDProfileSpec spec(String document) {
        var map=parameters(JSON.parse(document,65536));
        if("edgeai.vd/v3".equals(map.get("apiVersion"))) {
            object(map,"apiVersion","type","executionMode","sources","continuous");
            if(!"processing".equals(map.get("type")) || !"CONTINUOUS_EKUIPER".equals(map.get("executionMode")))throw new IllegalArgumentException("Unsupported continuous execution mode");
            var sources=new TreeMap<String,VDProfileSpec.Source>();
            parameters(map.get("sources")).forEach((alias,raw)->{
                var source=object(raw,"deviceModel","required");
                if(!Boolean.TRUE.equals(source.get("required")))throw new IllegalArgumentException("Continuous inputs are required");
                sources.put(text(alias,32),new VDProfileSpec.Source(deviceName(source.get("deviceModel")),true));
            });
            return new VDProfileSpec(sources,ContinuousInput.parse(map.get("continuous"),sources.keySet()));
        }
        throw new IllegalArgumentException("Only edgeai.vd/v3 continuous profiles are supported");
    }
    private static String deviceName(Object value) {
        String name=text(value,253);io.edgeai.domain.runtime.RuntimeNames.dns(name,253);return name;
    }
    private static String key(Object value) { String key=text(value,100);ProfileIdentity.validateKey(key);return key; }
    private static long number(Object value,long min,long max) {
        if(!(value instanceof BigInteger n) || n.compareTo(BigInteger.valueOf(min))<0 || n.compareTo(BigInteger.valueOf(max))>0)
            throw new IllegalArgumentException("Integer out of range");
        return n.longValueExact();
    }
}
