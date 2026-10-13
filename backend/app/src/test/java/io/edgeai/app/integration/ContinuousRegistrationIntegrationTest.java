package io.edgeai.app.integration;

import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.vd.ProcessingEngineGateway;
import io.edgeai.app.support.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="edgeai.continuous.enabled=true")
@AutoConfigureMockMvc
class ContinuousRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean DeviceResourceGateway gateway;
    @MockitoBean ProcessingEngineGateway engine;
    static final String credential="test-processing-credential-0123456789";
    static final Path credentialFile=credentialFile();
    static Path credentialFile(){try{var p=Files.createTempFile("edgeai-processing-test-",".key");Files.writeString(p,credential);p.toFile().deleteOnExit();return p;}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static String historyUrl(){return System.getenv().getOrDefault("EDGEAI_HISTORY_TEST_URL","jdbc:postgresql://127.0.0.1:"+System.getenv().getOrDefault("EDGEAI_DB_PORT","15432")+"/edgeai_history_test");}
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry){
        registry.add("edgeai.continuous.credential-file",credentialFile::toString);
        registry.add("edgeai.continuous.history.url",ContinuousRegistrationIntegrationTest::historyUrl);
        registry.add("edgeai.continuous.history.user",()->System.getenv().getOrDefault("EDGEAI_DB_USER","edgeai"));
        registry.add("edgeai.continuous.history.password",()->System.getenv("EDGEAI_DB_PASSWORD"));
    }
    final JsonDocuments json=new JsonDocuments();
    static final UUID uid=UUID.randomUUID();
    final UUID podUid=UUID.randomUUID();
    @Test void retiredProfilesAndBatchRegistrationRemainUnavailable() throws Exception {
        mvc.perform(post("/api/v1/profiles/VD").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/virtual-devices").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }
    static final UUID sourceGeneration=UUID.randomUUID();
    @BeforeEach void source() {
        when(engine.authenticate(eq("test-pod-token"))).thenReturn(new ProcessingEngineGateway.Identity(podUid,"my-ekuiper-0","worker"));
        var profile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        var device=Map.of("metadata",Map.of("name","continuous-sensor","namespace","edgeai-devices","uid",uid.toString(),"labels",Map.of("app.kubernetes.io/part-of","edgeai"),
            "annotations",Map.of("edgeai.io/device-profile",json.canonical(profile),ObservationIdentity.ANNOTATION,sourceGeneration.toString())),
            "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature",
            "visitors",Map.of("configData",Map.of("dataType","float64","unit","°C","sourceGeneration",sourceGeneration.toString(),"configurationSha256",DeviceProfileCompiler.configuration(profile))),
            "pushMethod",Map.of("mqtt",Map.of("address","tcp://127.0.0.1:1883","topic","edgeai/devices/continuous-sensor/temperature","qos",1))))));
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{
            String path=call.getArgument(1);
            if(path.endsWith("/devices/continuous-sensor"))return new DeviceResourceGateway.Reply(200,json.canonical(device));
            if(path.contains("/devices?"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("items",List.of(device))));
            if(path.endsWith("/nodes/edge-a"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("metadata",Map.of("uid",UUID.randomUUID().toString()),"status",Map.of("addresses",List.of(Map.of("type","InternalIP","address","192.0.2.4"))))));
            return new DeviceResourceGateway.Reply(404,"{}");
        });
    }
    @Test void onlyAnAttestedEngineCanClaimTheLeaseAndResolveCrdOutputRoutes() throws Exception {
        String session=UUID.randomUUID().toString(),poll=json.canonical(Map.of("sessionId",session,"sequence",1,"reports",List.of()));
        mvc.perform(post("/internal/v1/processing/poll").contentType("application/json").content(poll)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/processing/poll").header("Authorization","Bearer "+credential).header("X-EdgeAI-Pod-Token","test-pod-token").contentType("application/json").content(poll))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enginePodUid").value(podUid.toString()))
            .andExpect(jsonPath("$.inputs[0].broker").value("tcp://192.0.2.4:1883"))
            .andExpect(jsonPath("$.inputs[0].deviceUid").value(uid.toString()))
            .andExpect(jsonPath("$.inputs[0].topic").value("edgeai/observations/v1/edgeai-devices/continuous-sensor/temperature"));
        mvc.perform(post("/internal/v1/processing/poll").header("Authorization","Bearer "+credential).header("X-EdgeAI-Pod-Token","test-pod-token").contentType("application/json")
            .content(json.canonical(Map.of("sessionId",session,"sequence",3,"reports",List.of())))).andExpect(status().isConflict());
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder enginePost(String endpoint,Object body) {
        return post("/internal/v1/processing/"+endpoint).header("Authorization","Bearer "+credential).header("X-EdgeAI-Pod-Token","test-pod-token").contentType("application/json").content(json.canonical(body));
    }
    private Map<String,Object> result(String vd,String generation,long slot,double value) {
        return Map.of("vdId",vd,"outputId","temperature","generation",generation,"sequence",slot,"evaluationId",generation+":"+slot,"evaluationAt",Instant.ofEpochSecond(slot).toString(),"value",value,
            "sources",List.of(Map.of("alias","a","deviceUid",uid.toString(),"sourceGeneration",sourceGeneration.toString(),"configurationSha256",DeviceProfileCompiler.configuration(Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")))),"observedAt",slot*1000)));
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void drainingCapacityAndNeverStartedStopsCannotExceedTheAgentsBoundedPlan() throws Exception {
        String session=UUID.randomUUID().toString();long sequence=1;
        var first=mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence++,"reports",List.of()))).andExpect(status().isOk()).andReturn();
        var existing=(List<?>)((Map<?,?>)json.decode(first.getResponse().getContentAsString())).get("executions");
        for(var raw:existing) {
            String id=(String)((Map<?,?>)raw).get("vdId");
            var detail=mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(status().isOk()).andReturn();
            var revision=((Map<?,?>)((Map<?,?>)json.decode(detail.getResponse().getContentAsString())).get("vd")).get("revision");
            mvc.perform(post("/api/v1/virtual-devices/"+id+"/drain").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.canonical(Map.of("revision",revision)))).andExpect(status().isAccepted());
        }
        var cleanup=mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence++,"reports",List.of()))).andExpect(status().isOk()).andReturn();
        var stops=(List<?>)((Map<?,?>)json.decode(cleanup.getResponse().getContentAsString())).get("executions");
        var reports=stops.stream().map(raw->{var e=(Map<?,?>)raw;return Map.of("vdId",e.get("vdId"),"generation",e.get("generation"),"state","STOPPED","reason","STOPPED","ruleIds",List.of());}).toList();
        mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence++,"reports",reports))).andExpect(status().isOk());
        var ids=new ArrayList<String>();
        for(int i=0;i<=200;i++) {
            String body=json.canonical(Map.of("key","capacity-"+UUID.randomUUID(),"displayName","capacity","sources",List.of(Map.of("sourceKey","a","deviceId","continuous-sensor","properties",List.of("temperature"))),"continuous",Map.of("intervalSeconds",1,"retentionDays",7,"outputs",List.of(Map.of("name","value","expression","a","operation","scalar","unit","°C")))));
            var created=mvc.perform(post("/api/v1/virtual-devices/continuous").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
            String id=(String)((Map<?,?>)json.decode(created.getResponse().getContentAsString())).get("id");ids.add(id);
            mvc.perform(post("/api/v1/virtual-devices/"+id+"/drain").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
            if(i<200)mvc.perform(post("/api/v1/virtual-devices/"+id+"/provision").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted());
            if(i%25==0)mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence++,"reports",List.of()))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/virtual-devices/"+ids.getFirst()+"/drain").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/virtual-devices/"+ids.getLast()+"/provision").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTINUOUS_CAPACITY_EXCEEDED"));
        var plan=mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence++,"reports",List.of()))).andExpect(status().isOk()).andExpect(jsonPath("$.executions.length()").value(200)).andReturn();
        var entries=(List<?>)((Map<?,?>)json.decode(plan.getResponse().getContentAsString())).get("executions");
        var stop=(Map<?,?>)entries.stream().filter(raw->ids.getFirst().equals(((Map<?,?>)raw).get("vdId"))).findFirst().orElseThrow();
        mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",sequence,"reports",List.of(Map.of("vdId",stop.get("vdId"),"generation",stop.get("generation"),"state","STOPPED","reason","STOPPED","ruleIds",List.of()))))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/virtual-devices/"+ids.getLast()+"/provision").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted());
    }
    @Test void everyResultIsStoredAndHistoryFailureDoesNotStopLiveResultsOrReplayTheGap() throws Exception {
        String body=json.canonical(Map.of("key","continuous-history-"+UUID.randomUUID(),"displayName","온도",
            "sources",List.of(Map.of("sourceKey","a","deviceId","continuous-sensor","properties",List.of("temperature"),"maxAgeSeconds",30)),
            "continuous",Map.of("intervalSeconds",1,"retentionDays",7,"outputs",List.of(Map.of("name","temperature","expression","a","operation","scalar","unit","°C")))));
        var created=mvc.perform(post("/api/v1/virtual-devices/continuous").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        String vd=(String)((Map<?,?>)json.decode(created.getResponse().getContentAsString())).get("id"),session=UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/virtual-devices/"+vd+"/provision").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted());
        var poll=mvc.perform(enginePost("poll",Map.of("sessionId",session,"sequence",1,"reports",List.of()))).andExpect(status().isOk()).andReturn();
        var plan=(Map<?,?>)json.decode(poll.getResponse().getContentAsString());
        String generation=(String)((List<?>)plan.get("executions")).stream().map(v->(Map<?,?>)v).filter(v->vd.equals(v.get("vdId"))).findFirst().orElseThrow().get("generation");
        long slot=Instant.now().getEpochSecond();var batch=Map.of("sessionId",session,"results",List.of(result(vd,generation,slot-4,21),result(vd,generation,slot-3,22)));
        mvc.perform(enginePost("results",batch)).andExpect(status().isOk()).andExpect(jsonPath("$.newResults").value(2)).andExpect(jsonPath("$.historyStored").value(true));
        mvc.perform(enginePost("results",batch)).andExpect(status().isOk()).andExpect(jsonPath("$.newResults").value(0));
        String from=Instant.ofEpochSecond(slot-10).toString(),to=Instant.ofEpochSecond(slot+10).toString();
        mvc.perform(get("/api/v1/virtual-devices/"+vd+"/outputs/temperature/history").param("from",from).param("to",to)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        try(var db=DriverManager.getConnection(historyUrl(),System.getenv().getOrDefault("EDGEAI_DB_USER","edgeai"),System.getenv("EDGEAI_DB_PASSWORD"));var statement=db.createStatement()) {
            statement.execute("ALTER TABLE edgeai_history.result RENAME TO result_fault_test");
            try {
                mvc.perform(enginePost("results",Map.of("sessionId",session,"results",List.of(result(vd,generation,slot-2,23))))).andExpect(status().isOk()).andExpect(jsonPath("$.historyStored").value(false));
                mvc.perform(get("/api/v1/virtual-devices/"+vd+"/outputs")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].latest.value").value(23)).andExpect(jsonPath("$.items[0].latest.historyStored").value(false));
            }finally{statement.execute("ALTER TABLE edgeai_history.result_fault_test RENAME TO result");}
        }
        mvc.perform(enginePost("results",Map.of("sessionId",session,"results",List.of(result(vd,generation,slot-2,23))))).andExpect(status().isOk()).andExpect(jsonPath("$.newResults").value(0));
        mvc.perform(enginePost("results",Map.of("sessionId",session,"results",List.of(result(vd,generation,slot-1,24))))).andExpect(status().isOk()).andExpect(jsonPath("$.historyStored").value(true));
        mvc.perform(get("/api/v1/virtual-devices/"+vd+"/outputs/temperature/history").param("from",from).param("to",to))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.gaps[?(@.reason=='HISTORY_UNAVAILABLE')]").value(org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(get("/api/v1/virtual-devices/"+vd+"/outputs/temperature/history").param("from",from).param("to",to).param("bucketSeconds","60"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.aggregation.bucketSeconds").value(60));
        var reset=mvc.perform(enginePost("reset",Map.of("sessionId",session,"vdId",vd,"generation",generation,"reason","INPUT_STALE"))).andExpect(status().isOk()).andReturn();
        String replacement=(String)((Map<?,?>)json.decode(reset.getResponse().getContentAsString())).get("generation");
        mvc.perform(enginePost("results",batch)).andExpect(status().isConflict());
        mvc.perform(enginePost("results",Map.of("sessionId",session,"results",List.of(result(vd,replacement,slot,25))))).andExpect(status().isOk()).andExpect(jsonPath("$.historyStored").value(true));
        mvc.perform(get("/api/v1/virtual-devices/"+vd+"/outputs/temperature/history").param("from",from).param("to",to).param("bucketSeconds","60"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.generation=='"+generation+"')]").value(org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThan(0))))
            .andExpect(jsonPath("$.items[?(@.generation=='"+replacement+"')]").value(org.hamcrest.Matchers.hasSize(1)));
        mvc.perform(post("/api/v1/virtual-devices/"+vd+"/drain").with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"revision\":0}")).andExpect(status().isAccepted());
    }
    @Test void userCanRegisterAndReplayAContinuousDeviceWithoutCreatingAService() throws Exception {
        String body=json.canonical(Map.of("key","continuous-"+UUID.randomUUID(),"displayName","온도 평균",
            "sources",List.of(Map.of("sourceKey","a","deviceId","continuous-sensor","properties",List.of("temperature"),"maxAgeSeconds",30)),
            "continuous",Map.of("intervalSeconds",1,"retentionDays",7,"outputs",List.of(Map.of("name","average","expression","a","operation","movingAverage","sampleCount",3,"unit","°C")))));
        var response=mvc.perform(post("/api/v1/virtual-devices/continuous").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.serviceProfileVersionId").doesNotExist()).andReturn().getResponse();
        var id=(String)((Map<?,?>)json.decode(response.getContentAsString())).get("id");
        mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.activeSources[0].deviceUid").value(uid.toString()));
        mvc.perform(post("/api/v1/virtual-devices/continuous").with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        String operationKey=UUID.randomUUID().toString();
        var operation=mvc.perform(post("/api/v1/virtual-devices/"+id+"/provision").with(csrf()).header("Idempotency-Key",operationKey)
            .contentType("application/json").content("{\"revision\":0}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.targetRuntimeId").doesNotExist()).andReturn().getResponse();
        String operationId=(String)((Map<?,?>)json.decode(operation.getContentAsString())).get("id");
        mvc.perform(get("/api/v1/operations/"+operationId)).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("VD_CONTINUOUS_START"));
        mvc.perform(get("/api/v1/virtual-devices/"+id+"/execution")).andExpect(status().isOk())
            .andExpect(jsonPath("$.continuous.state").value("STARTING")).andExpect(jsonPath("$.current").doesNotExist());
        mvc.perform(post("/api/v1/virtual-devices/"+id+"/provision").with(csrf()).header("Idempotency-Key",operationKey)
            .contentType("application/json").content("{\"revision\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(operationId));
    }
}
