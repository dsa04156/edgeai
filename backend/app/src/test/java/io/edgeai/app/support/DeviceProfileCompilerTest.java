package io.edgeai.app.support;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.edgeai.app.support.DeviceProfileCompiler.*;

class DeviceProfileCompilerTest {
    static Map<String,Object> local(String protocol){return new TreeMap<>(Map.of("name","physical-01","protocol",protocol,"targetNode","edge-a","protocolConfig",Map.of("devicePath",protocol.equals("sensehat")?"/dev/i2c-1":"/dev/edgeai/arduino-001","deviceId",protocol.equals("sensehat")?"sensehat-001":"arduino-001"),"properties",List.of(protocol.equals("sensehat")?Map.of("name","humidity","dataType","float64","jsonPath","humidity"):Map.of("name","acceleration_x_raw","dataType","int32","sensor","acceleration","jsonPath","x"))));}
    @Test void physicalProfilesCompileWithoutNetworkFieldsAndKeepModelReusable(){
        for(String protocol:List.of("serial-json","sensehat")){
            var in=local(protocol);var c=compile(in,"edgeai-devices","tcp://127.0.0.1:1883");
            var config=map(map(map(c.deviceResource().get("spec")).get("protocol")).get("configData"));
            assertFalse(config.containsKey("host"));assertFalse(c.profile().containsKey("port"));assertEquals("physical",c.profile().get("kind"));
            var other=local(protocol);other.put("name","physical-02");other.put("targetNode","edge-b");assertEquals(c.model(),compile(other,"edgeai-devices","tcp://127.0.0.1:1883").model());
        }
    }
    @Test void localHardwareRejectsUnsafePathsAndUnknownSenseHatBindings(){
        var in=local("serial-json");in.put("protocolConfig",Map.of("devicePath","/dev/edgeai/../ttyACM0","deviceId","arduino-001"));assertThrows(IllegalArgumentException.class,()->compile(in,"edgeai-devices","tcp://127.0.0.1:1883"));
        var sense=local("sensehat");sense.put("properties",List.of(Map.of("name","fake","jsonPath","fake","dataType","float64")));assertThrows(IllegalArgumentException.class,()->compile(sense,"edgeai-devices","tcp://127.0.0.1:1883"));
        var network=local("serial-json");network.put("host","localhost");assertThrows(IllegalArgumentException.class,()->compile(network,"edgeai-devices","tcp://127.0.0.1:1883"));
    }
    @Test void hardwareMappersAreSharedPerNodeWithExplicitDeviceMounts(){
        String image="registry/mapper@sha256:"+"a".repeat(64);
        for(String protocol:List.of("serial-json","sensehat")){
            var pod=map(map(map(MapperDeployment.deployment("edge-a","edgeai-devices",image,protocol).get("spec")).get("template")).get("spec"));
            var container=map(((List<?>)pod.get("containers")).getFirst());assertEquals(true,map(container.get("securityContext")).get("privileged"));assertEquals(false,pod.get("automountServiceAccountToken"));
            String json=JSON.canonical(pod);assertTrue(json.contains(protocol.equals("serial-json")?"/run/edgeai/devices":"/dev/i2c-1"));assertFalse(json.contains("/host"));
        }
        var network=MapperDeployment.deployment("edge-a","edgeai-devices",image,"mqtt");assertFalse(JSON.canonical(network).contains("privileged"));
    }
    static Map<String,Object> input(){return new TreeMap<>(Map.of("name","motor-vibration-01","protocol","modbus-tcp","host","10.77.0.5","port",1502,"targetNode","etri-dev0002-raspi5","properties",List.of(Map.of("name","temperature","register",40001,"dataType","float32","unit","C"),Map.of("name","vibration_x","register",40003,"dataType","float32"),Map.of("name","vibration_y","register",40005,"dataType","float32"))));}
    @Test void generatesRealCrdAndReusesInterfaceAcrossConnections() throws Exception {
        var first=compile(input(),"edgeai-devices","tcp://127.0.0.1:1883");
        var second=input();second.put("name","motor-vibration-02");second.put("host","192.168.0.31");second.put("protocolConfig",Map.of("unitId",2));
        assertEquals(first.model(),compile(second,"edgeai-devices","tcp://127.0.0.1:1883").model());
        var spec=map(first.deviceResource().get("spec"));assertEquals("etri-dev0002-raspi5",spec.get("nodeName"));
        var props=(List<?>)spec.get("properties");assertEquals(4,props.size());
        var visitor=map(map(map(props.getFirst()).get("visitors")).get("configData"));assertEquals(0,visitor.get("address"));assertEquals(1,visitor.get("scale"));
        Path artifact=Path.of("build/device-slice-compiled.json");Files.createDirectories(artifact.getParent());Files.writeString(artifact,JSON.canonical(first.deviceResource()));
    }
    @Test void rejectsDuplicatePropertiesInvalidWidthsAndUnknownProtocol(){
        var duplicate=input();duplicate.put("properties",List.of(Map.of("name","x","register",40001,"dataType","float32"),Map.of("name","x","register",40003,"dataType","float32")));assertThrows(IllegalArgumentException.class,()->compile(duplicate,"edgeai-devices","tcp://127.0.0.1:1883"));
        var invalid=input();invalid.put("protocol","opc-ua");assertThrows(IllegalArgumentException.class,()->compile(invalid,"edgeai-devices","tcp://127.0.0.1:1883"));
        assertThrows(IllegalArgumentException.class,()->properties(List.of(Map.of("name","x","register",105536,"dataType","float64"))));
    }
    @Test void protocolBindingsCompileToSharedPropertyInterfaceAndSeparateMappers(){
        for(String protocol:List.of("opc-ua","mqtt","virtual")){
            var in=input();in.put("protocol",protocol);in.put("kind",protocol.equals("virtual")?"virtual":"physical");
            var property=new TreeMap<String,Object>(Map.of("name","temperature","dataType","float32"));
            property.put(switch(protocol){case "opc-ua"->"nodeId";case "mqtt"->"topic";default->"jsonPath";},switch(protocol){case "opc-ua"->"ns=1;s=temperature";case "mqtt"->"test/input";default->"temperature";});
            in.put("properties",List.of(property));var c=compile(in,"edgeai-devices","tcp://127.0.0.1:1883");
            assertEquals(mapperProtocol(protocol),map(map(c.deviceResource().get("spec")).get("protocol")).get("protocolName"));
            var second=new TreeMap<>(in);second.put("host","another-host");assertEquals(c.model(),compile(second,"edgeai-devices","tcp://127.0.0.1:1883").model());
            assertNotEquals(MapperDeployment.nameFor(c.node()),MapperDeployment.nameFor(c.node(),protocol));
        }
        assertThrows(IllegalArgumentException.class,()->properties("mqtt",List.of(Map.of("name","x","topic","edgeai/devices/loop/x","dataType","float32"))));
        var secured=input();secured.put("protocol","opc-ua");secured.put("protocolConfig",Map.of("securityMode","Sign"));assertThrows(IllegalArgumentException.class,()->compile(secured,"edgeai-devices","tcp://127.0.0.1:1883"));
    }
}
