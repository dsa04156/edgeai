package io.edgeai.app.support;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

public final class VDRuntimeDocuments {
    private VDRuntimeDocuments() {}
    public static String configuration(VirtualDevice vd,List<VDSourceBinding> sources,VDProfileSpec spec,String nodeName,RuntimeSettings settings) {
        var map=new TreeMap<String,Object>();var bindings=new TreeMap<String,String>();
        sources.forEach(b->bindings.put(b.sourceKey(),b.id().toString()));
        map.put("namespace",settings.namespace());map.put("serviceAccount",settings.serviceAccount());map.put("controlPlane",settings.controlPlane().toString());
        if(!settings.caConfigMap().isEmpty())map.put("caConfigMap",settings.caConfigMap());
        map.put("serviceProfileVersionId",vd.serviceProfileVersionId().toString());map.put("sources",bindings);
        map.put("placementMode",vd.placement().mode().name());map.put("targetNodeId",vd.placement().nodeId()==null?null:vd.placement().nodeId().toString());map.put("targetNodeName",nodeName);
        map.put("maxConcurrentTasks",spec.maxConcurrentTasks());map.put("startupSeconds",spec.startupTimeoutSeconds());map.put("drainSeconds",spec.drainTimeoutSeconds());
        String result=JSON.canonical(map);launch(vd.id(),UUID.randomUUID(),1,result);return result;
    }
    public static Map<?,?> read(String document) {
        var value=parameters(JSON.decode(document));
        var fields=new ArrayList<>(List.of("namespace","serviceAccount","controlPlane","serviceProfileVersionId","sources","placementMode","targetNodeId","targetNodeName","maxConcurrentTasks","startupSeconds","drainSeconds"));
        if(value.containsKey("caConfigMap")){io.edgeai.domain.runtime.RuntimeNames.dns(text(value.get("caConfigMap"),253),253);fields.add("caConfigMap");}
        return object(value,fields.toArray(String[]::new));
    }
    public static String digest(String document) { return JSON.digest("edgeai-vd-runtime-configuration-v1",read(document)); }
    public static VDRuntimeLaunch launch(UUID vd,UUID runtime,long generation,String document) {
        var m=read(document);
        return new VDRuntimeLaunch(vd,runtime,generation,text(m.get("namespace"),63),text(m.get("serviceAccount"),253),URI.create(text(m.get("controlPlane"),2048)),
            m.get("targetNodeId")==null?null:uuid(m.get("targetNodeId")),m.get("targetNodeName")==null?null:text(m.get("targetNodeName"),253),
            integer(m.get("maxConcurrentTasks")),integer(m.get("startupSeconds")),integer(m.get("drainSeconds")),caConfigMap(m));
    }
    public static VDRuntimeLaunch launch(VDRuntime runtime) {
        if(!digest(runtime.configurationJson()).equals(runtime.configurationDigest()))throw new IllegalArgumentException("Runtime configuration digest mismatch");
        return launch(runtime.vdId(),runtime.id(),runtime.generation(),runtime.configurationJson());
    }
    public static RuntimeSettings settings(String document) {
        var m=read(document);return new RuntimeSettings(text(m.get("namespace"),63),text(m.get("serviceAccount"),253),URI.create(text(m.get("controlPlane"),2048)),120,caConfigMap(m));
    }
    private static String caConfigMap(Map<?,?> value){return value.containsKey("caConfigMap")?text(value.get("caConfigMap"),253):"";}
    private static int integer(Object value) { return new BigDecimal(value.toString()).intValueExact(); }
}
