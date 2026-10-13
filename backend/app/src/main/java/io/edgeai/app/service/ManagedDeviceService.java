package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.repository.VirtualDeviceRepository;
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
    private final VirtualDeviceRepository vds;
    private final io.edgeai.domain.repository.DeviceRepository devices;
    public ManagedDeviceService(ObjectProvider<DeviceResourceGateway> gateways,Clock clock,
        @Value("${edgeai.devices.namespace:edgeai-devices}") String ns,@Value("${edgeai.devices.data-broker:tcp://127.0.0.1:1883}") String broker,
        @Value("${edgeai.devices.mapper-arm64:}") String armImage,@Value("${edgeai.devices.mapper-amd64:}") String amdImage,@Value("${edgeai.devices.mapper-sensehat-arm64:}") String sensehatImage,VirtualDeviceRepository vds,io.edgeai.domain.repository.DeviceRepository devices){this.devices=devices;this.vds=vds;this.gateways=gateways;this.clock=clock;this.ns=ns;this.broker=broker;this.armImage=armImage;this.amdImage=amdImage;this.sensehatImage=sensehatImage;}
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
        if(((List<?>)compiled.profile().get("properties")).size()!=1)throw new IllegalArgumentException("Register exactly one measurement per sensor");
        if(optional(path("devices")+"/"+compiled.id())!=null)throw error(409,"DEVICE_ALREADY_EXISTS","같은 이름의 장치가 등록되어 있습니다.");
        var node=node(compiled.node());mapper(compiled.node(),node,(String)compiled.profile().get("protocol"));
        ensure(path("devicemodels"),compiled.model(),compiled.modelResource());
        request("POST",path("devices"),io.edgeai.app.support.ObservationIdentity.qualify(compiled.deviceResource(),null),201);
        return get(compiled.id());
    }
    @Transactional
    public Map<String,Object> update(String id,String body){
        var compiled=compile(JSON.parse(body,65536),ns,broker);if(!name(id).equals(compiled.id()))throw new IllegalArgumentException("Device name cannot change");
        if(((List<?>)compiled.profile().get("properties")).size()!=1)throw new IllegalArgumentException("Register exactly one measurement per sensor");
        vds.lockManagedSources(List.of(id));
        var old=request("GET",path("devices")+"/"+id,null,200);owned(old);
        if(!Objects.equals(compiled.model(),map(map(old.get("spec")).get("deviceModelRef")).get("name")))requireUnused(id);
        mapper(compiled.node(),node(compiled.node()),(String)compiled.profile().get("protocol"));ensure(path("devicemodels"),compiled.model(),compiled.modelResource());
        var replacement=new TreeMap<>(compiled.deviceResource());var metadata=map(replacement.get("metadata"));metadata.put("resourceVersion",map(old.get("metadata")).get("resourceVersion"));
        configurationTimestamp(metadata,old,compiled.profile());
        // Status remains the controller's responsibility. The API replaces spec with optimistic concurrency.
        request("PUT",path("devices")+"/"+id,io.edgeai.app.support.ObservationIdentity.qualify(replacement,old),200);return get(id);
    }
    @Transactional
    public void delete(String id){
        String key=name(id);vds.lockManagedSources(List.of(key));var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        requireUnused(key);deleteResource(key,old);
    }
    private void deleteResource(String key,Map<String,Object> old){
        var meta=map(old.get("metadata"));request("DELETE",path("devices")+"/"+key,Map.of("apiVersion","v1","kind","DeleteOptions","preconditions",Map.of("uid",meta.get("uid"),"resourceVersion",meta.get("resourceVersion"))),200,202);
    }
    @Transactional
    public Map<String,Object> addProperty(String id,String body){
        String key=name(id);vds.lockManagedSources(List.of(key));var old=request("GET",path("devices")+"/"+key,null,200);owned(old);
        var meta=map(old.get("metadata"));
        var profile=map(JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}"))));
        var added=properties((String)profile.get("protocol"),List.of(JSON.parse(body,65536))).getFirst();
        var existing=(List<?>)profile.getOrDefault("properties",List.of());
        if(existing.stream().anyMatch(p->added.get("name").equals(map(p).get("name"))))throw error(409,"DEVICE_PROPERTY_ALREADY_EXISTS","이미 등록된 측정 항목입니다. 목록을 새로고침하세요.");
        String source=name(profile.getOrDefault("sourceName",map(profile.getOrDefault("protocolConfig",Map.of())).getOrDefault("deviceId",key)));
        String measurement=source+"-"+added.get("name").toString().replace('_','-');
        if(measurement.length()>63||measurement.endsWith("-"))measurement=measurement.substring(0,Math.min(54,measurement.length()))+"-"+JSON.digest("edgeai-measurement-name/v1",measurement).substring(7,15);
        var input=new TreeMap<>(profile);input.put("name",measurement);input.put("sourceName",source);input.put("properties",List.of(added));
        return create(JSON.canonical(input));
    }
    @Transactional
    public Map<String,Object> updateProperty(String id,String property,String body){
        String key=name(id),field=WorkflowInput.text(property,63);
        if(!field.matches("[a-z][a-z0-9_]*")||field.equals("connection"))throw new IllegalArgumentException("Invalid property name");
        vds.lockManagedSources(List.of(key));
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
        vds.lockManagedSources(List.of(key));
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
        configurationTimestamp(metadata,old,compiled.profile());
        // Use the version read above so concurrent edits are rejected rather than overwritten.
        request("PUT",path("devices")+"/"+key,io.edgeai.app.support.ObservationIdentity.qualify(replacement,old),200);
    }
    private void configurationTimestamp(Map<String,Object> metadata,Map<String,Object> old,Map<String,Object> profile) {
        var previous=map(map(old.get("metadata")).getOrDefault("annotations",Map.of()));
        var annotations=new TreeMap<>(map(metadata.getOrDefault("annotations",Map.of())));
        String before=Objects.toString(previous.get("edgeai.io/device-profile"),"{}");
        String changed=Objects.toString(previous.get("edgeai.io/configuration-updated-at"),"");
        if(!JSON.canonical(JSON.decode(before)).equals(JSON.canonical(profile)))changed=clock.instant().toString();
        if(!changed.isEmpty())annotations.put("edgeai.io/configuration-updated-at",changed);
        metadata.put("annotations",annotations);
    }
    public Map<String,Object> list(){
        var result=new ArrayList<Map<String,Object>>();var root=request("GET",path("devices")+"?labelSelector=app.kubernetes.io%2Fpart-of%3Dedgeai",null,200);
        for(var item:(List<?>)root.getOrDefault("items",List.of()))result.add(view(map(item)));
        result.sort(Comparator.comparing(d->(String)d.get("id")));return Map.of("items",result);
    }
    /** Metadata only: does not read DeviceStatus, test a sensor connection, or trigger collection. */
    public List<Map<String,Object>> observationCatalog(Map<String,Object> overrides) {
        var result=new ArrayList<Map<String,Object>>();var nodes=new HashMap<String,Map<String,Object>>();
        var root=request("GET",path("devices")+"?labelSelector=app.kubernetes.io%2Fpart-of%3Dedgeai",null,200);
        var items=(List<?>)root.getOrDefault("items",List.of());if(items.size()>500)throw error(409,"CONTINUOUS_CAPACITY_EXCEEDED","센서 측정 항목이 검증된 입력 용량을 초과합니다.");
        for(var raw:items) {
            var device=map(raw);owned(device);var metadata=map(device.get("metadata"));String id=(String)metadata.get("name");
            try {
                String nodeName=(String)map(device.get("spec")).get("nodeName");
                var node=nodes.computeIfAbsent(nodeName,key->request("GET","/api/v1/nodes/"+name(key),null,200));
                result.add(ObservationRoute.resolve(device,node,overrides));
            } catch(IllegalArgumentException | ControlPlaneException e) {
                var unavailable=new TreeMap<String,Object>();
                try{unavailable.putAll(ObservationRoute.metadata(device));}catch(IllegalArgumentException ignored){unavailable.put("deviceId",id);unavailable.put("deviceUid",Objects.toString(metadata.get("uid"),""));}
                unavailable.put("reason","OBSERVATION_ROUTE_UNAVAILABLE");result.add(unavailable);
            }
        }
        return result;
    }
    /** Called only by an authenticated, CSRF-protected registration/update transaction. */
    @Transactional
    public void prepareObservations(Collection<String> ids) {
        var ordered=ids.stream().distinct().sorted().toList();vds.lockManagedSources(ordered);
        for(var id:ordered) {
            var device=request("GET",path("devices")+"/"+name(id),null,200);owned(device);
            var qualified=ObservationIdentity.qualify(device,device);
            if(!JSON.canonical(device).equals(JSON.canonical(qualified))) {
                qualified.remove("status");request("PUT",path("devices")+"/"+name(id),qualified,200);
            }
        }
    }
    public Map<String,Object> get(String id){var resource=request("GET",path("devices")+"/"+name(id),null,200);owned(resource);return view(resource);}
    private void requireUnused(String id) {
        if(devices.hasHistoricalManagedDeviceReferences(id))throw error(409,"DEVICE_IN_USE","과거 실행 이력이 참조하는 센서입니다. 기존 연결·실행 종료를 확인하세요.");
        if(vds.managedSourceInUse(id))throw error(409,"DEVICE_IN_USE","VD 원본으로 사용 중인 센서입니다. VD 원본을 교체하고 이전 실행의 종료를 확인하세요.");
    }
    public Map<String,Object> source(String id,String model,UUID uid) {
        var device=get(id);
        if(!Objects.equals(model,device.get("model")) || uid!=null && !uid.toString().equals(device.get("uid")))
            throw error(409,"VD_SOURCE_INCOMPATIBLE","센서 DeviceModel 또는 UID가 변경됐습니다. 원본을 다시 선택하세요.");
        if(device.get("uid")==null)throw error(503,"DEVICE_IDENTITY_UNAVAILABLE","센서 UID를 확인할 수 없습니다.");
        return device;
    }
    private Map<String,Object> view(Map<String,Object> resource){
        var meta=map(resource.get("metadata"));String id=(String)meta.get("name");var spec=map(resource.get("spec"));
        var profile=JSON.decode(Objects.toString(map(meta.getOrDefault("annotations",Map.of())).getOrDefault("edgeai.io/device-profile","{}")));
        var statusResource=optional(path("devicestatuses")+"/"+id);var status=statusResource==null?map(resource.getOrDefault("status",Map.of())):map(statusResource.getOrDefault("status",Map.of()));
        var propertyNames=new HashSet<String>();for(var property:(List<?>)spec.getOrDefault("properties",List.of()))propertyNames.add(Objects.toString(map(property).get("name")));
        var values=new TreeMap<String,Object>();long lastSeen=0;
        for(var value:(List<?>)status.getOrDefault("twins",List.of())){var twin=map(value);var reported=map(twin.getOrDefault("reported",Map.of()));var metadata=map(reported.getOrDefault("metadata",Map.of()));
            long timestamp=0;try{timestamp=Long.parseLong(Objects.toString(metadata.getOrDefault("timestamp","0")));}catch(NumberFormatException ignored){}
            String property=Objects.toString(twin.get("propertyName"));if(!property.equals("connection")&&propertyNames.contains(property)){
                String raw=Objects.toString(reported.getOrDefault("value",""));var reading=new TreeMap<String,Object>(Map.of("value",raw,"timestamp",timestamp));
                String configuration=Objects.toString(metadata.get("edgeaiConfigurationSha256"),"");if(!configuration.isEmpty())reading.put("configurationSha256",configuration);
                if(raw.startsWith("edgeai.measurement/v1:") && raw.length()<=512)try {
                    String decoded=new String(Base64.getUrlDecoder().decode(raw.substring("edgeai.measurement/v1:".length())),java.nio.charset.StandardCharsets.UTF_8);
                    var observation=map(JSON.parse(decoded,16384));
                    if("edgeai.measurement/v1".equals(observation.get("apiVersion")) && observation.keySet().equals(Set.of("apiVersion","value","configurationSha256","collectedAt"))
                        && observation.get("value") instanceof String sampleValue && observation.get("configurationSha256") instanceof String applied && applied.matches("sha256:[0-9a-f]{64}") && observation.get("collectedAt") instanceof Number collected) {
                        long sampleTime=new java.math.BigDecimal(collected.toString()).longValueExact();
                        reading.put("value",sampleValue);reading.put("configurationSha256",applied);reading.put("timestamp",sampleTime);
                    }
                }catch(IllegalArgumentException | ArithmeticException ignored){}
                lastSeen=Math.max(lastSeen,((Number)reading.get("timestamp")).longValue());values.put(property,reading);
            }
        }
        String state=Objects.toString(status.getOrDefault("state","UNKNOWN")).toUpperCase(Locale.ROOT);
        String observedState=state,reason="DMI";
        long heartbeat=lastSeen;try{heartbeat=Math.max(heartbeat,Instant.parse(Objects.toString(status.get("lastOnlineTime"))).toEpochMilli());}catch(java.time.format.DateTimeParseException ignored){}
        if(state.equals("ONLINE")&&(heartbeat==0||clock.millis()-heartbeat>30000)){state="OFFLINE";reason="STALE_DMI_REPORT";}
        String protocol=Objects.toString(map(spec.getOrDefault("protocol",Map.of())).getOrDefault("protocolName",PROTOCOL)).replaceFirst("^edgeai-","");
        String mapper=MapperDeployment.nameFor((String)spec.get("nodeName"),protocol);var deployment=optional(deployments()+"/"+mapper);
        boolean mapperReady=deployment!=null&&((Number)map(deployment.getOrDefault("status",Map.of())).getOrDefault("readyReplicas",0)).intValue()>0;
        if(!mapperReady){state="OFFLINE";reason="MAPPER_NOT_READY";}
        var result=new TreeMap<String,Object>();result.put("id",id);result.put("uid",meta.get("uid"));result.put("profile",profile);result.put("model",map(spec.get("deviceModelRef")).get("name"));result.put("targetNode",spec.get("nodeName"));result.put("state",state);result.put("observedState",observedState);result.put("stateReason",reason);result.put("lastSeen",lastSeen);result.put("properties",values);result.put("mapper",Map.of("name",mapper,"ready",mapperReady));result.put("resourceVersion",meta.get("resourceVersion"));result.put("configurationUpdatedAt",map(meta.getOrDefault("annotations",Map.of())).get("edgeai.io/configuration-updated-at"));
        for(var property:(List<?>)spec.getOrDefault("properties",List.of())) {
            var visitor=map(map(property).getOrDefault("visitors",Map.of()));var data=map(visitor.getOrDefault("configData",Map.of()));
            if(data.containsKey("configurationSha256")){result.put("configurationSha256",configuration(profile));break;}
        }
        return result;
    }
    public Map<String,Object> protocols(){return Map.of("items",List.of("modbus-tcp","opc-ua","mqtt","virtual","serial-json","sensehat").stream().map(protocol->DeviceProtocolCatalog.entry(protocol,protocol.equals("sensehat")?!sensehatImage.isBlank():!armImage.isBlank() || !amdImage.isBlank())).toList());}
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
