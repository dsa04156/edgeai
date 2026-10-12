package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.profile.ProfileIdentity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VDSensorBatchIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProfileService profiles;
    @Autowired Clock clock;
    @MockitoBean DeviceResourceGateway gateway;
    private final JsonDocuments json=new JsonDocuments();
    private final UUID sensorUid=UUID.randomUUID();
    private long observedAt;
    private String value="26.5";
    private static final String MODEL="edgeai-model-sensehat";
    @BeforeEach void sensorBoundary() {
        observedAt=clock.millis();
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{
            String path=call.getArgument(1);
            if(path.endsWith("/devices/sensehat-001"))return reply(Map.of("metadata",Map.of("name","sensehat-001","uid",sensorUid.toString(),"resourceVersion","1",
                "labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",json.canonical(Map.of("kind","physical","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")))))),
                "spec",Map.of("nodeName","edge-node","deviceModelRef",Map.of("name",MODEL),"protocol",Map.of("protocolName","edgeai-sensehat"),"properties",List.of(Map.of("name","temperature")))));
            if(path.endsWith("/devicestatuses/sensehat-001"))return reply(Map.of("status",Map.of("state","ONLINE","twins",List.of(Map.of("propertyName","temperature","reported",Map.of("value",value,"metadata",Map.of("timestamp",Long.toString(observedAt))))))));
            if(path.contains("/deployments/"))return reply(Map.of("status",Map.of("readyReplicas",1)));
            return new DeviceResourceGateway.Reply(404,"{}");
        });
    }
    private DeviceResourceGateway.Reply reply(Object value){return new DeviceResourceGateway.Reply(200,json.canonical(value));}
    private UUID profile(ProfileIdentity.Kind kind,Object spec) {
        return profiles.publish(kind,json.canonical(Map.of("key","sensor-batch-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private UUID template() throws Exception {
        var sp=profile(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        return profile(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v2","type","sensorMirror","serviceProfileVersionId",sp.toString(),
            "sources",Map.of("input",Map.of("deviceModel",MODEL,"required",true)),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60)));
    }
    @Test void selectedMeasurementsAndFreshnessPolicyAreStoredOnTheVdSource() throws Exception {
        var body=Map.of("key","sensor-vd-"+UUID.randomUUID(),"displayName","Temperature VD","profileVersionId",template().toString(),
            "sources",List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temperature"),"maxAgeSeconds",30)),"placement",Map.of("mode","AUTO"));
        var response=mvc.perform(post("/api/v1/virtual-devices").with(csrf()).contentType("application/json").content(json.canonical(body)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id=((Map<?,?>)json.decode(response)).get("id");
        mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.activeSources[0].properties[0]").value("temperature"))
            .andExpect(jsonPath("$.activeSources[0].maxAgeSeconds").value(30));
    }
    @Test void changingAgeCreatesANewBindingAndUnknownMeasurementsAreRejected()throws Exception {
        var source=Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temperature"),"maxAgeSeconds",30);
        var body=new LinkedHashMap<String,Object>(Map.of("key","sensor-history-"+UUID.randomUUID(),"displayName","Selection history","profileVersionId",template().toString(),"sources",List.of(source),"placement",Map.of("mode","AUTO")));
        var response=mvc.perform(post("/api/v1/virtual-devices").with(csrf()).contentType("application/json").content(json.canonical(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id=((Map<?,?>)json.decode(response)).get("id");body.remove("key");body.put("revision",0);
        body.put("sources",List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("temperature"),"maxAgeSeconds",45)));
        mvc.perform(patch("/api/v1/virtual-devices/"+id).with(csrf()).contentType("application/json").content(json.canonical(body))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(jsonPath("$.activeSources[0].maxAgeSeconds").value(45))
            .andExpect(jsonPath("$.sourceHistory.length()").value(2));
        body.put("revision",1);body.put("sources",List.of(Map.of("sourceKey","input","deviceId","sensehat-001","properties",List.of("unknown"),"maxAgeSeconds",45)));
        mvc.perform(patch("/api/v1/virtual-devices/"+id).with(csrf()).contentType("application/json").content(json.canonical(body))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VD_SOURCE_INCOMPATIBLE"));
        mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(jsonPath("$.vd.revision").value(1)).andExpect(jsonPath("$.sourceHistory.length()").value(2));
    }

}
