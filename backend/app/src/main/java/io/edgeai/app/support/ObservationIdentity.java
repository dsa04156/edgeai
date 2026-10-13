package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.DeviceProfileCompiler.*;

/** Internal identity is separate from the editable profile and its configuration digest. */
public final class ObservationIdentity {
    public static final String ANNOTATION="edgeai.io/source-generation";
    private ObservationIdentity() {}
    public static Map<String,Object> qualify(Map<String,Object> resource,Map<String,Object> previous) {
        var result=map(copy(resource));
        var metadata=map(result.get("metadata"));var annotations=new TreeMap<>(map(metadata.getOrDefault("annotations",Map.of())));
        String generation=previous==null?"":Objects.toString(map(map(previous.get("metadata")).getOrDefault("annotations",Map.of())).get(ANNOTATION),"");
        try {UUID.fromString(generation);}catch(IllegalArgumentException e){generation=UUID.randomUUID().toString();}
        annotations.put(ANNOTATION,generation);metadata.put("annotations",annotations);
        var profile=map(JSON.decode(Objects.toString(annotations.get("edgeai.io/device-profile"),"{}")));
        var units=new HashMap<String,Object>();
        for(var raw:(List<?>)profile.getOrDefault("properties",List.of())){var p=map(raw);units.put((String)p.get("name"),p.getOrDefault("unit",""));}
        for(var raw:(List<?>)map(result.get("spec")).getOrDefault("properties",List.of())) {
            var property=map(raw);if("connection".equals(property.get("name")))continue;
            var visitor=map(map(property.get("visitors")).get("configData"));
            visitor.put("sourceGeneration",generation);visitor.put("unit",units.getOrDefault(property.get("name"),""));
            visitor.put("configurationSha256",configuration(profile));
        }
        return result;
    }
    private static Object copy(Object value) {
        if(value instanceof Map<?,?> source){var result=new TreeMap<String,Object>();source.forEach((k,v)->result.put((String)k,copy(v)));return result;}
        if(value instanceof List<?> source)return source.stream().map(ObservationIdentity::copy).toList();
        return value;
    }
}
