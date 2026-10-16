package io.edgeai.app.support;

import java.math.BigDecimal;
import java.util.*;

/** Validated user profile -> the installed KubeEdge v1beta1 schema. No device values are stored here. */
public final class DeviceProfileCompiler {
    public static final String API="devices.kubeedge.io/v1beta1", PROTOCOL="edgeai-modbus-tcp", OWNER="edgeai";
    public static final JsonDocuments JSON=new JsonDocuments();
    public record Compilation(String id,String node,String model,Map<String,Object> profile,Map<String,Object> modelResource,Map<String,Object> deviceResource) {}
    private DeviceProfileCompiler() {}
    public static Compilation compile(Object value,String namespace,String broker) {
        var input=map(value); fields(input,Set.of("name","sourceName","kind","protocol","host","port","protocolConfig","properties","collectionInterval","targetNode"),Set.of("name","protocol","properties","targetNode"));
        String id=name(input.get("name")),node=name(input.get("targetNode"));
        String kind=Objects.toString(input.getOrDefault("kind","physical"));
        String protocol=WorkflowInput.text(input.get("protocol"),32),mapperProtocol=mapperProtocol(protocol);
        if(!(protocol.equals("virtual")?kind.equals("virtual"):kind.equals("physical")))throw new IllegalArgumentException("Device kind does not match protocol");
        boolean local=Set.of("serial-json","sensehat").contains(protocol);
        if(local&&(input.containsKey("host")||input.containsKey("port")))throw new IllegalArgumentException("Local hardware uses devicePath, not host/port");
        String host=local?"":WorkflowInput.text(input.get("host"),253);
        if(!local&&!host.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*"))throw new IllegalArgumentException("Host must be an IP address or hostname");
        int defaultPort=switch(protocol){case "modbus-tcp"->502;case "opc-ua"->4840;case "mqtt"->1883;case "rtsp"->554;default->8080;};
        int port=integer(input.getOrDefault("port",defaultPort),1,65535),cycle=integer(input.getOrDefault("collectionInterval",1000),100,60000);
        var config=map(input.getOrDefault("protocolConfig",Map.of()));
        var allowed=new HashSet<String>(Set.of("timeoutMs"));
        switch(protocol){case "modbus-tcp"->allowed.add("unitId");case "opc-ua"->{allowed.add("path");allowed.add("securityMode");}case "mqtt"->allowed.add("staleMs");case "virtual","rtsp"->allowed.add("path");case "serial-json"->{allowed.addAll(Set.of("devicePath","deviceId","baudRate"));}case "sensehat"->allowed.addAll(Set.of("devicePath","deviceId"));default->throw new IllegalArgumentException("Protocol Mapper is not installed");}
        fields(config,allowed,Set.of());
        var normalizedConfig=new TreeMap<String,Object>();normalizedConfig.put("timeoutMs",integer(config.getOrDefault("timeoutMs",protocol.equals("rtsp")?5000:1000),100,5000));
        switch(protocol){
            case "modbus-tcp"->normalizedConfig.put("unitId",integer(config.getOrDefault("unitId",1),1,247));
            case "opc-ua"->{if(!Objects.toString(config.getOrDefault("securityMode","None")).equals("None"))throw new IllegalArgumentException("Only anonymous OPC-UA SecurityMode None is supported");normalizedConfig.put("securityMode","None");normalizedConfig.put("path",endpointPath(config.getOrDefault("path",""),false));}
            case "mqtt"->normalizedConfig.put("staleMs",integer(config.getOrDefault("staleMs",Math.min(60000,Math.max(5000,cycle*3))),100,60000));
            case "virtual"->normalizedConfig.put("path",endpointPath(config.getOrDefault("path","/properties"),true));
            case "rtsp"->{String path=endpointPath(config.getOrDefault("path","/"),true);if(path.startsWith("//"))throw new IllegalArgumentException("Absolute local RTSP stream path required");normalizedConfig.put("path",path);}
            case "serial-json","sensehat"->{
                String devicePath=Objects.toString(config.get("devicePath"),"");
                if(protocol.equals("serial-json")?!devicePath.matches("/dev/edgeai/[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}"):!devicePath.equals("/dev/i2c-1"))throw new IllegalArgumentException("Invalid hardware device path");
                normalizedConfig.put("devicePath",devicePath);normalizedConfig.put("deviceId",name(config.get("deviceId")));
                if(protocol.equals("serial-json")){int baud=integer(config.getOrDefault("baudRate",115200),9600,115200);if(!Set.of(9600,19200,38400,57600,115200).contains(baud))throw new IllegalArgumentException("Unsupported baud rate");normalizedConfig.put("baudRate",baud);}
            }
        }
        var connection=new TreeMap<String,Object>(normalizedConfig);if(!local){connection.put("host",host);connection.put("port",port);}
        var properties=properties(protocol,input.get("properties"));
        var modelProperties=new ArrayList<Map<String,Object>>();var deviceProperties=new ArrayList<Map<String,Object>>();
        for(var property:properties) {
            String key=(String)property.get("name"),type="STRING";
            // EdgeCore 1.23 drops arbitrary Twin metadata. Keep value, applied
            // configuration and collection time together in one string value.
            modelProperties.add(Map.of("name",key,"type",type,"description","edgeai.measurement/v1; wire type="+property.get("dataType"),"accessMode","ReadOnly","unit",property.get("unit")));
            var visitor=new TreeMap<>(property);visitor.remove("name");visitor.remove("unit");
            if(protocol.equals("modbus-tcp")){int register=((Number)visitor.remove("register")).intValue();String function=(String)visitor.get("function");int base=function.equals("input")?30001:function.equals("coil")?1:40001;visitor.put("address",register-base);}
            deviceProperties.add(Map.of("name",key,"desired",Map.of("value","","metadata",Map.of("type",type.toLowerCase(Locale.ROOT))),
                "visitors",Map.of("protocolName",mapperProtocol,"configData",visitor),"collectCycle",cycle,"reportCycle",5000,"reportToCloud",true,
                "pushMethod",Map.of("mqtt",Map.of("address",broker,"topic","edgeai/devices/"+id+"/"+key,"qos",1,"retained",false))));
        }
        var modelSpec=Map.<String,Object>of("protocol",mapperProtocol,"properties",modelProperties);
        String model="edgeai-model-"+JSON.digest("edgeai-device-model-v1",modelSpec).substring(7,31);
        var profile=new TreeMap<String,Object>();profile.putAll(input);profile.put("kind",kind);if(!local)profile.put("port",port);profile.put("collectionInterval",cycle);profile.put("protocolConfig",normalizedConfig);profile.put("properties",properties);
        profile.put("sourceName",name(input.getOrDefault("sourceName",normalizedConfig.getOrDefault("deviceId",id))));
        String configuration=configuration(profile);
        for(var property:deviceProperties)if(!property.get("name").equals("connection"))
            map(map(property.get("visitors")).get("configData")).put("configurationSha256",configuration);
        var metadata=metadata(id,namespace);metadata.put("annotations",Map.of("edgeai.io/device-profile",JSON.canonical(profile)));
        var spec=Map.of("deviceModelRef",Map.of("name",model),"nodeName",node,"protocol",Map.of("protocolName",mapperProtocol,"configData",connection),"properties",deviceProperties);
        return new Compilation(id,node,model,profile,resource("DeviceModel",metadata(model,namespace),modelSpec),resource("Device",metadata,spec));
    }
    public static String configuration(Object profile){return JSON.digest("edgeai.mapper-configuration/v1",profile);}
    public static List<Map<String,Object>> properties(Object value) {
        return properties("modbus-tcp",value);
    }
    public static List<Map<String,Object>> properties(String protocol,Object value) {
        mapperProtocol(protocol);
        if(!(value instanceof List<?> list)||list.isEmpty()||list.size()>64)throw new IllegalArgumentException("1..64 properties required");
        var result=new ArrayList<Map<String,Object>>();var names=new HashSet<String>();
        for(var item:list) {
            var p=map(item);
            String binding=switch(protocol){case "modbus-tcp"->"register";case "opc-ua"->"nodeId";case "mqtt"->"topic";default->"jsonPath";};
            var allowed=new HashSet<String>(Set.of("name","dataType","scale","offset","unit",binding));
            if(protocol.equals("modbus-tcp")){allowed.add("function");allowed.add("byteOrder");}else if(protocol.equals("mqtt"))allowed.add("jsonPath");
            if(protocol.equals("serial-json"))allowed.add("sensor");
            if(Set.of("mqtt","virtual").contains(protocol))allowed.add("timestampPath");
            fields(p,allowed,Set.of("name",binding,"dataType"));
            String key=WorkflowInput.text(p.get("name"),63);
            if(!key.matches("[a-z][a-z0-9_]*")||key.equals("connection")||!names.add(key))throw new IllegalArgumentException("Invalid or duplicate property name");
            String type=Objects.toString(p.get("dataType"));modelType(type);
            String unit=Objects.toString(p.getOrDefault("unit",""));if(unit.length()>64)throw new IllegalArgumentException("Unit exceeds limit");
            var normalized=new TreeMap<String,Object>(Map.of("name",key,"dataType",type,"unit",unit));
            if(protocol.equals("rtsp")){
                if(list.size()!=1||!key.equals("connected")||!type.equals("bool")||!Objects.equals(p.get("jsonPath"),"connected")||!unit.isEmpty()||!(p.getOrDefault("scale",1) instanceof Number scale)||scale.doubleValue()!=1||!(p.getOrDefault("offset",0) instanceof Number offset)||offset.doubleValue()!=0)throw new IllegalArgumentException("RTSP exposes only the connected boolean property");
                normalized.put("jsonPath","connected");
            }else if(protocol.equals("modbus-tcp")){
            if(type.equals("string"))throw new IllegalArgumentException("Modbus string decoding is not supported");
            String fn=Objects.toString(p.getOrDefault("function","holding"));if(!Set.of("holding","input","coil").contains(fn))throw new IllegalArgumentException("Invalid Modbus function");
            if(fn.equals("coil")&&!type.equals("bool"))throw new IllegalArgumentException("Coil requires bool");
            if(!fn.equals("coil")&&type.equals("bool"))throw new IllegalArgumentException("bool requires coil");
            int base=fn.equals("input")?30001:fn.equals("coil")?1:40001;
            int width=type.equals("float64")?4:Set.of("float32","int32","uint32").contains(type)?2:1;
            int register=integer(p.get("register"),base,base+65536-width);
            String order=Objects.toString(p.getOrDefault("byteOrder","ABCD"));if(!Set.of("ABCD","BADC","CDAB","DCBA").contains(order))throw new IllegalArgumentException("Invalid byte order");
            normalized.putAll(Map.of("register",register,"function",fn,"byteOrder",order));
            }else if(protocol.equals("opc-ua")){
                String nodeId=WorkflowInput.text(p.get("nodeId"),512);
                if(!nodeId.matches("(?:ns=[0-9]{1,5};)?(?:i=[0-9]+|s=[^\\p{Cntrl}]+|g=[0-9a-fA-F-]{36}|b=[A-Za-z0-9+/=]+)"))throw new IllegalArgumentException("Valid OPC-UA NodeId required");normalized.put("nodeId",nodeId);
            }else{
                if(protocol.equals("mqtt")){String topic=WorkflowInput.text(p.get("topic"),256);if(topic.contains("#")||topic.contains("+")||topic.startsWith("edgeai/devices/")||topic.startsWith("edgeai/observations/")||topic.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Exact MQTT input topic required; output topics are reserved");normalized.put("topic",topic);}
                String jsonPath=Objects.toString(p.getOrDefault("jsonPath",""));if(jsonPath.length()>256||(!jsonPath.isEmpty()&&!jsonPath.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*")))throw new IllegalArgumentException("Dot-separated JSON property path required");normalized.put("jsonPath",jsonPath);
                if(protocol.equals("serial-json")){String sensor=WorkflowInput.text(p.get("sensor"),63);if(!sensor.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")||jsonPath.isEmpty())throw new IllegalArgumentException("Serial sensor and JSON path required");normalized.put("sensor",sensor);}
                if(protocol.equals("sensehat")&&!Set.of("temp_humidity","temp_pressure","humidity","pressure","compass","pitch","roll","yaw","gyro_x","gyro_y","gyro_z").contains(jsonPath))throw new IllegalArgumentException("Unsupported Sense HAT property");
            }
            if(p.containsKey("timestampPath")) {
                String path=WorkflowInput.text(p.get("timestampPath"),128);
                if(!path.matches("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*"))throw new IllegalArgumentException("Invalid timestamp path");
                normalized.put("timestampPath",path);
            }
            for(var field:List.of("scale","offset")){Object v=p.getOrDefault(field,field.equals("scale")?1:0);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IllegalArgumentException("Finite scaling required");normalized.put(field,v);}
            result.add(normalized);
        }
        result.sort(Comparator.comparing(p->(String)p.get("name")));return result;
    }
    public static String mapperProtocol(String protocol){if(!Set.of("modbus-tcp","opc-ua","mqtt","virtual","serial-json","sensehat","rtsp").contains(protocol))throw new IllegalArgumentException("Protocol Mapper is not installed");return "edgeai-"+protocol;}
    public static int mapperPort(String protocol){return switch(protocol){case "modbus-tcp"->7777;case "opc-ua"->7778;case "mqtt"->7779;case "virtual"->7780;case "serial-json"->7781;case "sensehat"->7782;case "rtsp"->7783;default->throw new IllegalArgumentException("Protocol Mapper is not installed");};}
    private static String endpointPath(Object value,boolean required){String s=Objects.toString(value);if(s.length()>512||(required&&s.isEmpty())||(!s.isEmpty()&&!s.matches("/[a-zA-Z0-9_./-]*"))||s.contains(".."))throw new IllegalArgumentException("Absolute endpoint path required");return s;}
    private static String modelType(String type){return switch(type){case "float32"->"FLOAT";case "float64"->"DOUBLE";case "int16","uint16","int32","uint32"->"INT";case "bool"->"BOOLEAN";case "string"->"STRING";default->throw new IllegalArgumentException("Unsupported property data type");};}
    public static String name(Object value){String s=WorkflowInput.text(value,63);if(!s.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?"))throw new IllegalArgumentException("DNS device/node name required");return s;}
    public static int integer(Object v,int min,int max){try{int n=new BigDecimal(v.toString()).intValueExact();if(n<min||n>max)throw new IllegalArgumentException("Number out of range");return n;}catch(ArithmeticException|NullPointerException e){throw new IllegalArgumentException("Integer required");}}
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Object required");return (Map<String,Object>)value;}
    public static void fields(Map<String,Object> map,Set<String> allowed,Set<String> required){if(!allowed.containsAll(map.keySet())||!map.keySet().containsAll(required))throw new IllegalArgumentException("Unexpected or missing device fields");}
    public static Map<String,Object> metadata(String name,String namespace){return new TreeMap<>(Map.of("name",name,"namespace",namespace,"labels",Map.of("app.kubernetes.io/part-of",OWNER,"app.kubernetes.io/managed-by","edgeai-device-manager")));}
    public static Map<String,Object> resource(String kind,Map<String,Object> metadata,Map<String,Object> spec){return Map.of("apiVersion",API,"kind",kind,"metadata",metadata,"spec",spec);}
}
