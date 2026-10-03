package io.edgeai.adapters.kubernetes;

import java.util.*;

/** Deployment-owned public CA certificates; workloads cannot select arbitrary mounts or disable TLS. */
final class KubernetesTrustBundle {
    private KubernetesTrustBundle() {}
    static void mount(Map<String,Object> pod,Map<String,Object> container,String configMap) {
        if(configMap.isEmpty())return;
        var volumes=new ArrayList<Object>((List<?>)pod.get("volumes"));
        volumes.add(Map.of("name","edgeai-trust","configMap",Map.of("name",configMap,"defaultMode",292,
            "items",List.of(Map.of("key","ca.crt","path","ca.crt")))));
        pod.put("volumes",volumes);
        var mounts=new ArrayList<Object>((List<?>)container.get("volumeMounts"));
        mounts.add(Map.of("name","edgeai-trust","mountPath","/var/run/edgeai-trust","readOnly",true));container.put("volumeMounts",mounts);
        var env=new ArrayList<Object>((List<?>)container.get("env"));
        env.add(Map.of("name","SSL_CERT_FILE","value","/var/run/edgeai-trust/ca.crt"));container.put("env",env);
    }
}
