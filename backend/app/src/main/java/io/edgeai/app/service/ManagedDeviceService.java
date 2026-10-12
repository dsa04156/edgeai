package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.repository.DeviceRepository;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.DeviceProfileCompiler.*;

@Service
public class ManagedDeviceService {
    private final ObjectProvider<DeviceResourceGateway> gateways;
    private final String ns,broker,armImage,amdImage,sensehatImage;
    private final Clock clock;
    private final DeviceRepository devices;
    public ManagedDeviceService(ObjectProvider<DeviceResourceGateway> gateways,Clock clock,
        @Value("${edgeai.devices.namespace:edgeai-devices}") String ns,@Value("${edgeai.devices.data-broker:tcp://127.0.0.1:1883}") String broker,
        @Value("${edgeai.devices.mapper-arm64:}") String armImage,@Value("${edgeai.devices.mapper-amd64:}") String amdImage,@Value("${edgeai.devices.mapper-sensehat-arm64:}") String sensehatImage,DeviceRepository devices){this.devices=devices;this.gateways=gateways;this.clock=clock;this.ns=ns;this.broker=broker;this.armImage=armImage;this.amdImage=amdImage;this.sensehatImage=sensehatImage;}
    private String path(String resource){return "/apis/"+API+"/namespaces/"+ns+"/"+resource;}
    private String core(String resource){return "/api/v1/namespaces/"+ns+"/"+resource;}
    private String deployments(){return "/apis/apps/v1/namespaces/"+ns+"/deployments";}
    private DeviceResourceGateway gateway(){var g=gateways.getIfAvailable();if(g==null)throw error(503,"DEVICE_MANAGER_DISABLED","Kubernetes 연결이 설정되지 않았습니다.");return g;}
    private Map<String,Object> request(String method,String path,Object value,int... expected){
        DeviceResourceGateway.Reply reply;
        try{reply=gateway().request(method,path,value);}catch(ControlPlaneException e){throw e;}catch(RuntimeException e){throw error(503,"DEVICE_CONTROL_UNAVAILABLE","KubeEdge API에 연결할 수 없습니다.");}
        if(Arrays.stream(expected).noneMatch(n->n==reply.status()))throw error(reply.status()==404?404:reply.status()==409?409:503,"DEVICE_RESOURCE_ERROR","KubeEdge 리소스 요청을 완료할 수 없습니다.");
        return map(JSON.decode(reply.document()));
    }
    private Map<String,Object> optional(String path){
        var r=gateway().request("GET",path,null);if(r.status()==404)return null;
        if(r.status()!=200)throw error(503,"DEVICE_CONTROL_UNAVAILABLE","KubeEdge 상태를 읽을 수 없습니다.");return map(JSON.decode(r.document()));
    }
    private static void owned(Map<String,Object> resource){var labels=map(map(resource.get("metadata")).getOrDefault("labels",Map.of()));if(!OWNER.equals(labels.get("app.kubernetes.io/part-of")))throw error(409,"DEVICE_OWNERSHIP_CONFLICT","다른 시스템이 관리하는 리소스입니다.");}
    private Map<String,Object> node(String node){var resource=request("GET","/api/v1/nodes/"+name(node),null,200);var labels=map(map(resource.get("metadata")).getOrDefault("labels",Map.of()));if(!labels.containsKey("node-role.kubernetes.io/edge"))throw error(400,"EDGE_NODE_REQUIRED","KubeEdge Edge Node를 선택하세요.");return resource;}
    private void ensure(String collection,String id,Map<String,Object> document){
        var previous=optional(collection+"/"+id);
        if(previous!=null){owned(previous);return;}
        var reply=gateway().request("POST",collection,document);
        if(reply.status()!=201){var concurrent=optional(collection+"/"+id);if(concurrent!=null){owned(concurrent);return;}throw error(503,"DEVICE_APPLY_FAILED","KubeEdge 리소스를 생성하지 못했습니다.");}
    }
    private void mapper(String node,Map<String,Object> nodeResource,String protocol){
        var labels=map(map(nodeResource.get("metadata")).get("labels"));String arch=Objects.toString(labels.get("kubernetes.io/arch"));
        String image=arch.equals("arm64")?armImage:arch.equals("amd64")?amdImage:"";
        if(protocol.equals("sensehat")){if(!arch.equals("arm64"))throw error(400,"UNSUPPORTED_HARDWARE_NODE","Sense HAT은 ARM64 Raspberry Pi 노드에서 지원합니다.");image=sensehatImage;}
        if(image.isBlank())throw error(503,"MAPPER_IMAGE_NOT_CONFIGURED","대상 아키텍처의 Mapper 이미지가 설정되지 않았습니다.");
        String key=MapperDeployment.nameFor(node,protocol);
        ensure(core("configmaps"),key,MapperDeployment.config(node,ns,protocol));ensure(deployments(),key,MapperDeployment.deployment(node,ns,image,protocol));
    }
    public Map<String,Object> create(String body){
        var compiled=compile(JSON.parse(body,65536),ns,broker);
        if(optional(path("devices")+"/"+compiled.id())!=null)throw error(409,"DEVICE_ALREADY_EXISTS","같은 이름의 장치가 등록되어 있습니다.");
        var node=node(compiled.node());mapper(compiled.node(),node,(String)compiled.profile().get("protocol"));
        ensure(path("devicemodels"),compiled.model(),compiled.modelResource());
        request("POST",path("devices"),compiled.deviceResource(),201);
        return get(compiled.id());
    }
    @Transactional
    public Map<String,Object> update(String id,String body){
        var compiled=compile(JSON.parse(body,65536),ns,broker);if(!name(id).equals(compiled.id()))throw new IllegalArgumentException("Device name cannot change");
        devices.lockManagedDeviceReferences(List.of(id));
        var old=request("GET",path("devices")+"/"+id,null,200);owned(old);
        if(!Objects.equals(compiled.model(),map(map(old.get("spec")).get("deviceModelRef")).get("name")))requireUnused(id);
        mapper(compiled.node(),node(compiled.node()),(String)compiled.profile().get("protocol"));ensure(path("devicemodels"),compiled.model(),compiled.modelResource());
        var replacement=new TreeMap<>(compiled.deviceResource());var metadata=map(replacement.get("metadata"));metadata.put("resourceVersion",map(old.get("metadata")).get("resourceVersion"));
        // Status remains the controller's responsibility. The API replaces spec with optimistic concurrency.
        request("PUT",path("devices")+"/"+id,replacement,200);return get(id);
    }
    @Transactional
    public void delete(String id){
        String key=name(id);devices.lockManagedDeviceReferences(List.of(key));var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        requireUnused(key);deleteResource(key,old);
    }
    private void deleteResource(String key,Map<String,Object> old){
        var meta=map(old.get("metadata"));request("DELETE",path("devices")+"/"+key,Map.of("apiVersion","v1","kind","DeleteOptions","preconditions",Map.of("uid",meta.get("uid"),"resourceVersion",meta.get("resourceVersion"))),200,202);
    }
    @Transactional
    public Map<String,Object> addProperty(String id,String body){
        String key=name(id);devices.lockManagedDeviceReferences(List.of(key));var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        var meta=map(old.get("metadata"));
        var profile=map(JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}"))));
        var added=properties((String)profile.get("protocol"),List.of(JSON.parse(body,65536))).getFirst();
        var existing=(List<?>)profile.getOrDefault("properties",List.of());
        if(existing.stream().anyMatch(p->added.get("name").equals(map(p).get("name"))))throw error(409,"DEVICE_PROPERTY_ALREADY_EXISTS","이미 등록된 측정 항목입니다. 목록을 새로고침하세요.");
        var combined=new ArrayList<Object>(existing);combined.add(added);
        replaceProperties(key,old,profile,combined);return get(key);
    }
    @Transactional
    public Map<String,Object> updateProperty(String id,String property,String body){
        String key=name(id),field=WorkflowInput.text(property,63);
        if(!field.matches("[a-z][a-z0-9_]*")||field.equals("connection"))throw new IllegalArgumentException("Invalid property name");
        devices.lockManagedDeviceReferences(List.of(key));
        var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        var meta=map(old.get("metadata"));
        var profile=map(JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}"))));
        var existing=(List<?>)profile.getOrDefault("properties",List.of());
        if(existing.stream().noneMatch(p->field.equals(map(p).get("name"))))throw error(404,"DEVICE_PROPERTY_NOT_FOUND","측정 항목이 없습니다. 목록을 새로고침하세요.");
        var updated=properties((String)profile.get("protocol"),List.of(JSON.parse(body,65536))).getFirst();
        if(!field.equals(updated.get("name")))throw new IllegalArgumentException("Property name cannot change");
        var combined=existing.stream().map(p->field.equals(map(p).get("name"))?updated:p).toList();
        replaceProperties(key,old,profile,combined);return get(key);
    }
    @Transactional
    public void deleteProperty(String id,String property){
        String key=name(id),field=WorkflowInput.text(property,63);
        if(!field.matches("[a-z][a-z0-9_]*")||field.equals("connection"))throw new IllegalArgumentException("Invalid property name");
        devices.lockManagedDeviceReferences(List.of(key));
        var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        var meta=map(old.get("metadata"));
        var profile=map(JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}"))));
        var properties=(List<?>)profile.getOrDefault("properties",List.of());
        var remaining=properties.stream().filter(p->!field.equals(map(p).get("name"))).toList();
        if(remaining.size()==properties.size())throw error(404,"DEVICE_PROPERTY_NOT_FOUND","측정 항목이 없습니다. 목록을 새로고침하세요.");
        if(remaining.isEmpty()){requireUnused(key);deleteResource(key,old);return;}
        replaceProperties(key,old,profile,remaining);
    }
    private void replaceProperties(String key,Map<String,Object> old,Map<String,Object> profile,List<?> properties){
        var meta=map(old.get("metadata"));var input=new TreeMap<>(profile);input.put("properties",properties);
        var compiled=compile(input,ns,broker);if(!Objects.equals(compiled.model(),map(map(old.get("spec")).get("deviceModelRef")).get("name")))requireUnused(key);ensure(path("devicemodels"),compiled.model(),compiled.modelResource());
        var replacement=new TreeMap<>(old);replacement.remove("status");replacement.put("spec",compiled.deviceResource().get("spec"));
        var metadata=new TreeMap<>(meta);var annotations=new TreeMap<>(map(meta.getOrDefault("annotations",Map.of())));
        annotations.put("edgeai.io/device-profile",JSON.canonical(compiled.profile()));metadata.put("annotations",annotations);replacement.put("metadata",metadata);
        // Use the version read above so concurrent edits are rejected rather than overwritten.
        request("PUT",path("devices")+"/"+key,replacement,200);
    }
    public Map<String,Object> list(){
        var result=new ArrayList<Map<String,Object>>();var root=request("GET",path("devices")+"?labelSelector=app.kubernetes.io%2Fpart-of%3Dedgeai",null,200);
        for(var item:(List<?>)root.getOrDefault("items",List.of()))result.add(view(map(item)));
        result.sort(Comparator.comparing(d->(String)d.get("id")));return Map.of("items",result);
    }
    public Map<String,Object> get(String id){var resource=request("GET",path("devices")+"/"+name(id),null,200);owned(resource);return view(resource);}
    private void requireUnused(String id) {
        if(devices.hasHistoricalManagedDeviceReferences(id))throw error(409,"DEVICE_IN_USE","과거 VD 원본이나 실행 이력이 참조하는 센서입니다. 기존 버전의 연결·실행 종료 여부를 확인하세요.");
    }
    private Map<String,Object> view(Map<String,Object> resource){
        var meta=map(resource.get("metadata"));String id=(String)meta.get("name");var spec=map(resource.get("spec"));
        var profile=JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}")));
        var statusResource=optional(path("devicestatuses")+"/"+id);var status=statusResource==null?map(resource.getOrDefault("status",Map.of())):map(statusResource.getOrDefault("status",Map.of()));
        var propertyNames=new HashSet<String>();for(var property:(List<?>)spec.getOrDefault("properties",List.of()))propertyNames.add(Objects.toString(map(property).get("name")));
        var values=new TreeMap<String,Object>();long lastSeen=0;
        for(var value:(List<?>)status.getOrDefault("twins",List.of())){var twin=map(value);var reported=map(twin.getOrDefault("reported",Map.of()));var metadata=map(reported.getOrDefault("metadata",Map.of()));
            long timestamp=0;try{timestamp=Long.parseLong(Objects.toString(metadata.getOrDefault("timestamp","0")));}catch(NumberFormatException ignored){}
            String property=Objects.toString(twin.get("propertyName"));if(!property.equals("connection")&&propertyNames.contains(property)){lastSeen=Math.max(lastSeen,timestamp);values.put(property,Map.of("value",Objects.toString(reported.getOrDefault("value","")),"timestamp",timestamp));}
        }
        String state=Objects.toString(status.getOrDefault("state","UNKNOWN")).toUpperCase(Locale.ROOT);
        String observedState=state,reason="DMI";
        long heartbeat=lastSeen;try{heartbeat=Math.max(heartbeat,Instant.parse(Objects.toString(status.get("lastOnlineTime"))).toEpochMilli());}catch(java.time.format.DateTimeParseException ignored){}
        if(state.equals("ONLINE")&&(heartbeat==0||clock.millis()-heartbeat>30000)){state="OFFLINE";reason="STALE_DMI_REPORT";}
        String protocol=Objects.toString(map(spec.getOrDefault("protocol",Map.of())).getOrDefault("protocolName",PROTOCOL)).replaceFirst("^edgeai-","");
        String mapper=MapperDeployment.nameFor((String)spec.get("nodeName"),protocol);var deployment=optional(deployments()+"/"+mapper);
        boolean mapperReady=deployment!=null&&((Number)map(deployment.getOrDefault("status",Map.of())).getOrDefault("readyReplicas",0)).intValue()>0;
        if(!mapperReady){state="OFFLINE";reason="MAPPER_NOT_READY";}
        var result=new TreeMap<String,Object>();result.put("id",id);result.put("uid",meta.get("uid"));result.put("profile",profile);result.put("model",map(spec.get("deviceModelRef")).get("name"));result.put("targetNode",spec.get("nodeName"));result.put("state",state);result.put("observedState",observedState);result.put("stateReason",reason);result.put("lastSeen",lastSeen);result.put("properties",values);result.put("mapper",Map.of("name",mapper,"ready",mapperReady));result.put("resourceVersion",meta.get("resourceVersion"));return result;
    }
    public Map<String,Object> protocols(){return Map.of("items",List.of(Map.of("id","modbus-tcp","available",true,"kind","physical"),Map.of("id","opc-ua","available",true,"kind","physical"),Map.of("id","mqtt","available",true,"kind","physical"),Map.of("id","virtual","available",true,"kind","virtual"),Map.of("id","serial-json","available",true,"kind","physical"),Map.of("id","sensehat","available",!sensehatImage.isBlank(),"kind","physical")));}
    public Map<String,Object> profiles(){var root=request("GET",core("configmaps")+"?labelSelector=edgeai.io%2Fdevice-profile%3Dtrue",null,200);var items=new ArrayList<Object>();for(var item:(List<?>)root.getOrDefault("items",List.of()))items.add(JSON.decode((String)map(map(item).get("data")).get("profile.json")));return Map.of("items",items);}
    public Map<String,Object> createProfile(String body){
        var input=map(JSON.parse(body,65536));fields(input,Set.of("name","protocol","properties"),Set.of("name","protocol","properties"));String key=name(input.get("name"));
        String protocol=WorkflowInput.text(input.get("protocol"),32);mapperProtocol(protocol);
        var profile=Map.<String,Object>of("name",key,"protocol",protocol,"properties",properties(protocol,input.get("properties")));
        var metadata=metadata("edgeai-profile-"+key,ns);metadata.put("labels",Map.of("app.kubernetes.io/part-of",OWNER,"app.kubernetes.io/managed-by","edgeai-device-manager","edgeai.io/device-profile","true"));
        request("POST",core("configmaps"),Map.of("apiVersion","v1","kind","ConfigMap","metadata",metadata,"data",Map.of("profile.json",JSON.canonical(profile))),201);return profile;
    }
    public Map<String,Object> testConnection(String body){
        var compiled=compile(JSON.parse(body,65536),ns,broker);String protocol=(String)compiled.profile().get("protocol");var node=node(compiled.node());mapper(compiled.node(),node,protocol);
        var nodeStatus=map(node.get("status"));String address=null;for(var item:(List<?>)nodeStatus.get("addresses")){var a=map(item);if(a.get("type").equals("InternalIP"))address=(String)a.get("address");}
        if(address==null)throw error(503,"MAPPER_UNAVAILABLE","노드 주소를 확인할 수 없습니다.");
        var spec=map(compiled.deviceResource().get("spec"));var first=map(((List<?>)spec.get("properties")).getFirst());
        var input=Map.of("connection",map(spec.get("protocol")).get("configData"),"property",map(first.get("visitors")).get("configData"));
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()){
            var request=HttpRequest.newBuilder(URI.create("http://"+address+":"+mapperPort(protocol)+"/connections/test")).timeout(Duration.ofSeconds(6)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.canonical(input))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200&&response.statusCode()!=422)throw error(503,"MAPPER_UNAVAILABLE","Mapper 연결 확인을 완료할 수 없습니다.");return map(JSON.parse(response.body(),16384));
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw error(503,"MAPPER_UNAVAILABLE","연결 확인이 중단되었습니다.");}catch(ControlPlaneException e){throw e;}catch(Exception e){throw error(503,"MAPPER_UNAVAILABLE","대상 Edge Mapper에 연결할 수 없습니다. 준비 상태를 확인하세요.");}
    }
    public Map<String,Object> workflowDevice(Object input){
        var ref=map(input);fields(ref,Set.of("id","outputs"),Set.of("id","outputs"));
        if(!(ref.get("outputs") instanceof List<?> outputs)||outputs.isEmpty()||outputs.size()>64)throw new IllegalArgumentException("Device outputs required");
        var device=get(name(ref.get("id")));if(!device.get("state").equals("ONLINE"))throw error(409,"DEVICE_OFFLINE","Workflow 입력 장치가 OFFLINE입니다.");
        var properties=map(device.get("properties"));var values=new TreeMap<String,Object>();
        for(var output:outputs){String key=WorkflowInput.text(output,63);if(!properties.containsKey(key))throw error(400,"DEVICE_PROPERTY_NOT_FOUND","장치 property가 없습니다.");values.put(key,map(properties.get(key)).get("value"));}
        return Map.of("id",device.get("id"),"values",values,"observedAt",device.get("lastSeen"));
    }
    private static ControlPlaneException error(int status,String code,String message){return new ControlPlaneException(status,code,message);}
}
