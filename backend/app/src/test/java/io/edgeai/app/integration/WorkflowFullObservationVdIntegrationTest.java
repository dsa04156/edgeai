package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.StreamConnectionSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.vd.ProcessingEngineGateway;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** CONTINUOUS_VD ALL_OBSERVATIONS through the public VD registration, engine result, run, drain and Runner input APIs. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.continuous.enabled=true",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true",
    "edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","edgeai.stream.lease-seconds=120"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class WorkflowFullObservationVdIntegrationTest {
    private static final String NAMESPACE="full-observation-vd-"+UUID.randomUUID();
    static final String credential="test-processing-credential-0123456789";
    private static final Path KEY=file(null),CREDENTIAL=file(credential);
    private static Path file(String content){try{
        var p=Files.createTempFile("edgeai-full-observation-vd-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        if(content==null){var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);content=HexFormat.of().formatHex(bytes);}
        Files.writeString(p,content);return p;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.runtime.namespace",()->NAMESPACE);p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);
        p.add("edgeai.continuous.credential-file",CREDENTIAL::toString);p.add("edgeai.continuous.history.url",ContinuousRegistrationIntegrationTest::historyUrl);
        p.add("edgeai.continuous.history.user",()->System.getenv().getOrDefault("EDGEAI_DB_USER","edgeai"));p.add("edgeai.continuous.history.password",()->System.getenv("EDGEAI_DB_PASSWORD"));
    }
    @AfterAll static void remove()throws Exception{Files.delete(KEY);Files.delete(CREDENTIAL);}
    @MockitoBean RuntimeGateway gateway;@MockitoBean S3ArtifactStore storage;@MockitoBean MosquittoStreamBroker broker;
    @MockitoBean DeviceResourceGateway devices;@MockitoBean ProcessingEngineGateway engine;
    @MockitoBean StreamAuthorityWorker authorityWorker;@MockitoBean StreamConnectionSettings connection;@MockitoBean StreamRunWorker scheduledWorker;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired ExecutionRepository executions;@Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;@Autowired RunnerTokenService runnerTokens;@Autowired MockMvc mvc;
    private final JsonDocuments json=new JsonDocuments();
    /** Cross-thread waits are bounded so a regression fails instead of hanging the suite. */
    private static final long BOUND_SECONDS=20;
    private final UUID uid=UUID.randomUUID(),sourceGeneration=UUID.randomUUID(),podUid=UUID.randomUUID();
    private final Map<String,Object> sensorProfile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
    private String session;private long engineSequence=1;private String vd;private String generation;

    private String encode(Object value){return json.canonical(value);}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(String value){return (Map<String,Object>)json.decode(value);}
    private Map<String,Object> call(MockHttpServletRequestBuilder r,int code)throws Exception{
        var response=mvc.perform(r.with(user("test")).with(csrf())).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(code);return decode(response.getContentAsString());
    }
    private MockHttpServletRequestBuilder engineCall(String endpoint,Object body){
        return post("/internal/v1/processing/"+endpoint).header("Authorization","Bearer "+credential).header("X-EdgeAI-Pod-Token","test-pod-token").contentType("application/json").content(encode(body));
    }
    private Map<String,Object> result(long slot,double value){
        return Map.of("vdId",vd,"outputId","temperature","generation",generation,"sequence",slot,"evaluationId",generation+":"+slot,"evaluationAt",Instant.ofEpochSecond(slot).toString(),"value",value,
            "sources",List.of(Map.of("alias","a","deviceUid",uid.toString(),"sourceGeneration",sourceGeneration.toString(),"configurationSha256",DeviceProfileCompiler.configuration(sensorProfile),"observedAt",slot*1000)));
    }
    /** One engine delivery; a retry of the same slots is a separate call with the same body. */
    private int results(Map<String,Object>... values)throws Exception{
        var reply=decode(mvc.perform(engineCall("results",Map.of("sessionId",session,"results",List.of(values)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return ((Number)reply.get("newResults")).intValue();
    }
    @SuppressWarnings("unchecked") private UUID profile(boolean source)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));var stream=(Map<String,Object>)spec.get("stream");
        if(source){stream.put("source","WORKFLOW_DATA");stream.put("inputs",Map.of());stream.put("command",List.of("edgeai-workflow-input"));stream.put("args",List.of());
            stream.put("outputs",Map.of("sample",Map.of("mediaType","application/json","maxPayloadBytes",4096)));}
        else{stream.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxPayloadBytes",4096)));stream.put("outputs",Map.of());}
        return profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","full-observation-vd-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private UUID run()throws Exception{
        var workflow=workflows.create(encode(Map.of("key","full-observation-vd-"+UUID.randomUUID(),"displayName","Full VD"))).value();
        var version=workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",List.of(
            Map.of("key","source","serviceProfileVersionId",profile(true).toString(),"parameters",Map.of("dataSource",Map.of("kind","CONTINUOUS_VD","vdId",vd,"outputId","temperature","delivery","ALL_OBSERVATIONS"))),
            Map.of("key","sink","serviceProfileVersionId",profile(false).toString(),"parameters",Map.of())),
            "dependencies",List.of(Map.of("fromTask","source","toTask","sink","fromPort","sample","toPort","input","mode","STREAM"))))).value();
        var created=call(post("/api/v1/workflow-runs").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(encode(Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))),201);
        UUID run=UUID.fromString((String)created.get("id"));
        for(String name:List.of("source","sink")){
            var task=executions.tasks(run).stream().filter(t->t.key().equals(name)).findFirst().orElseThrow();var a=executions.attempts(task.id()).getFirst();
            var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");lifecycle.submitted(a.id(),pod.jobUid());lifecycle.claim(a.id(),a.epoch(),pod);
        }
        return run;
    }
    private Map<String,Object> input(UUID run,int code,Object... extra)throws Exception{
        var task=executions.tasks(run).stream().filter(t->t.key().equals("source")).findFirst().orElseThrow();
        var r=runtimes.byAttempt(executions.attempts(task.id()).getFirst().id()).orElseThrow();
        var body=new TreeMap<String,Object>(Map.of("epoch",r.epoch(),"podUid",r.producerPodUid().toString()));
        for(int i=0;i<extra.length;i+=2)body.put((String)extra[i],extra[i+1]);
        return decode(mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/streams/input").header("Authorization","Bearer "+runnerTokens.issue(r))
            .header("X-EdgeAI-Pod-Token","source-pod-proof").contentType("application/json").content(encode(body))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString());
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> reply){return (List<Map<String,Object>>)reply.get("items");}
    @SuppressWarnings("unchecked") private static double value(Map<String,Object> item){return ((Number)((Map<String,Object>)item.get("sample")).get("value")).doubleValue();}
    /** Reads like the Runner: page by page from the last cursor until WAITING/END, returning every delivered item. */
    private List<Map<String,Object>> consume(UUID run,List<String> cursor,String terminal)throws Exception{
        var all=new ArrayList<Map<String,Object>>();
        for(int page=0;;page++){
            assertThat(page).as("bounded page count").isLessThan(100);
            var reply=cursor.isEmpty()?input(run,200,"maxItems",3):input(run,200,"cursor",cursor.getLast(),"maxItems",3);
            if(!reply.get("state").equals("DATA")){assertThat(reply).isEqualTo(Map.of("state",terminal));return all;}
            assertThat(items(reply)).hasSizeLessThanOrEqualTo(3);all.addAll(items(reply));cursor.add((String)items(reply).getLast().get("cursor"));
        }
    }

    @BeforeEach void continuousVd()throws Exception {
        when(engine.authenticate(eq("test-pod-token"))).thenReturn(new ProcessingEngineGateway.Identity(podUid,"my-ekuiper-0","worker"));
        when(gateway.authenticatePod(any(),eq("source-pod-proof"))).thenAnswer(c->{RuntimeInstance r=c.getArgument(0);return new RuntimePod(r.jobUid(),r.producerPodUid(),r.nodeUid(),r.nodeName());});
        var device=Map.of("metadata",Map.of("name","full-sensor","namespace","edgeai-devices","uid",uid.toString(),"labels",Map.of("app.kubernetes.io/part-of","edgeai"),
            "annotations",Map.of("edgeai.io/device-profile",encode(sensorProfile),ObservationIdentity.ANNOTATION,sourceGeneration.toString())),
            "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature",
            "visitors",Map.of("configData",Map.of("dataType","float64","unit","°C","sourceGeneration",sourceGeneration.toString(),"configurationSha256",DeviceProfileCompiler.configuration(sensorProfile))),
            "pushMethod",Map.of("mqtt",Map.of("address","tcp://127.0.0.1:1883","topic","edgeai/devices/full-sensor/temperature","qos",1))))));
        when(devices.request(eq("GET"),anyString(),isNull())).thenAnswer(c->{
            String path=c.getArgument(1);
            if(path.endsWith("/devices/full-sensor"))return new DeviceResourceGateway.Reply(200,encode(device));
            if(path.contains("/devices?"))return new DeviceResourceGateway.Reply(200,encode(Map.of("items",List.of(device))));
            if(path.endsWith("/nodes/edge-a"))return new DeviceResourceGateway.Reply(200,encode(Map.of("metadata",Map.of("uid",UUID.randomUUID().toString()),"status",Map.of("addresses",List.of(Map.of("type","InternalIP","address","198.51.100.4"))))));
            return new DeviceResourceGateway.Reply(404,"{}");
        });
        var created=call(post("/api/v1/virtual-devices/continuous").contentType("application/json").content(encode(Map.of("key","full-vd-"+UUID.randomUUID(),"displayName","전체 관측",
            "sources",List.of(Map.of("sourceKey","a","deviceId","full-sensor","properties",List.of("temperature"),"maxAgeSeconds",30)),
            "continuous",Map.of("intervalSeconds",1,"retentionDays",7,"resultStorage",Map.of("mode","NONE"),"outputs",List.of(Map.of("name","temperature","expression","a","operation","scalar","unit","°C")))))),201);
        vd=(String)created.get("id");session=UUID.randomUUID().toString();
        call(post("/api/v1/virtual-devices/"+vd+"/provision").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content("{\"revision\":0}"),202);
        var plan=decode(mvc.perform(engineCall("poll",Map.of("sessionId",session,"sequence",engineSequence++,"reports",List.of()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var execution=((List<?>)plan.get("executions")).stream().map(v->(Map<?,?>)v).filter(v->vd.equals(v.get("vdId"))).findFirst().orElseThrow();
        generation=(String)execution.get("generation");
        var rules=((List<?>)execution.get("rules")).stream().map(r->((Map<?,?>)r).get("id")).toList();
        mvc.perform(engineCall("poll",Map.of("sessionId",session,"sequence",engineSequence++,"reports",List.of(Map.of("vdId",vd,"generation",generation,"state","RUNNING","reason","HEALTHY","ruleIds",rules)))))
            .andExpect(status().isOk());
    }

    @Test void concurrentEngineCommitsAndRetriesAreDeliveredOnceWithoutSkippingAndDrainDeliversBacklogBeforeEnd()throws Exception {
        long slot=Instant.now().getEpochSecond();
        assertThat(results(result(slot,0))).isEqualTo(1);
        UUID run=run();
        var pool=Executors.newFixedThreadPool(8);var cursor=new ArrayList<String>();var delivered=new ArrayList<Map<String,Object>>();
        try{
            var commits=new ArrayList<Future<Integer>>();
            for(int i=1;i<=8;i++){long s=slot-14+i;double v=i;commits.add(pool.submit(()->results(result(s,v))));commits.add(pool.submit(()->results(result(s,v))));}
            // The Runner keeps reading while engine transactions commit; it must never pass an uncommitted position.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
            while(!commits.stream().allMatch(Future::isDone)){
                assertThat(System.nanoTime()).as("engine commits finish within the bound").isLessThan(deadline);
                delivered.addAll(consume(run,cursor,"WAITING"));
            }
            int accepted=0;for(var c:commits)accepted+=c.get(BOUND_SECONDS,TimeUnit.SECONDS);
            assertThat(accepted).isEqualTo(8);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(BOUND_SECONDS,TimeUnit.SECONDS)).as("engine clients stopped").isTrue();}
        delivered.addAll(consume(run,cursor,"WAITING"));
        assertThat(delivered.stream().map(WorkflowFullObservationVdIntegrationTest::value).sorted().toList()).containsExactly(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0);
        assertThat(delivered.stream().map(i->i.get("sampleId")).distinct()).hasSize(8);
        assertThat(results(result(slot-7,7),result(slot-6,8))).isZero();
        assertThat(input(run,200,"cursor",cursor.getLast())).isEqualTo(Map.of("state","WAITING"));

        assertThat(results(result(slot-4,9),result(slot-3,10),result(slot-2,11))).isEqualTo(3);
        call(post("/api/v1/workflow-runs/"+run+"/drain").contentType("application/json").content("{}"),200);
        assertThat(results(result(slot-1,12))).isEqualTo(1);
        var backlog=consume(run,cursor,"END");
        assertThat(backlog.stream().map(WorkflowFullObservationVdIntegrationTest::value).toList()).containsExactly(9.0,10.0,11.0);
    }
}
