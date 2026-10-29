package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.domain.profile.ProfileIdentity;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public profile/preview and Run-create seams on an isolated PostgreSQL database. Costs are hand-computed literals. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OperationalSchedulingIntegrationTest.TimeConfiguration.class)
class OperationalSchedulingIntegrationTest {
    static final class TestClock extends Clock {
        private volatile Instant time=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return time;}
        void advance(long seconds){time=time.plusSeconds(seconds);}
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary TestClock schedulingClock(){return new TestClock();} }
    @Autowired TestClock clock;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final JsonMapper json=new JsonMapper();
    private String encode(Object value){return json.writeValueAsString(value);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int statusCode) throws Exception {
        String body=mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return json.readTree(body.isEmpty()?"{}":body);
    }
    private JsonNode call(String path,String key,Object body,int statusCode) throws Exception {
        var request=post(path).contentType("application/json").content(encode(body));
        if(key!=null)request.header("Idempotency-Key",key);
        return perform(request,statusCode);
    }

    private record Nodes(UUID fast,UUID near,UUID unlabeled,UUID notReady,UUID smallMemory,UUID arm) {
        List<UUID> all(){return List.of(fast,near,unlabeled,notReady,smallMemory,arm);}
    }
    private UUID node(String arch,String status,String labels,String allocatable) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at,allocatable) VALUES (?,?,?,'linux',?,'4','4Gi',CAST(? AS jsonb),?,CAST(? AS jsonb))",
            id,"sched-"+id,arch,status,labels,java.sql.Timestamp.from(clock.instant()),allocatable);
        return id;
    }
    private static final String POOL="{\"edgeai.io/pool\":\"edge\"}",ENOUGH="{\"cpu\":\"4\",\"memory\":\"8Gi\",\"pods\":\"110\"}";
    private Nodes nodes() {
        return new Nodes(node("amd64","READY",POOL,ENOUGH),node("amd64","READY",POOL,ENOUGH),node("amd64","READY","{}",ENOUGH),
            node("amd64","NOT_READY",POOL,ENOUGH),node("amd64","READY",POOL,"{\"cpu\":\"4\",\"memory\":\"32Mi\"}"),node("arm64","READY",POOL,ENOUGH));
    }
    private UUID service(List<String> inputs) throws Exception {return service(inputs,List.of());}
    @SuppressWarnings("unchecked") private UUID service(List<String> inputs,List<String> optional) throws Exception {
        var spec=(Map<String,Object>)json.readValue(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")),Map.class);
        var ports=new TreeMap<String,Object>();for(String port:inputs)ports.put(port,Map.of("mediaType","application/json","maxBytes",1048576,"required",true));
        for(String port:optional)ports.put(port,Map.of("mediaType","application/json","maxBytes",1048576,"required",false));
        spec.put("inputs",ports);spec.put("platform",Map.of("os","linux","architectures",List.of("amd64")));spec.put("nodeSelector",Map.of("edgeai.io/pool","edge"));
        return profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","sched-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private Map<String,Object> task(String key,UUID service){return Map.of("key",key,"serviceProfileVersionId",service.toString(),"parameters",Map.of());}
    private Map<String,Object> edge(String from,String to,String port){return Map.of("fromTask",from,"toTask",to,"fromPort","output","toPort",port,"mode","BATCH");}
    private UUID publish(List<Map<String,Object>> tasks,List<Map<String,Object>> edges) {
        var workflow=workflows.create(encode(Map.of("key","sched-"+UUID.randomUUID(),"displayName","배치 시험"))).value();
        return workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",tasks,"dependencies",edges))).value().id();
    }
    /** split → {left,right} → join. The optional unconnected split input is never downloaded by the Runner, so it has no cost. */
    private UUID forkJoin() throws Exception {
        UUID source=service(List.of(),List.of("seed")),single=service(List.of("input")),pair=service(List.of("left","right"));
        return publish(List.of(task("split",source),task("left",single),task("right",single),task("join",pair)),
            List.of(edge("split","left","input"),edge("split","right","input"),edge("left","join","left"),edge("right","join","right")));
    }
    /** Valid source→sink STREAM DAG from the published stream SERVICE example; profiles for it are unsupported. */
    @SuppressWarnings("unchecked") private UUID streamDag() throws Exception {
        UUID[] ids=new UUID[2];
        for(int i=0;i<2;i++) {
            var spec=(Map<String,Object>)json.readValue(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")),Map.class);
            var stream=(Map<String,Object>)spec.get("stream");var port=Map.of("mediaType","application/json","maxPayloadBytes",4096);
            stream.put("inputs",i==0?Map.of("a",port):Map.of("input",port));stream.put("outputs",i==0?Map.of("output",port):Map.of());
            ids[i]=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","sched-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
        }
        return publish(List.of(task("source",ids[0]),task("sink",ids[1])),
            List.of(Map.of("fromTask","source","toTask","sink","fromPort","output","toPort","input","mode","STREAM")));
    }
    private static Map<String,Object> costs(Nodes n,double fast,double near) {
        var values=new TreeMap<String,Object>();values.put(n.fast().toString(),fast);values.put(n.near().toString(),near);
        for(UUID cheap:List.of(n.unlabeled(),n.notReady(),n.smallMemory(),n.arm()))values.put(cheap.toString(),0.5);
        return values;
    }
    private static Map<String,Object> cost(Map<String,Object> compute,Map<String,Object> downloads,Map<String,Object> uploads) {
        return Map.of("computeMs",compute,"downloadMs",downloads,"uploadMs",uploads);
    }
    /** Hand calculation: split fast 10+5=15; left near 5+60+5=70 (fast 30+50+20=100); right fast 5+20+5=30; join near 1+1+10+4=16. E2E max(15+70,15+30)+16=101. */
    private Map<String,Object> forkJoinTasks(Nodes n) {
        return Map.of(
            "split",cost(costs(n,10,40),Map.of(),Map.of("output",costs(n,5,5))),
            "left",cost(costs(n,50,60),Map.of("input",costs(n,30,5)),Map.of("output",costs(n,20,5))),
            "right",cost(costs(n,20,80),Map.of("input",costs(n,5,5)),Map.of("output",costs(n,5,5))),
            "join",cost(costs(n,10,10),Map.of("left",costs(n,10,1),"right",costs(n,10,1)),Map.of("output",costs(n,4,4))));
    }
    private Map<String,Object> profile(UUID version,Map<String,Object> tasks,Instant validUntil) {
        var body=new LinkedHashMap<String,Object>();
        body.put("workflowVersionId",version.toString());body.put("measuredAt",clock.instant().minusSeconds(3600).toString());body.put("validUntil",validUntil.toString());
        body.put("provenance",Map.of("kind","MEASURED_BATCH_ARTIFACT","evidenceId","runner-batch-2026-10-29","sampleCount",12));
        body.put("parameters",Map.of("scale",2));body.put("tasks",tasks);return body;
    }
    private JsonNode register(Map<String,Object> body,int statusCode) throws Exception {return call("/api/v1/scheduling-profiles",UUID.randomUUID().toString(),body,statusCode);}
    private JsonNode preview(String profileId,Object parameters,int statusCode) throws Exception {return call("/api/v1/scheduling-profiles/"+profileId+"/placement-preview",null,Map.of("parameters",parameters),statusCode);}
    private Map<String,Object> runRequest(UUID version,String profileId) {
        var body=new LinkedHashMap<String,Object>();body.put("workflowVersionId",version.toString());body.put("parameters",Map.of("scale",2));
        body.put("execution",Map.of("mode","AUTO"));if(profileId!=null)body.put("scheduling",Map.of("profileId",profileId));return body;
    }
    private int rows(String sql,Object... args){return jdbc.queryForObject(sql,Integer.class,args);}

    @Test void measuredProfileIsImmutableAndPreviewPicksTransferAwareEligibleNodes() throws Exception {
        var n=nodes();UUID version=forkJoin();var body=profile(version,forkJoinTasks(n),clock.instant().plusSeconds(86400));
        String key=UUID.randomUUID().toString();
        var created=call("/api/v1/scheduling-profiles",key,body,201);
        String id=created.path("id").asText();assertThat(created.path("digest").asText()).startsWith("sha256:");
        assertThat(call("/api/v1/scheduling-profiles",key,body,200).path("id").asText()).isEqualTo(id);
        var changed=new LinkedHashMap<>(body);changed.put("parameters",Map.of("scale",3));
        assertThat(call("/api/v1/scheduling-profiles",key,changed,409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        var stored=perform(get("/api/v1/scheduling-profiles/"+id),200);
        Files.createDirectories(Path.of("build/test-evidence"));
        Files.writeString(Path.of("build/test-evidence/scheduling-profile-response.json"),json.writeValueAsString(stored));
        assertThat(stored.path("tasks").path("left").path("downloadMs").path("input").path(n.near().toString()).asDouble()).isEqualTo(5.0);
        assertThat(stored.path("provenance").path("sampleCount").asInt()).isEqualTo(12);
        assertThat(perform(get("/api/v1/scheduling-profiles/"+UUID.randomUUID()),404).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_NOT_FOUND");

        var plan=preview(id,Map.of("scale",2),200);
        assertThat(plan.path("method").asText()).isEqualTo("ARTIFACT_PROFILE_MIN_COST");
        assertThat(plan.path("predicted").asBoolean()).isTrue();
        assertThat(plan.path("profileDigest").asText()).isEqualTo(created.path("digest").asText());
        assertThat(plan.path("placements").path("split").asText()).isEqualTo(n.fast().toString());
        assertThat(plan.path("placements").path("left").asText()).as("transfer costs outweigh faster compute").isEqualTo(n.near().toString());
        assertThat(plan.path("placements").path("right").asText()).isEqualTo(n.fast().toString());
        assertThat(plan.path("placements").path("join").asText()).isEqualTo(n.near().toString());
        assertThat(plan.path("predictedCriticalPathMs").asDouble()).isEqualTo(101.0);
        assertThat(plan.path("criticalPath").valueStream().map(JsonNode::asText).toList()).containsExactly("split","left","join");
        Map<UUID,List<String>> reasons=new HashMap<>();
        for(var candidate:plan.path("candidates").path("left"))reasons.put(UUID.fromString(candidate.path("nodeId").asText()),candidate.path("reasons").valueStream().map(JsonNode::asText).toList());
        assertThat(reasons.get(n.fast())).isEmpty();assertThat(reasons.get(n.near())).isEmpty();
        assertThat(reasons.get(n.unlabeled())).containsExactly("NODE_SELECTOR");
        assertThat(reasons.get(n.notReady())).containsExactly("NOT_READY");
        assertThat(reasons.get(n.smallMemory())).containsExactly("ALLOCATABLE_MEMORY");
        assertThat(reasons.get(n.arm())).containsExactly("ARCHITECTURE");
        assertThat(preview(id,Map.of("scale",3),409).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_MISMATCH");

        UUID unprofiled=node("amd64","READY",POOL,ENOUGH);
        var refreshed=preview(id,Map.of("scale",2),200);boolean listed=false;
        for(var candidate:refreshed.path("candidates").path("join"))if(candidate.path("nodeId").asText().equals(unprofiled.toString())) {
            listed=true;assertThat(candidate.path("eligible").asBoolean()).isFalse();
            assertThat(candidate.path("reasons").valueStream().map(JsonNode::asText).toList()).containsExactly("NOT_PROFILED");
        }
        assertThat(listed).isTrue();

        var expiring=register(profile(version,forkJoinTasks(n),clock.instant().plusSeconds(5)),201).path("id").asText();
        clock.advance(10);
        assertThat(preview(expiring,Map.of("scale",2),409).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_EXPIRED");
    }

    @Test void incompleteFutureOrUnsupportedProfilesAreRejectedWithoutStorage() throws Exception {
        var n=nodes();UUID version=forkJoin();Instant until=clock.instant().plusSeconds(86400);
        int before=rows("SELECT count(*) FROM edgeai.scheduling_profile");
        var missingUpload=new HashMap<>(forkJoinTasks(n));missingUpload.put("join",cost(costs(n,10,10),Map.of("left",costs(n,10,1),"right",costs(n,10,1)),Map.of()));
        var missingNode=new HashMap<>(forkJoinTasks(n));var partial=new TreeMap<>(costs(n,30,5));partial.remove(n.near().toString());
        missingNode.put("left",cost(costs(n,50,60),Map.of("input",partial),Map.of("output",costs(n,20,5))));
        var negative=new HashMap<>(forkJoinTasks(n));var below=new TreeMap<>(costs(n,50,60));below.put(n.fast().toString(),-1);
        negative.put("left",cost(below,Map.of("input",costs(n,30,5)),Map.of("output",costs(n,20,5))));
        var extraTask=new HashMap<>(forkJoinTasks(n));extraTask.put("ghost",forkJoinTasks(n).get("split"));
        var missingTask=new HashMap<>(forkJoinTasks(n));missingTask.remove("right");
        var unconnected=new HashMap<>(forkJoinTasks(n));unconnected.put("split",cost(costs(n,10,40),Map.of("seed",costs(n,1,1)),Map.of("output",costs(n,5,5))));
        for(var tasks:List.<Map<String,Object>>of(missingUpload,missingNode,negative,extraTask,missingTask,unconnected))
            assertThat(register(profile(version,tasks,until),400).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_INVALID");
        var future=profile(version,forkJoinTasks(n),until);future.put("measuredAt",clock.instant().plusSeconds(60).toString());
        var inverted=profile(version,forkJoinTasks(n),clock.instant().minusSeconds(7200));
        var noSamples=profile(version,forkJoinTasks(n),until);noSamples.put("provenance",Map.of("kind","MEASURED_BATCH_ARTIFACT","evidenceId","x","sampleCount",0));
        var research=profile(version,forkJoinTasks(n),until);research.put("provenance",Map.of("kind","RESEARCH_FIXTURE","evidenceId","x","sampleCount",3));
        for(var body:List.of(future,inverted,noSamples,research))
            assertThat(register(body,400).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_INVALID");
        var unknownNode=new HashMap<>(forkJoinTasks(n));var stranger=new TreeMap<>(costs(n,10,40));stranger.put(UUID.randomUUID().toString(),1);
        unknownNode.put("split",cost(stranger,Map.of(),Map.of("output",new TreeMap<>(stranger))));
        assertThat(register(profile(version,unknownNode,until),404).path("code").asText()).isEqualTo("NODE_NOT_FOUND");
        assertThat(register(profile(UUID.randomUUID(),forkJoinTasks(n),until),404).path("code").asText()).isEqualTo("WORKFLOW_NOT_FOUND");
        UUID loose=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","sched-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("type","synthetic-test")))).version().id();
        UUID looseVersion=publish(List.of(task("only",loose)),List.of());
        assertThat(register(profile(looseVersion,Map.of("only",cost(costs(n,1,1),Map.of(),Map.of("output",costs(n,1,1)))),until),409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        assertThat(register(profile(streamDag(),Map.of("source",cost(costs(n,1,1),Map.of(),Map.of("result",costs(n,1,1))),
            "sink",cost(costs(n,1,1),Map.of(),Map.of("result",costs(n,1,1)))),until),409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        assertThat(rows("SELECT count(*) FROM edgeai.scheduling_profile")).isEqualTo(before);

        var blocked=register(profile(version,Map.of(
            "split",cost(Map.of(n.unlabeled().toString(),1),Map.of(),Map.of("output",Map.of(n.unlabeled().toString(),1))),
            "left",forkJoinTasks(n).get("left"),"right",forkJoinTasks(n).get("right"),"join",forkJoinTasks(n).get("join")),until),201).path("id").asText();
        assertThat(preview(blocked,Map.of("scale",2),409).path("code").asText()).isEqualTo("SCHEDULING_NO_CANDIDATE");
    }

    @Test void wideDagSelectsPerTaskMinimumWithoutEnumeratingCombinations() throws Exception {
        UUID a=node("amd64","READY",POOL,ENOUGH),b=node("amd64","READY",POOL,ENOUGH);
        UUID source=service(List.of()),single=service(List.of("input"));
        var tasks=new ArrayList<Map<String,Object>>();var edges=new ArrayList<Map<String,Object>>();var costs=new TreeMap<String,Object>();
        tasks.add(task("root",source));
        costs.put("root",cost(Map.of(a.toString(),3,b.toString(),9),Map.of(),Map.of("output",Map.of(a.toString(),1,b.toString(),1))));
        for(int i=0;i<127;i++) {
            String key="w"+i;tasks.add(task(key,single));edges.add(edge("root",key,"input"));
            double onA=i%2==0?10:20,onB=i%2==0?20:10;if(i==126){onA=100;onB=50;}
            costs.put(key,cost(Map.of(a.toString(),onA,b.toString(),onB),Map.of("input",Map.of(a.toString(),1,b.toString(),1)),Map.of("output",Map.of(a.toString(),1,b.toString(),1))));
        }
        UUID version=publish(tasks,edges);
        var body=profile(version,costs,clock.instant().plusSeconds(86400));body.put("parameters",Map.of("scale",2));
        String id=register(body,201).path("id").asText();
        long started=System.nanoTime();var plan=preview(id,Map.of("scale",2),200);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(10));
        assertThat(plan.path("placements").size()).isEqualTo(128);
        assertThat(plan.path("placements").path("w0").asText()).isEqualTo(a.toString());
        assertThat(plan.path("placements").path("w1").asText()).isEqualTo(b.toString());
        assertThat(plan.path("placements").path("w126").asText()).isEqualTo(b.toString());
        assertThat(plan.path("predictedCriticalPathMs").asDouble()).isEqualTo(4.0+52.0);
        assertThat(plan.path("criticalPath").valueStream().map(JsonNode::asText).toList()).containsExactly("root","w126");
    }

    @Test void zeroCostSinkStaysOnReportedCriticalPath() throws Exception {
        UUID only=node("amd64","READY",POOL,ENOUGH);String node=only.toString();
        UUID version=publish(List.of(task("a",service(List.of())),task("z",service(List.of("input")))),List.of(edge("a","z","input")));
        var tasks=Map.<String,Object>of("a",cost(Map.of(node,10),Map.of(),Map.of("output",Map.of(node,0))),
            "z",cost(Map.of(node,0),Map.of("input",Map.of(node,0)),Map.of("output",Map.of(node,0))));
        var plan=preview(register(profile(version,tasks,clock.instant().plusSeconds(86400)),201).path("id").asText(),Map.of("scale",2),200);
        assertThat(plan.path("predictedCriticalPathMs").asDouble()).isEqualTo(10.0);
        assertThat(plan.path("criticalPath").valueStream().map(JsonNode::asText).toList()).containsExactly("a","z");
    }

    @Test void scheduledRunPinsInitialNodesAndReplaysUserIntentAfterProfileOrNodeChanges() throws Exception {
        var n=nodes();UUID version=forkJoin();
        String profileId=register(profile(version,forkJoinTasks(n),clock.instant().plusSeconds(120)),201).path("id").asText();
        String key=UUID.randomUUID().toString();
        var run=call("/api/v1/workflow-runs",key,runRequest(version,profileId),201);
        String runId=run.path("id").asText();
        assertThat(run.path("scheduling").path("profileId").asText()).isEqualTo(profileId);
        assertThat(run.path("taskExecutions").path("left").path("mode").asText()).isEqualTo("NODE");
        assertThat(run.path("taskExecutions").path("left").path("nodeId").asText()).isEqualTo(n.near().toString());
        var detail=perform(get("/api/v1/workflow-runs/"+runId),200);
        Map<String,String> initial=new HashMap<>();
        for(var task:detail.path("tasks"))initial.put(task.path("key").asText(),task.path("initialNodeId").asText());
        assertThat(initial).containsEntry("split",n.fast().toString()).containsEntry("left",n.near().toString())
            .containsEntry("right",n.fast().toString()).containsEntry("join",n.near().toString());
        var root=jdbc.queryForObject("SELECT a.node_id FROM edgeai.task_attempt a JOIN edgeai.task t ON t.id=a.task_id JOIN edgeai.task_definition d ON d.id=t.definition_id WHERE t.run_id=? AND d.task_key='split'",UUID.class,UUID.fromString(runId));
        assertThat(root).isEqualTo(n.fast());
        var decision=perform(get("/api/v1/workflow-runs/"+runId+"/scheduling-decision"),200);
        assertThat(decision.path("runId").asText()).isEqualTo(runId);
        assertThat(decision.path("profileId").asText()).isEqualTo(profileId);
        assertThat(decision.path("predictedCriticalPathMs").asDouble()).isEqualTo(101.0);
        assertThat(decision.path("effectiveTaskExecutions").path("join").path("nodeId").asText()).isEqualTo(n.near().toString());

        jdbc.update("UPDATE edgeai.execution_node SET observed_status='NOT_READY' WHERE id IN (?,?)",n.fast(),n.near());
        clock.advance(300);
        var replay=call("/api/v1/workflow-runs",key,runRequest(version,profileId),200);
        assertThat(replay.path("id").asText()).isEqualTo(runId);
        assertThat(replay.path("taskExecutions")).isEqualTo(run.path("taskExecutions"));
        var different=runRequest(version,profileId);different.put("parameters",Map.of("scale",9));
        assertThat(call("/api/v1/workflow-runs",key,different,409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),runRequest(version,profileId),409).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_EXPIRED");
    }

    @Test void rejectedSchedulingLeavesNoRunTaskOrDecisionAndPlainRunsAreUnchanged() throws Exception {
        var n=nodes();UUID version=forkJoin();UUID other=forkJoin();
        String profileId=register(profile(version,forkJoinTasks(n),clock.instant().plusSeconds(86400)),201).path("id").asText();
        int runs=rows("SELECT count(*) FROM edgeai.workflow_run"),tasks=rows("SELECT count(*) FROM edgeai.task"),decisions=rows("SELECT count(*) FROM edgeai.workflow_run_scheduling");
        var conflict=runRequest(version,profileId);conflict.put("taskExecutions",Map.of("split",Map.of("mode","AUTO")));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),conflict,400).path("code").asText()).isEqualTo("SCHEDULING_CONFLICT");
        var pinned=runRequest(version,profileId);pinned.put("execution",Map.of("mode","NODE","nodeId",n.fast().toString()));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),pinned,409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        var remote=runRequest(version,profileId);remote.put("execution",Map.of("mode","REMOTE","providerKey","valid-provider"));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),remote,409).path("code").asText()).as("runtime disabled is not the cause").isEqualTo("SCHEDULING_UNSUPPORTED");
        var device=runRequest(version,profileId);device.put("device",Map.of("id",UUID.randomUUID().toString(),"outputs",List.of("temperature")));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),device,409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        var camera=runRequest(version,profileId);camera.put("camera",Map.of("id","camera-1","uid",UUID.randomUUID().toString()));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),camera,409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        var streamed=runRequest(version,profileId);
        streamed.put("streamInputs",List.of(Map.of("deviceId",UUID.randomUUID().toString(),"sourcePort","samples","toTask","split","toPort","seed","maxPayloadBytes",4096)));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),streamed,409).path("code").asText()).isEqualTo("SCHEDULING_UNSUPPORTED");
        var mismatch=runRequest(version,profileId);mismatch.put("parameters",Map.of("scale",3));
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),mismatch,409).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_MISMATCH");
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),runRequest(other,profileId),409).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_MISMATCH");
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),runRequest(version,UUID.randomUUID().toString()),404).path("code").asText()).isEqualTo("SCHEDULING_PROFILE_NOT_FOUND");
        jdbc.update("UPDATE edgeai.execution_node SET observed_status='NOT_READY' WHERE id IN (?,?)",n.fast(),n.near());
        assertThat(call("/api/v1/workflow-runs",UUID.randomUUID().toString(),runRequest(version,profileId),409).path("code").asText()).isEqualTo("SCHEDULING_NO_CANDIDATE");
        assertThat(rows("SELECT count(*) FROM edgeai.workflow_run")).isEqualTo(runs);
        assertThat(rows("SELECT count(*) FROM edgeai.task")).isEqualTo(tasks);
        assertThat(rows("SELECT count(*) FROM edgeai.workflow_run_scheduling")).isEqualTo(decisions);

        var plain=call("/api/v1/workflow-runs",UUID.randomUUID().toString(),runRequest(version,null),201);
        assertThat(plain.path("scheduling").isNull() || plain.path("scheduling").isMissingNode()).isTrue();
        assertThat(plain.path("taskExecutions").size()).isZero();
        assertThat(perform(get("/api/v1/workflow-runs/"+plain.path("id").asText()+"/scheduling-decision"),404).path("code").asText()).isEqualTo("SCHEDULING_DECISION_NOT_FOUND");
    }
}
