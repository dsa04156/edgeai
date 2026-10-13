package io.edgeai.app.support;

import java.net.URI;
import java.util.*;
import static io.edgeai.app.support.DeviceProfileCompiler.*;

/** Resolves the CRD's output endpoint; sensor protocol endpoints are intentionally independent. */
public final class ObservationRoute {
    private ObservationRoute() {}
    /** Selection metadata is available before qualified observations are enabled. */
    public static Map<String,Object> metadata(Map<String,Object> resource) {
        var metadata=map(resource.get("metadata"));UUID.fromString(Objects.toString(metadata.get("uid"),""));
        var annotations=map(metadata.getOrDefault("annotations",Map.of()));var profile=map(JSON.decode(Objects.toString(annotations.get("edgeai.io/device-profile"),"{}")));
        var spec=map(resource.get("spec"));var properties=(List<?>)profile.getOrDefault("properties",List.of());
        if(properties.size()!=1)throw new IllegalArgumentException("Expected a separate measurement profile");
        var property=map(properties.getFirst());
        return new TreeMap<>(Map.of("deviceId",metadata.get("name"),"deviceUid",metadata.get("uid"),"deviceModel",map(spec.get("deviceModelRef")).get("name"),
            "namespace",metadata.get("namespace"),"property",property.get("name"),"type",property.get("dataType"),"unit",property.getOrDefault("unit",""),"targetNode",spec.get("nodeName")));
    }
    public static Map<String,Object> resolve(Map<String,Object> resource,Map<String,Object> node,Map<String,Object> overrides) {
        var metadata=map(resource.get("metadata"));var annotations=map(metadata.getOrDefault("annotations",Map.of()));
        UUID.fromString(Objects.toString(metadata.get("uid"),""));String generation=Objects.toString(annotations.get(ObservationIdentity.ANNOTATION),"");UUID.fromString(generation);
        String namespace=(String)metadata.get("namespace"),id=(String)metadata.get("name");
        var profile=map(JSON.decode((String)annotations.get("edgeai.io/device-profile")));
        var spec=map(resource.get("spec"));var properties=((List<?>)spec.get("properties")).stream().map(DeviceProfileCompiler::map).filter(p->!"connection".equals(p.get("name"))).toList();
        if(properties.size()!=1)throw new IllegalArgumentException("Expected a separate measurement Device");
        var property=properties.getFirst();var visitor=map(map(property.get("visitors")).get("configData"));
        String hash=configuration(profile);
        if(!generation.equals(visitor.get("sourceGeneration")) || !hash.equals(visitor.get("configurationSha256")))throw new IllegalArgumentException("Device observation identity has not been applied");
        var mqtt=map(map(property.get("pushMethod")).get("mqtt"));var endpoint=URI.create((String)mqtt.get("address"));
        if(endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null || endpoint.getHost()==null || !Set.of("tcp","ssl","tls").contains(endpoint.getScheme()) || !Set.of("","/").contains(endpoint.getPath()) || endpoint.getPort()<1)throw new IllegalArgumentException("Unsupported CRD data broker route");
        var nodeMeta=map(node.get("metadata"));String nodeUid=(String)nodeMeta.get("uid");
        String override=Objects.toString(overrides.get(nodeUid),"");
        if(!override.isBlank() || Set.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost())) {
            String host=override;
            if(host.isBlank()) {
                var addresses=((List<?>)map(node.get("status")).getOrDefault("addresses",List.of())).stream().map(DeviceProfileCompiler::map).filter(a->"InternalIP".equals(a.get("type"))).map(a->(String)a.get("address")).distinct().toList();
                if(addresses.size()!=1)throw new IllegalArgumentException("Configure a data broker address for this Node UID");host=addresses.getFirst();
            }
            try {endpoint=new URI(endpoint.getScheme(),null,host,endpoint.getPort(),null,null,null);}catch(Exception e){throw new IllegalArgumentException("Invalid node data broker address");}
        }
        var result=new TreeMap<String,Object>();result.put("deviceId",id);result.put("deviceUid",metadata.get("uid"));result.put("deviceModel",map(spec.get("deviceModelRef")).get("name"));
        result.put("namespace",namespace);result.put("property",property.get("name"));result.put("sourceGeneration",generation);result.put("configurationSha256",hash);
        result.put("targetNode",spec.get("nodeName"));
        result.put("type",visitor.get("dataType"));result.put("unit",visitor.getOrDefault("unit",""));result.put("broker",endpoint.toString());
        result.put("topic","edgeai/observations/v1/"+namespace+"/"+id+"/"+property.get("name"));return result;
    }
}
