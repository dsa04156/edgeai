package io.edgeai.adapters.kubernetes;

import io.edgeai.domain.device.DeviceResourceGateway;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** Limited to the configured namespace, device CRDs, mapper deployments and node reads. */
public final class KubeEdgeDeviceResources implements DeviceResourceGateway, AutoCloseable {
    private final KubernetesRuntimeHttp http;
    private final String context, namespace;
    private final JsonMapper json = new JsonMapper();
    public KubeEdgeDeviceResources(String url,String token,String ca,String namespace) {
        this.http=new KubernetesRuntimeHttp(url,token,ca);this.context=null;this.namespace=namespace;
    }
    public KubeEdgeDeviceResources(String context,String namespace) {
        if(context.isBlank())throw new IllegalArgumentException("Fixed Kubernetes context required");
        this.http=null;this.context=context;this.namespace=namespace;
    }
    public Reply request(String method,String path,Object body) {
        if(!Set.of("GET","POST","PUT","DELETE").contains(method) || !allowed(path,method))
            throw new IllegalArgumentException("Unsupported device resource operation");
        if(http!=null) {var r=http.request(method,path,body,Duration.ofSeconds(5));return new Reply(r.status(),new String(r.body(),StandardCharsets.UTF_8));}
        var args=new ArrayList<>(List.of("kubectl","--context",context,"--request-timeout=5s"));
        if(method.equals("GET"))args.addAll(List.of("get","--raw",path));
        else if(method.equals("DELETE"))args.addAll(List.of("delete","--raw",path,"-f","-"));
        else args.addAll(List.of(method.equals("POST")?"create":"replace","-f","-","-o","json"));
        Process process=null;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            process=new ProcessBuilder(args).start();
            var running=process;
            var output=executor.submit(()->running.getInputStream().readNBytes(8*1024*1024+1));
            var errors=executor.submit(()->running.getErrorStream().readNBytes(65537));
            try(var stdin=process.getOutputStream()) {if(body!=null)stdin.write(json.writeValueAsBytes(body));}
            byte[] bytes=output.get(6,TimeUnit.SECONDS);
            if(bytes.length>8*1024*1024 || !process.waitFor(1,TimeUnit.SECONDS))throw new IllegalStateException("Device resources unavailable");
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(process.exitValue()!=0) {
                String failure=new String(errors.get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8);
                if(failure.contains("(NotFound)"))return new Reply(404,"{}");
                if(failure.contains("(AlreadyExists)")||failure.contains("(Conflict)"))return new Reply(409,"{}");
                if(failure.contains("(Forbidden)"))return new Reply(403,"{}");
                return new Reply(503,"{}");
            }
            return new Reply(method.equals("POST")?201:200,text.isBlank()?"{}":text);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Device resources interrupted");}
        catch(Exception e){throw new IllegalStateException("Device resources unavailable");}
        finally{if(process!=null)process.destroyForcibly();}
    }
    private boolean allowed(String path,String method) {
        if(method.equals("GET") && path.matches("/api/v1/nodes(/[a-z0-9.-]+)?"))return true;
        String ns="/namespaces/"+namespace+"/";
        return path.startsWith("/apis/devices.kubeedge.io/v1beta1"+ns) && path.substring(path.indexOf(ns)+ns.length()).matches("(devices|devicemodels|devicestatuses)(/[a-z0-9.-]+)?(\\?[^#]*)?")
            || path.startsWith("/api/v1"+ns) && path.substring(path.indexOf(ns)+ns.length()).matches("(configmaps|pods)(/[a-z0-9.-]+)?(\\?[^#]*)?")
            || path.startsWith("/apis/apps/v1"+ns) && path.substring(path.indexOf(ns)+ns.length()).matches("deployments(/[a-z0-9.-]+)?(\\?[^#]*)?");
    }
    public void close(){if(http!=null)http.close();}
}
