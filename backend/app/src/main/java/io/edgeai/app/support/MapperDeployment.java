package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.DeviceProfileCompiler.*;

/** Exactly one shared protocol Mapper deployment per edge node. */
public final class MapperDeployment {
    private MapperDeployment() {}
    public static String nameFor(String node){return nameFor(node,"modbus-tcp");}
    public static String nameFor(String node,String protocol){mapperProtocol(protocol);return "edgeai-"+(protocol.equals("modbus-tcp")?"modbus":protocol)+"-"+JSON.digest("edgeai-mapper-node",node).substring(7,23);}
    public static Map<String,Object> config(String node,String namespace,String protocol){
        String name=nameFor(node,protocol);
        String content="grpc_server:\n  socket_path: /etc/kubeedge/edgeai/"+name+".sock\ncommon:\n  name: "+name+"\n  version: 1.0.0\n  api_version: v1.0.0\n  protocol: "+mapperProtocol(protocol)+"\n  address: 127.0.0.1\n  edgecore_sock: /etc/kubeedge/dmi.sock\n  http_port: \""+mapperPort(protocol)+"\"\n";
        return Map.of("apiVersion","v1","kind","ConfigMap","metadata",metadata(name,namespace),"data",Map.of("config.yaml",content));
    }
    public static Map<String,Object> deployment(String node,String namespace,String image,String protocol){
        if(!image.matches("[^\\s]+@sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Mapper image digest required");
        String name=nameFor(node,protocol);
        var labels=Map.of("app.kubernetes.io/part-of",OWNER,"app.kubernetes.io/managed-by","edgeai-device-manager","edgeai.io/mapper",name);
        var container=new TreeMap<String,Object>();
        container.put("name","mapper");container.put("image",image);container.put("args",List.of("--config-file","/config/config.yaml"));
        container.put("env",List.of(Map.of("name","DEVICE_NAMESPACE","value",namespace),Map.of("name","DMI_BUDGET_FILE","value","/etc/kubeedge/edgeai/dmi-budget")));
        container.put("volumeMounts",List.of(Map.of("name","config","mountPath","/config","readOnly",true),Map.of("name","dmi","mountPath","/etc/kubeedge/dmi.sock"),Map.of("name","callbacks","mountPath","/etc/kubeedge/edgeai")));
        container.put("securityContext",Map.of("allowPrivilegeEscalation",false,"readOnlyRootFilesystem",true,"capabilities",Map.of("drop",List.of("ALL"))));
        container.put("resources",Map.of("requests",Map.of("cpu","25m","memory","32Mi"),"limits",Map.of("cpu","500m","memory","128Mi")));
        container.put("readinessProbe",Map.of("httpGet",Map.of("path","/readyz","port",mapperPort(protocol)),"periodSeconds",5));
        container.put("livenessProbe",Map.of("httpGet",Map.of("path","/healthz","port",mapperPort(protocol)),"periodSeconds",10));
        var mounts=new ArrayList<Object>((List<?>)container.get("volumeMounts"));
        var volumes=new ArrayList<Object>(List.of(Map.of("name","config","configMap",Map.of("name",name)),Map.of("name","dmi","hostPath",Map.of("path","/etc/kubeedge/dmi.sock","type","Socket")),Map.of("name","callbacks","hostPath",Map.of("path","/etc/kubeedge/edgeai","type","DirectoryOrCreate"))));
        if(Set.of("serial-json","sensehat").contains(protocol)){
            String hostPath=protocol.equals("serial-json")?"/run/edgeai/devices":"/dev/i2c-1",mountPath=protocol.equals("serial-json")?"/dev/edgeai":"/dev/i2c-1";
            volumes.add(Map.of("name","hardware","hostPath",Map.of("path",hostPath,"type",protocol.equals("serial-json")?"Directory":"CharDevice")));
            mounts.add(Map.of("name","hardware","mountPath",mountPath));volumes.add(Map.of("name","tmp","emptyDir",Map.of()));mounts.add(Map.of("name","tmp","mountPath","/tmp"));
            // These local character devices have no device plugin. Privileged is
            // necessary for the runtime device cgroup; network drivers keep their
            // existing restricted security context. No host root filesystem mount.
            container.put("securityContext",Map.of("privileged",true,"readOnlyRootFilesystem",true));
            container.put("resources",Map.of("requests",Map.of("cpu","25m","memory","64Mi"),"limits",Map.of("cpu","500m","memory","256Mi")));
        }
        if(protocol.equals("rtsp"))container.put("resources",Map.of("requests",Map.of("cpu","250m","memory","128Mi"),"limits",Map.of("cpu","2","memory","512Mi")));
        container.put("volumeMounts",mounts);
        var pod=Map.of("nodeName",node,"hostNetwork",true,"dnsPolicy","ClusterFirstWithHostNet","automountServiceAccountToken",false,"containers",List.of(container),"volumes",volumes);
        return Map.of("apiVersion","apps/v1","kind","Deployment","metadata",metadata(name,namespace),"spec",Map.of("replicas",1,"strategy",Map.of("type","Recreate"),"selector",Map.of("matchLabels",Map.of("edgeai.io/mapper",name)),"template",Map.of("metadata",Map.of("labels",labels),"spec",pod)));
    }
}
