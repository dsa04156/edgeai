package io.edgeai.app.support;

import java.util.*;

/** Discoverable adapter fields; calculation profiles remain protocol-independent. */
public final class DeviceProtocolCatalog {
    private DeviceProtocolCatalog() {}
    public static Map<String,Object> entry(String protocol,boolean configured) {
        DeviceProfileCompiler.mapperProtocol(protocol);
        var connection=new TreeMap<String,Object>();connection.put("timeoutMs",integer(100,5000,1000));
        var required=new ArrayList<String>();
        switch(protocol){
            case "modbus-tcp" -> connection.put("unitId",integer(1,247,1));
            case "opc-ua" -> {connection.put("path",Map.of("type","string","maxLength",512));connection.put("securityMode",Map.of("type","string","enum",List.of("None"),"default","None"));}
            case "mqtt" -> connection.put("staleMs",integer(100,60000,5000));
            case "virtual" -> connection.put("path",Map.of("type","string","maxLength",512,"default","/properties"));
            case "serial-json", "sensehat" -> {
                connection.put("deviceId",Map.of("type","string","maxLength",63));required.addAll(List.of("devicePath","deviceId"));
                connection.put("devicePath",protocol.equals("serial-json")?Map.of("type","string","pattern","^/dev/edgeai/[a-zA-Z0-9][a-zA-Z0-9_-]{0,62}$"):Map.of("type","string","const","/dev/i2c-1"));
                if(protocol.equals("serial-json"))connection.put("baudRate",Map.of("type","integer","enum",List.of(9600,19200,38400,57600,115200),"default",115200));
            }
        }
        return Map.of("id",protocol,"kind",protocol.equals("virtual")?"virtual":"physical","available",configured,
            "capabilityVerification","PER_NODE_REQUIRED","capabilityPath","/capabilities",
            "configurationSchema",Map.of("type","object","additionalProperties",false,"required",required,"properties",connection));
    }
    private static Map<String,Object> integer(int minimum,int maximum,int value){return Map.of("type","integer","minimum",minimum,"maximum",maximum,"default",value);}
}
