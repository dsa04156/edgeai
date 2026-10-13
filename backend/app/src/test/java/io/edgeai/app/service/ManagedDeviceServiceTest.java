package io.edgeai.app.service;

import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedDeviceServiceTest {
    @Test void observationDiscoveryDoesNotChangeAnUnqualifiedLegacySensor() {
        var profile=Map.of("protocol","virtual","host","sensor.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        var sensor=Map.of("metadata",Map.of("name","sensor","namespace","edgeai-devices","uid",UUID.randomUUID().toString(),"labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",DeviceProfileCompiler.JSON.canonical(profile))),
            "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature","visitors",Map.of("configData",Map.of("dataType","float64"))))));
        read("/apis/devices.kubeedge.io/v1beta1/namespaces/edgeai-devices/devices?labelSelector=app.kubernetes.io%2Fpart-of%3Dedgeai",Map.of("items",List.of(sensor)));
        read("/api/v1/nodes/edge-a",Map.of("metadata",Map.of("uid",UUID.randomUUID().toString())));
        var catalog=service().observationCatalog(Map.of());
        assertEquals("temperature",catalog.getFirst().get("property"));assertEquals("OBSERVATION_ROUTE_UNAVAILABLE",catalog.getFirst().get("reason"));
        verify(gateway,never()).request(eq("PUT"),anyString(),any());
    }
    @Test void registrationRejectsMultipleMeasurementsBeforeChangingClusterResources(){
        String body=DeviceProfileCompiler.JSON.canonical(Map.of("name","board","targetNode","edge-a","protocol","modbus-tcp","host","192.0.2.31","properties",List.of(Map.of("name","temperature","register",40001,"dataType","float32"),Map.of("name","humidity","register",40003,"dataType","float32"))));
        assertThrows(IllegalArgumentException.class,()->service().create(body));
        verifyNoInteractions(gateway);
    }
    final Instant now=Instant.parse("2026-10-07T04:30:00Z");
    final DeviceResourceGateway gateway=mock(DeviceResourceGateway.class);
    @SuppressWarnings("unchecked") final ObjectProvider<DeviceResourceGateway> provider=mock(ObjectProvider.class);
    ManagedDeviceService service(){when(provider.getIfAvailable()).thenReturn(gateway);return new ManagedDeviceService(provider,Clock.fixed(now,ZoneOffset.UTC),"edgeai-devices","tcp://127.0.0.1:1883","registry/mapper@sha256:"+"a".repeat(64),"","",mock(io.edgeai.domain.repository.VirtualDeviceRepository.class),mock(io.edgeai.domain.repository.DeviceRepository.class));}
    void read(String path,Object data){when(gateway.request("GET",path,null)).thenReturn(new DeviceResourceGateway.Reply(200,DeviceProfileCompiler.JSON.canonical(data)));}
    String path(String kind,String id){return "/apis/devices.kubeedge.io/v1beta1/namespaces/edgeai-devices/"+kind+"/"+id;}
    void device(boolean ready,String state,Instant heartbeat){
        read(path("devices","sensor"),Map.of("metadata",Map.of("name","sensor","resourceVersion","1","labels",Map.of("app.kubernetes.io/part-of","edgeai")),"spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","model"),"properties",List.of(Map.of("name","temperature")))));
        read(path("devicestatuses","sensor"),Map.of("status",Map.of("state",state,"lastOnlineTime",heartbeat.toString(),"twins",List.of(Map.of("propertyName","temperature","reported",Map.of("value","0","metadata",Map.of("timestamp",Long.toString(now.minusSeconds(120).toEpochMilli()))))))));
        read("/apis/apps/v1/namespaces/edgeai-devices/deployments/"+MapperDeployment.nameFor("edge-a"),Map.of("status",Map.of("readyReplicas",ready?1:0)));
    }
    @Test void freshDmiHeartbeatDoesNotTurnAnUnchangedValueIntoOffline(){device(true,"online",now);var view=service().get("sensor");assertEquals("ONLINE",view.get("state"));var resolved=service().workflowDevice(Map.of("id","sensor","outputs",List.of("temperature")));assertEquals(Map.of("temperature","0"),resolved.get("values"));assertFalse(resolved.containsKey("protocol"));}
    @Test void mapperOutageAndStaleDmiReportsFailClosedBeforeWorkflowCreation(){
        device(false,"online",now);assertEquals("MAPPER_NOT_READY",service().get("sensor").get("stateReason"));assertThrows(ControlPlaneException.class,()->service().workflowDevice(Map.of("id","sensor","outputs",List.of("temperature"))));
        device(true,"online",now.minusSeconds(31));assertEquals("STALE_DMI_REPORT",service().get("sensor").get("stateReason"));
        device(true,"offline",now);assertEquals("OFFLINE",service().get("sensor").get("state"));
    }
    @Test void atomicMeasurementValuePreservesAppliedConfigurationAndCollectionTimeWhenDmiDropsMetadata(){
        var resource=registered(List.of(property("temperature",40001)));
        var profile=DeviceProfileCompiler.JSON.decode((String)DeviceProfileCompiler.map(DeviceProfileCompiler.map(resource.get("metadata")).get("annotations")).get("edgeai.io/device-profile"));
        String applied=DeviceProfileCompiler.configuration(profile);long collected=now.minusSeconds(2).toEpochMilli();
        String envelope=DeviceProfileCompiler.JSON.canonical(Map.of("apiVersion","edgeai.measurement/v1","value","0","configurationSha256",applied,"collectedAt",collected));
        read(path("devicestatuses","sensor"),Map.of("status",Map.of("state","online","lastOnlineTime",now.toString(),"twins",List.of(Map.of("propertyName","temperature","reported",Map.of("value","edgeai.measurement/v1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"metadata",Map.of("timestamp",Long.toString(now.toEpochMilli()),"type","string")))))));
        read("/apis/apps/v1/namespaces/edgeai-devices/deployments/"+MapperDeployment.nameFor("edge-a"),Map.of("status",Map.of("readyReplicas",1)));
        var device=service().get("sensor");var reading=DeviceProfileCompiler.map(DeviceProfileCompiler.map(device.get("properties")).get("temperature"));
        assertEquals("0",reading.get("value"));assertEquals(collected,reading.get("timestamp"));assertEquals(applied,reading.get("configurationSha256"));
    }
    @Test void workflowReferencesRejectProtocolFieldsAndMissingProperties(){device(true,"online",now);assertThrows(IllegalArgumentException.class,()->service().workflowDevice(Map.of("id","sensor","outputs",List.of("temperature"),"host","private")));assertThrows(ControlPlaneException.class,()->service().workflowDevice(Map.of("id","sensor","outputs",List.of("missing"))));}
    @Test void resourcesFromAnotherOwnerAreNeverAdopted(){device(true,"online",now);read(path("devices","sensor"),Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of","other"))));assertEquals(409,assertThrows(ControlPlaneException.class,()->service().delete("sensor")).status());verify(gateway,never()).request(eq("DELETE"),anyString(),any());}
    Map<String,Object> property(String name,int register){return Map.of("name",name,"register",register,"dataType","float32");}
    String propertySettings(List<?> properties){
        var settings=(List<?>)DeviceProfileCompiler.JSON.decode(DeviceProfileCompiler.JSON.canonical(properties));
        for(var property:settings) {
            var visitor=DeviceProfileCompiler.map(DeviceProfileCompiler.map(DeviceProfileCompiler.map(property).get("visitors")).get("configData"));
            visitor.remove("configurationSha256");visitor.remove("sourceGeneration");visitor.remove("unit");
        }
        return DeviceProfileCompiler.JSON.canonical(settings);
    }
    Map<String,Object> registered(List<Map<String,Object>> properties){
        var compiled=DeviceProfileCompiler.compile(Map.of("name","sensor","targetNode","edge-a","protocol","modbus-tcp","host","192.0.2.31","port",502,"protocolConfig",Map.of("unitId",2),"collectionInterval",1500,"properties",properties),"edgeai-devices","tcp://127.0.0.1:1883");
        var document=new TreeMap<>(compiled.deviceResource());var metadata=new TreeMap<>(DeviceProfileCompiler.map(document.get("metadata")));
        metadata.put("resourceVersion","41");metadata.put("uid","device-uid");
        var annotations=new TreeMap<>(DeviceProfileCompiler.map(metadata.get("annotations")));annotations.put("external-note","keep");metadata.put("annotations",annotations);document.put("metadata",metadata);
        read(path("devices","sensor"),document);return document;
    }
    void allowModelCreation(){
        when(gateway.request(eq("GET"),startsWith(path("devicemodels","edgeai-model-")),isNull())).thenReturn(new DeviceResourceGateway.Reply(404,"{}"));
        when(gateway.request(eq("POST"),eq("/apis/devices.kubeedge.io/v1beta1/namespaces/edgeai-devices/devicemodels"),any())).thenReturn(new DeviceResourceGateway.Reply(201,"{}"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"vibration","vibration_","vibration______________________________________________________"})
    void addingOneMeasurementCreatesSeparateDeviceAndKeepsOriginalAndSharedMapper(String measurement){
        var old=registered(List.of(property("temperature",40001)));allowModelCreation();
        var createdId=new String[1];
        when(gateway.request(eq("GET"),startsWith(path("devices","sensor-")),isNull())).thenReturn(new DeviceResourceGateway.Reply(404,"{}"));
        read("/api/v1/nodes/edge-a",Map.of("metadata",Map.of("labels",Map.of("node-role.kubernetes.io/edge","","kubernetes.io/arch","arm64"))));
        String mapper=MapperDeployment.nameFor("edge-a");
        var shared=Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of","edgeai")),"status",Map.of("readyReplicas",1));
        read("/api/v1/namespaces/edgeai-devices/configmaps/"+mapper,shared);
        read("/apis/apps/v1/namespaces/edgeai-devices/deployments/"+mapper,shared);
        when(gateway.request(eq("GET"),startsWith(path("devicestatuses","sensor-")),isNull())).thenReturn(new DeviceResourceGateway.Reply(404,"{}"));
        when(gateway.request(eq("POST"),eq("/apis/devices.kubeedge.io/v1beta1/namespaces/edgeai-devices/devices"),any())).thenAnswer(call->{
            var created=DeviceProfileCompiler.map(call.getArgument(2));String id=(String)DeviceProfileCompiler.map(created.get("metadata")).get("name");
            assertDoesNotThrow(()->DeviceProfileCompiler.name(id));assertTrue(id.startsWith("sensor-"));createdId[0]=id;var spec=DeviceProfileCompiler.map(created.get("spec"));
            assertEquals(DeviceProfileCompiler.map(old.get("spec")).get("protocol"),spec.get("protocol"));
            assertEquals(List.of(measurement),((List<?>)spec.get("properties")).stream().map(p->DeviceProfileCompiler.map(p).get("name")).toList());
            read(path("devices",id),created);return new DeviceResourceGateway.Reply(201,"{}");
        });
        var result=service().addProperty("sensor",DeviceProfileCompiler.JSON.canonical(property(measurement,40003)));
        assertEquals(createdId[0],result.get("id"));var profile=DeviceProfileCompiler.map(result.get("profile"));
        assertEquals("sensor",profile.get("sourceName"));assertEquals(1500,((Number)profile.get("collectionInterval")).intValue());
        assertEquals(1,((List<?>)profile.get("properties")).size());
        assertEquals(DeviceProfileCompiler.JSON.canonical(old),gateway.request("GET",path("devices","sensor"),null).document());
        verify(gateway,never()).request(eq("PUT"),anyString(),any());
        verify(gateway,never()).request(eq("POST"),contains("/deployments"),any());
        verify(gateway,never()).request(eq("DELETE"),anyString(),any());
    }
    @Test void duplicateInvalidAndForeignPropertyAdditionsNeverChangeResources(){
        registered(List.of(property("temperature",40001)));var service=service();
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service.addProperty("sensor",DeviceProfileCompiler.JSON.canonical(property("temperature",40003)))).status());
        assertThrows(IllegalArgumentException.class,()->service.addProperty("sensor","{\"name\":\"vibration\",\"dataType\":\"float32\",\"register\":40003,\"host\":\"other\"}"));
        assertThrows(IllegalArgumentException.class,()->service.addProperty("sensor",DeviceProfileCompiler.JSON.canonical(property("connection",40003))));
        read(path("devices","sensor"),Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of","other"))));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service.addProperty("sensor",DeviceProfileCompiler.JSON.canonical(property("vibration",40003)))).status());
        verify(gateway,never()).request(eq("PUT"),anyString(),any());verify(gateway,never()).request(eq("POST"),anyString(),any());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
    }
    @Test void existingSiblingMeasurementIsRejectedWithoutChangingEitherDevice(){
        registered(List.of(property("temperature",40001)));
        read(path("devices","sensor-vibration"),Map.of("metadata",Map.of("name","sensor-vibration")));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service().addProperty("sensor",DeviceProfileCompiler.JSON.canonical(property("vibration",40003)))).status());
        verify(gateway,never()).request(eq("POST"),anyString(),any());verify(gateway,never()).request(eq("PUT"),anyString(),any());
    }
    @Test void updatingOnePropertyMergesIntoLatestProfileAndPreservesOtherFieldsWithoutRestartingMapper(){
        var old=registered(List.of(property("temperature",40001),property("vibration",40003),property("new_since_ui_load",40005)));allowModelCreation();
        var oldProfile=DeviceProfileCompiler.map(DeviceProfileCompiler.JSON.decode((String)DeviceProfileCompiler.map(DeviceProfileCompiler.map(old.get("metadata")).get("annotations")).get("edgeai.io/device-profile")));
        read(path("devicestatuses","sensor"),Map.of("status",Map.of("state","online","lastOnlineTime",now.toString())));
        read("/apis/apps/v1/namespaces/edgeai-devices/deployments/"+MapperDeployment.nameFor("edge-a"),Map.of("status",Map.of("readyReplicas",1)));
        var changed=new TreeMap<String,Object>(property("temperature",40007));changed.put("dataType","float64");changed.put("unit","C");changed.put("scale",0.1);changed.put("offset",2);
        when(gateway.request(eq("PUT"),eq(path("devices","sensor")),any())).thenAnswer(call->{
            var replacement=DeviceProfileCompiler.map(call.getArgument(2));var metadata=DeviceProfileCompiler.map(replacement.get("metadata"));
            assertEquals(now.toString(),DeviceProfileCompiler.map(metadata.get("annotations")).get("edgeai.io/configuration-updated-at"));
            assertEquals("41",metadata.get("resourceVersion"));assertEquals("device-uid",metadata.get("uid"));assertEquals("keep",DeviceProfileCompiler.map(metadata.get("annotations")).get("external-note"));
            var spec=DeviceProfileCompiler.map(replacement.get("spec"));var oldSpec=DeviceProfileCompiler.map(old.get("spec"));
            assertEquals(oldSpec.get("protocol"),spec.get("protocol"));assertEquals(oldSpec.get("nodeName"),spec.get("nodeName"));
            assertEquals(propertySettings(((List<?>)oldSpec.get("properties")).stream().filter(p->!"temperature".equals(DeviceProfileCompiler.map(p).get("name"))).toList()),propertySettings(((List<?>)spec.get("properties")).stream().filter(p->!"temperature".equals(DeviceProfileCompiler.map(p).get("name"))).toList()));
            var profile=DeviceProfileCompiler.map(DeviceProfileCompiler.JSON.decode((String)DeviceProfileCompiler.map(metadata.get("annotations")).get("edgeai.io/device-profile")));
            var unchanged=new TreeMap<>(profile);unchanged.remove("properties");var expected=new TreeMap<>(oldProfile);expected.remove("properties");assertEquals(expected,unchanged);
            assertEquals(((List<?>)oldProfile.get("properties")).stream().filter(p->!"temperature".equals(DeviceProfileCompiler.map(p).get("name"))).toList(),((List<?>)profile.get("properties")).stream().filter(p->!"temperature".equals(DeviceProfileCompiler.map(p).get("name"))).toList());
            read(path("devices","sensor"),replacement);return new DeviceResourceGateway.Reply(200,"{}");
        });
        var result=service().updateProperty("sensor","temperature",DeviceProfileCompiler.JSON.canonical(changed));
        assertEquals(now.toString(),result.get("configurationUpdatedAt"));
        var fields=(List<?>)DeviceProfileCompiler.map(result.get("profile")).get("properties");assertEquals(3,fields.size());
        var selected=DeviceProfileCompiler.map(fields.stream().filter(p->"temperature".equals(DeviceProfileCompiler.map(p).get("name"))).findFirst().orElseThrow());assertEquals(40007,((Number)selected.get("register")).intValue());assertEquals("float64",selected.get("dataType"));assertEquals("C",selected.get("unit"));assertEquals(0.1,((Number)selected.get("scale")).doubleValue());assertEquals(2,((Number)selected.get("offset")).intValue());
        assertEquals("ONLINE",result.get("state"));verify(gateway,never()).request(eq("POST"),contains("/deployments"),any());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
    }
    @Test void unchangedPropertyUpdatePreservesTheObservationCutoff(){
        var old=registered(List.of(property("temperature",40001)));allowModelCreation();
        read(path("devicestatuses","sensor"),Map.of("status",Map.of("state","online","lastOnlineTime",now.toString())));
        read("/apis/apps/v1/namespaces/edgeai-devices/deployments/"+MapperDeployment.nameFor("edge-a"),Map.of("status",Map.of("readyReplicas",1)));
        var metadata=DeviceProfileCompiler.map(old.get("metadata"));var annotations=new TreeMap<>(DeviceProfileCompiler.map(metadata.get("annotations")));
        String cutoff=now.minusSeconds(60).toString();annotations.put("edgeai.io/configuration-updated-at",cutoff);metadata.put("annotations",annotations);read(path("devices","sensor"),old);
        var profile=DeviceProfileCompiler.map(DeviceProfileCompiler.JSON.decode((String)annotations.get("edgeai.io/device-profile")));
        var unchanged=((List<?>)profile.get("properties")).getFirst();
        when(gateway.request(eq("PUT"),eq(path("devices","sensor")),any())).thenAnswer(call->{
            var replacement=DeviceProfileCompiler.map(call.getArgument(2));
            assertEquals(cutoff,DeviceProfileCompiler.map(DeviceProfileCompiler.map(replacement.get("metadata")).get("annotations")).get("edgeai.io/configuration-updated-at"));
            read(path("devices","sensor"),replacement);return new DeviceResourceGateway.Reply(200,"{}");
        });
        assertEquals(cutoff,service().updateProperty("sensor","temperature",DeviceProfileCompiler.JSON.canonical(unchanged)).get("configurationUpdatedAt"));
    }
    @Test void missingReservedRenamedInvalidAndForeignPropertyUpdatesNeverMutateResources(){
        registered(List.of(property("temperature",40001)));var service=service();
        assertEquals(404,assertThrows(ControlPlaneException.class,()->service.updateProperty("sensor","missing",DeviceProfileCompiler.JSON.canonical(property("missing",40003)))).status());
        assertThrows(IllegalArgumentException.class,()->service.updateProperty("sensor","connection",DeviceProfileCompiler.JSON.canonical(property("connection",40003))));
        assertThrows(IllegalArgumentException.class,()->service.updateProperty("sensor","temperature",DeviceProfileCompiler.JSON.canonical(property("renamed",40003))));
        assertThrows(IllegalArgumentException.class,()->service.updateProperty("sensor","temperature","{\"name\":\"temperature\",\"dataType\":\"float32\",\"register\":40003,\"host\":\"other\"}"));
        read(path("devices","sensor"),Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of","other"))));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service.updateProperty("sensor","temperature",DeviceProfileCompiler.JSON.canonical(property("temperature",40003)))).status());
        verify(gateway,never()).request(eq("PUT"),anyString(),any());verify(gateway,never()).request(eq("POST"),anyString(),any());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
    }
    @Test void concurrentSinglePropertyUpdateIsRejectedWithoutRemovingOrRetryingFields(){
        var old=registered(List.of(property("temperature",40001),property("vibration",40003)));allowModelCreation();
        when(gateway.request(eq("PUT"),eq(path("devices","sensor")),any())).thenReturn(new DeviceResourceGateway.Reply(409,"{}"));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service().updateProperty("sensor","temperature",DeviceProfileCompiler.JSON.canonical(property("temperature",40007)))).status());
        verify(gateway,times(1)).request(eq("PUT"),eq(path("devices","sensor")),any());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
        assertEquals(DeviceProfileCompiler.JSON.canonical(old),gateway.request("GET",path("devices","sensor"),null).document());
    }
    @Test void individualDeletionUpdatesOnlyTheSelectedPropertyAndPreservesConnectionAndMetadata(){
        var old=registered(List.of(property("temperature",40001),property("vibration",40003)));allowModelCreation();
        when(gateway.request(eq("PUT"),eq(path("devices","sensor")),any())).thenAnswer(call->{
            var replacement=DeviceProfileCompiler.map(call.getArgument(2));var metadata=DeviceProfileCompiler.map(replacement.get("metadata"));
            assertEquals("41",metadata.get("resourceVersion"));assertEquals("device-uid",metadata.get("uid"));assertEquals("keep",DeviceProfileCompiler.map(metadata.get("annotations")).get("external-note"));
            var spec=DeviceProfileCompiler.map(replacement.get("spec"));var oldSpec=DeviceProfileCompiler.map(old.get("spec"));
            assertEquals(oldSpec.get("protocol"),spec.get("protocol"));assertEquals(oldSpec.get("nodeName"),spec.get("nodeName"));
            var names=((List<?>)spec.get("properties")).stream().map(p->DeviceProfileCompiler.map(p).get("name")).toList();assertEquals(List.of("vibration"),names);
            var profile=DeviceProfileCompiler.map(DeviceProfileCompiler.JSON.decode((String)DeviceProfileCompiler.map(metadata.get("annotations")).get("edgeai.io/device-profile")));
            assertEquals(1500,((Number)profile.get("collectionInterval")).intValue());assertEquals("vibration",DeviceProfileCompiler.map(((List<?>)profile.get("properties")).getFirst()).get("name"));
            return new DeviceResourceGateway.Reply(200,"{}");
        });
        service().deleteProperty("sensor","temperature");
        verify(gateway).request(eq("PUT"),eq(path("devices","sensor")),any());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
        verify(gateway,never()).request(anyString(),contains("/deployments"),any());
    }
    @Test void lastPropertyDeletionRemovesOnlyTheDeviceWithUidAndVersionPreconditions(){
        registered(List.of(property("temperature",40001)));
        when(gateway.request(eq("DELETE"),eq(path("devices","sensor")),any())).thenAnswer(call->{var options=DeviceProfileCompiler.map(call.getArgument(2));assertEquals(Map.of("uid","device-uid","resourceVersion","41"),options.get("preconditions"));return new DeviceResourceGateway.Reply(200,"{}");});
        service().deleteProperty("sensor","temperature");verify(gateway).request(eq("DELETE"),eq(path("devices","sensor")),any());
        verify(gateway,never()).request(eq("PUT"),anyString(),any());verify(gateway,never()).request(eq("POST"),anyString(),any());
    }
    @Test void missingReservedAndForeignPropertiesNeverMutateResources(){
        registered(List.of(property("temperature",40001)));var service=service();
        assertEquals(404,assertThrows(ControlPlaneException.class,()->service.deleteProperty("sensor","missing")).status());
        assertThrows(IllegalArgumentException.class,()->service.deleteProperty("sensor","connection"));
        read(path("devices","sensor"),Map.of("metadata",Map.of("labels",Map.of("app.kubernetes.io/part-of","other"))));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service.deleteProperty("sensor","temperature")).status());
        verify(gateway,never()).request(eq("DELETE"),anyString(),any());verify(gateway,never()).request(eq("PUT"),anyString(),any());verify(gateway,never()).request(eq("POST"),anyString(),any());
    }
    @Test void concurrentPropertyChangesAreReportedAsConflicts(){
        registered(List.of(property("temperature",40001),property("vibration",40003)));allowModelCreation();
        when(gateway.request(eq("PUT"),eq(path("devices","sensor")),any())).thenReturn(new DeviceResourceGateway.Reply(409,"{}"));
        assertEquals(409,assertThrows(ControlPlaneException.class,()->service().deleteProperty("sensor","temperature")).status());verify(gateway,never()).request(eq("DELETE"),anyString(),any());
    }
    @Test void removedPropertiesAreExcludedFromReadingsAndWorkflowEvenWhenTwinStillContainsThem(){
        device(true,"online",now);
        read(path("devicestatuses","sensor"),Map.of("status",Map.of("state","online","lastOnlineTime",now.toString(),"twins",List.of(
            Map.of("propertyName","temperature","reported",Map.of("value","0","metadata",Map.of("timestamp",Long.toString(now.toEpochMilli())))),
            Map.of("propertyName","removed","reported",Map.of("value","99","metadata",Map.of("timestamp",Long.toString(now.toEpochMilli()))))))));
        var service=service();assertEquals(Set.of("temperature"),DeviceProfileCompiler.map(service.get("sensor").get("properties")).keySet());
        assertEquals(400,assertThrows(ControlPlaneException.class,()->service.workflowDevice(Map.of("id","sensor","outputs",List.of("removed")))).status());
    }
}
