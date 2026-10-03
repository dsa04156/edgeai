package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.StreamConnectionSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public MVC and real PostgreSQL transactions. Pod identities, broker receipts and S3 receipts are explicit fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.runtime.namespace=public-stream-test",
    "edgeai.vd.enabled=true","edgeai.vd.lease-seconds=60",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true",
    "edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","edgeai.stream.lease-seconds=120"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class StreamRunIntegrationTest {
    private static final Path KEY=key();
    private static Path key(){try{
        var p=Files.createTempFile("edgeai-public-stream-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);}
    @AfterAll static void removeKey()throws Exception{Files.delete(KEY);}
    @MockitoBean RuntimeGateway gateway;@MockitoBean S3ArtifactStore storage;@MockitoBean MosquittoStreamBroker broker;
    @MockitoBean VDGateway vdGateway;
    @MockitoBean StreamAuthorityWorker authorityWorker;@MockitoBean StreamConnectionSettings connection;@MockitoBean StreamRunWorker scheduledWorker;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired DeviceService devices;@Autowired DeviceRepository deviceStore;
    @Autowired ExecutionService runs;@Autowired ExecutionRepository executions;@Autowired RuntimeLifecycleService lifecycle;@Autowired RuntimeRepository runtimes;
    @Autowired StreamRunService streams;@Autowired StreamRunRepository store;@Autowired DataRouteRepository routes;@Autowired DataRouteService routeLifecycle;
    @Autowired StreamExecutionRepository completions;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired PlatformTransactionManager transactions;
    @Autowired DeviceStreamTokenService deviceTokens;
    @Autowired WorkflowRepository workflowStore;
    @Autowired StreamExecutionService streamExecution;@Autowired StreamCheckpointRepository checkpoints;
    @Autowired OffloadService offloads;@Autowired OffloadRepository offloadStore;@Autowired NodeService nodes;
    @Autowired VirtualDeviceService virtualDevices;@Autowired VirtualDeviceRepository vds;@Autowired VDLifecycleService vdLifecycle;
    @Autowired VDRuntimeRepository supervisors;@Autowired VDTaskRepository allocations;@Autowired VDPollService vdPoll;
    @Autowired RunnerTokenService runnerTokens;
    private final JsonDocuments json=new JsonDocuments();
    private record Definition(UUID version,List<Map<String,Object>> inputs,List<Device> devices){}
    private String encode(Object value){return json.canonical(value);}
    private UUID profile(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key","public-stream-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private UUID service(List<String> inputs,boolean output,boolean fileInput)throws Exception{
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/"+(inputs==null?"service-execution":"service-stream")+".example.json")));
        if(inputs!=null){var stream=(Map<String,Object>)spec.get("stream");var ports=new TreeMap<String,Object>();
            inputs.forEach(p->ports.put(p,Map.of("mediaType","application/json","maxPayloadBytes",4096)));
            stream.put("inputs",ports);stream.put("outputs",output?Map.of("output",Map.of("mediaType","application/json","maxPayloadBytes",4096)):Map.of());}
        if(fileInput)spec.put("inputs",Map.of("file-input",Map.of("mediaType","application/json","maxBytes",1048576,"required",true)));
        return profile(ProfileIdentity.Kind.SERVICE,spec);
    }
    private Device device(){var p=profile(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var d=devices.create(encode(Map.of("key","public-stream-"+UUID.randomUUID(),"displayName","Stream test","profileVersionId",p.toString(),"sourceMode","SYNTHETIC"))).value();
        devices.openSession(d.id(),encode(Map.of("bootId",UUID.randomUUID().toString())));return d;}
    private Map<String,Object> input(Device d,String task,String port){return Map.of("deviceId",d.id().toString(),"sourcePort","samples","toTask",task,"toPort",port,"maxPayloadBytes",4096);}
    private Map<String,Object> edge(String from,String to,String output,String input,String mode){return Map.of("fromTask",from,"toTask",to,"fromPort",output,"toPort",input,"mode",mode);}
    private UUID version(Map<String,UUID> specs,List<Map<String,Object>> edges){
        var w=workflows.create(encode(Map.of("key","public-stream-"+UUID.randomUUID(),"displayName","Public stream fixture"))).value();
        var tasks=specs.entrySet().stream().map(e->Map.of("key",e.getKey(),"serviceProfileVersionId",e.getValue().toString(),"parameters",Map.of())).toList();
        return workflows.publish(w.id(),encode(Map.of("version","1.0.0","tasks",tasks,"dependencies",edges))).value().id();
    }
    private Definition definition(boolean upstream,boolean unrelated)throws Exception{
        var specs=new TreeMap<String,UUID>();specs.put("source",service(List.of("a","b"),true,false));specs.put("sink",service(List.of("input"),false,upstream));
        var edges=new ArrayList<Map<String,Object>>();edges.add(edge("source","sink","output","input","STREAM"));
        if(upstream){specs.put("prep",service(null,false,false));edges.add(edge("prep","sink","output","file-input","BATCH"));}
        if(unrelated){specs.put("independent",service(null,false,false));specs.put("child",service(null,false,true));edges.add(edge("sink","child","result","file-input","BATCH"));}
        var a=device();var b=device();return new Definition(version(specs,edges),List.of(input(a,"source","a"),input(b,"source","b")),List.of(a,b));
    }
    private Map<String,Object> request(Definition d){return new TreeMap<>(Map.of("workflowVersionId",d.version().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of(),"streamInputs",d.inputs()));}
    private String perform(MockHttpServletRequestBuilder r,int code)throws Exception{return mvc.perform(r.with(user("test")).with(csrf())).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();}
    private String create(String key,Object value,int code)throws Exception{return perform(post("/api/v1/workflow-runs").header("Idempotency-Key",key).contentType("application/json").content(encode(value)),code);}
    private UUID create(Definition d)throws Exception{return UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),request(d),201))).get("id"));}
    private MockHttpServletRequestBuilder deviceRoutes(DeviceSession session,UUID run,int limit,int offset){
        return post("/internal/v1/devices/"+session.deviceId()+"/sessions/"+session.id()+"/streams/routes")
            .header("Authorization","Bearer "+deviceTokens.issue(session)).contentType("application/json")
            .content(encode(Map.of("epoch",session.epoch(),"runId",run.toString(),"limit",limit,"offset",offset)));
    }
    private Task task(UUID run,String name){return executions.tasks(run).stream().filter(t->t.key().equals(name)).findFirst().orElseThrow();}
    private TaskAttempt attempt(UUID run,String name){return executions.attempts(task(run,name).id()).getFirst();}
    private RuntimeLifecycleService.Assignment claim(UUID run,String name){var a=attempt(run,name);var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        lifecycle.submitted(a.id(),pod.jobUid());return lifecycle.claim(a.id(),a.epoch(),pod);}
    /** Every recovery scenario starts with the same public request used by real clients. */
    private UUID recoveryRun(Definition d,int attempts)throws Exception{
        var body=request(d);body.put("retry",Map.of("maxAttempts",attempts,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        return UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
    }
    @Test void publicRetryNormalizesReplayKeepsBudgetAndRejectsConflictingOrInvalidPolicies()throws Exception{
        var d=definition(false,false);var body=request(d);var key=UUID.randomUUID().toString();
        body.put("retry",Map.of("maxAttempts",3,"backoffSeconds",2,"maxElapsedSeconds",300,"retryOn",List.of("WORKLOAD_FAILED","RUNTIME_LOST")));
        var created=(Map<?,?>)json.decode(create(key,body,201));UUID id=UUID.fromString((String)created.get("id"));
        assertThat(executions.run(id,false).orElseThrow().retry()).isEqualTo(new RetryPolicy(3,2,300,Set.of("WORKLOAD_FAILED","RUNTIME_LOST")));
        body.put("retry",Map.of("maxAttempts",3,"backoffSeconds",2,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST","WORKLOAD_FAILED")));
        body.put("streamInputs",d.inputs().reversed());
        assertThat(((Map<?,?>)json.decode(create(key,body,200))).get("id")).isEqualTo(id.toString());
        body.put("retry",Map.of("maxAttempts",4,"backoffSeconds",2,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        create(key,body,409);
        assertThat(executions.attempts(task(id,"source").id())).hasSize(1);
        for(Object reasons:List.of(List.of(),List.of("OUTPUT_INVALID"),List.of("RUNTIME_LOST","RUNTIME_LOST"))){
            body=request(d);body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",30,"retryOn",reasons));
            rejected(d,body,400);
        }
        body=request(d);body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        var offload=new TreeMap<String,Object>();offload.put("cpuPercent",80);offload.put("memoryPercent",null);offload.put("latencyMicros",null);
        offload.putAll(Map.of("consecutiveSamples",2,"maxSampleAgeSeconds",30,"maxGapSeconds",10,"minRunningSeconds",10,
            "cooldownSeconds",10,"maxTransfers",1,"drainTimeoutSeconds",30,"startTimeoutSeconds",30));
        body.put("offload",offload);create(UUID.randomUUID().toString(),body,201);
    }
    private void finishPhysical(UUID run,String name){
        var a=attempt(run,name);var r=runtimes.byAttempt(a.id()).orElseThrow();
        // Explicit provisioning fixture: the CREATE operation has finished and the old Pod has disappeared.
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",r.id());
        lifecycle.confirmStopped(a.id());
    }
    private void revokeGroup(UUID run){
        for(var route:routes.forRun(run,20,0))routes.open(route.id()).ifPresent(g->{
            assertThat(g.fencedAt()).isNotNull();routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
        });
    }
    private void due(UUID run)throws Exception{
        var latest=executions.tasks(run).stream().map(t->executions.retry(t.id())).flatMap(Optional::stream)
            .map(TaskRetry::availableAt).max(Comparator.naturalOrder()).orElseThrow();
        long millis=Duration.between(Instant.now(),latest).toMillis();if(millis>=0)Thread.sleep(millis+20);
    }
    private void worker(){new StreamRunWorker(store,streams,lifecycle,"public-stream-test").tick();}
    private io.edgeai.app.config.RunnerPrincipal principal(UUID run,String name){
        var r=runtimes.byAttempt(attempt(run,name).id()).orElseThrow();
        if(r.vd()){
            var a=allocations.byRuntime(r.id()).orElseThrow();var v=supervisors.runtime(a.vdRuntimeId()).orElseThrow();
            return new io.edgeai.app.config.RunnerPrincipal(r.attemptId(),r.epoch(),null,
                new VDTaskProducer(v.id(),v.generation(),v.sessionId(),v.podUid(),v.nodeUid(),v.nodeName()));
        }
        return new io.edgeai.app.config.RunnerPrincipal(r.attemptId(),r.epoch(),new RuntimePod(r.jobUid(),r.producerPodUid(),r.nodeUid(),r.nodeName()));
    }
    private String identity(io.edgeai.app.config.RunnerPrincipal p,Object... extra){
        var value=new TreeMap<String,Object>(Map.of("epoch",p.epoch(),"podUid",p.podUid().toString()));
        for(int i=0;i<extra.length;i+=2)value.put((String)extra[i],extra[i+1]);return encode(value);
    }
    /** Checkpoint bytes/S3 receipt are fixtures here; actual signed-object finalization is tested separately. */
    private Map<String,StreamCheckpoint> seal(UUID run){return checkpoint(run,true);}
    private Map<String,StreamCheckpoint> checkpoint(UUID run,boolean seal){
        return checkpoint(run,seal,true);
    }
    private Map<String,StreamCheckpoint> checkpoint(UUID run,boolean seal,boolean complete){
        for(var route:routes.forRun(run,20,0)){var g=routes.open(route.id()).orElseThrow();
            routeLifecycle.activate(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));}
        var source=principal(run,"source");streamExecution.execution(source,identity(source));
        var saved=new HashMap<String,StreamCheckpoint>();
        for(String name:List.of("source","sink")){
            var t=task(run,name);var p=principal(run,name);var r=runtimes.byAttempt(p.attemptId()).orElseThrow();
            var relevant=routes.forRun(run,20,0).stream().filter(v->t.id().equals(v.sourceTaskId()) || t.id().equals(v.consumerTaskId())).toList();
            var inputs=new ArrayList<Object>();var outputs=new ArrayList<Object>();var cursors=new ArrayList<Object>();var ids=new ArrayList<UUID>();
            for(var route:relevant){var g=routes.open(route.id()).orElseThrow();ids.add(g.id());
                Object producer=route.deviceSource()?Map.of("kind","DEVICE_SESSION","deviceId",route.sourceDeviceId().toString(),"sessionId",g.producer().id().toString(),"epoch",g.producer().epoch())
                    :Map.of("kind","TASK_ATTEMPT","attemptId",g.producer().id().toString(),"epoch",g.producer().epoch());
                (t.id().equals(route.consumerTaskId())?inputs:outputs).add(Map.of("routeId",route.id().toString(),"generation",g.generation(),"producer",producer));
                cursors.add(Map.of("routeId",route.id().toString(),"received",3,"committed",3,"ended",seal));
            }
            var request=new StreamCheckpoint.Request(null,10,"d".repeat(64),100,"e".repeat(64),ids);var content=request.content(t.id(),p.attemptId());
            var profile=workflowStore.definitions(executions.run(run,false).orElseThrow().workflowVersionId()).stream().filter(d->d.id().equals(t.definitionId())).findFirst().orElseThrow().serviceProfileVersionId();
            var summary=encode(Map.of("manifest",Map.of("version",1,"inputs",inputs,"outputs",outputs,"limits",Map.of("max_frames",128,"max_buffer_bytes",16777216,"max_state_bytes",262144)),
                "revision",3,"routes",cursors,"stateSha256","f".repeat(64),"stateBytes",2));
            var cp=new StreamCheckpoint(UUID.randomUUID(),run,t.id(),p.attemptId(),r.id(),p.epoch(),p.podUid(),profile,request,3,summary,
                new VerifiedArtifact("fixture-only",content.objectKey(),UUID.randomUUID().toString(),content.sha256(),content.bytes(),content.mediaType()),Instant.now(),null);
            new TransactionTemplate(transactions).execute(tx->{executions.run(run,true);checkpoints.insert(cp);return null;});
            if(seal && complete)streamExecution.complete(p,identity(p,"checkpointId",cp.id().toString()));saved.put(name,cp);
        }
        if(!seal || !complete)return saved;
        finishDevices(run);
        assertThat(saved.values()).allMatch(c->completions.granted(c.attemptId()).isPresent());return saved;
    }
    private void finishDevices(UUID run){
        for(var pin:store.bindings(run)){var g=routes.open(pin.routeId()).orElseThrow();
            streamExecution.deviceComplete(new io.edgeai.app.config.DeviceStreamPrincipal(pin.deviceId(),pin.sessionId(),pin.epoch()),
                encode(Map.of("epoch",pin.epoch(),"generationId",g.id().toString(),"sequence",3)));}
    }
    private void commitFinalizer(UUID run,String name){
        var p=principal(run,name);var output=new ResultManifest.Output("result",2,"a".repeat(64),"application/json","fixture-version");
        var permit=lifecycle.prepareCommit(p.attemptId(),p.epoch(),p.podUid(),new ResultManifest(List.of(output)));
        var content=output.content(task(run,name).id(),p.attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("result",new VerifiedArtifact("fixture-only",content.objectKey(),output.versionId(),content.sha256(),content.bytes(),content.mediaType()))));
    }
    private void rejected(Definition d,Map<String,Object> request,int code)throws Exception{
        String key=UUID.randomUUID().toString();create(key,request,code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE idempotency_key=?",Integer.class,UUID.fromString(key))).isZero();
    }
    private <T>List<T> parallel(IntFunction<T> action)throws Exception{
        try(var pool=Executors.newFixedThreadPool(4)){var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<4;i++){int index=i;futures.add(pool.submit(()->{start.await();return action.apply(index);}));}
            start.countDown();var result=new ArrayList<T>();for(var f:futures)result.add(f.get(15,TimeUnit.SECONDS));return result;
        }
    }

    private UUID targetNode(){var id=UUID.randomUUID();nodes.recordSnapshot(List.of(new io.edgeai.domain.node.ExecutionNode(
        id,"transfer-"+id,"amd64","linux","READY","4","4Gi","{}",Instant.now())),Instant.now());return id;}
    private String transferBody(UUID run,String name,UUID target){return encode(Map.of("sourceAttemptId",attempt(run,name).id().toString(),
        "targetNodeId",target.toString(),"drainTimeoutSeconds",60,"startTimeoutSeconds",60));}
    private OffloadOperation transfer(UUID run,UUID target){return offloads.request(task(run,"source").id(),UUID.randomUUID().toString(),transferBody(run,"source",target)).value();}
    private void drainTransfer(UUID run,UUID operation){finishPhysical(run,"source");finishPhysical(run,"sink");revokeGroup(run);offloads.advance(operation);}
    private RuntimeLifecycleService.Assignment claimOn(UUID run,String name,UUID node){var a=attempt(run,name);var p=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),node,"transfer-"+node);
        lifecycle.submitted(a.id(),p.jobUid());return lifecycle.claim(a.id(),a.epoch(),p);}
    private List<UUID> placementNodes(){var ids=List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());var now=Instant.now();
        nodes.recordSnapshot(ids.stream().map(id->new io.edgeai.domain.node.ExecutionNode(id,"transfer-"+id,"amd64","linux","READY","4","4Gi","{}",now)).toList(),now);return ids;}
    private Map<String,Object> placements(UUID source,UUID sink){return Map.of("source",Map.of("mode","NODE","nodeId",source.toString()),"sink",Map.of("mode","NODE","nodeId",sink.toString()));}

    private record VD(VirtualDevice device,VDRuntime runtime,UUID session,VDGateway.PodIdentity pod){}
    private VD readyVD(UUID service,int capacity){
        UUID profile=profile(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",service.toString(),
            "sources",Map.of(),"state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",capacity,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        var vd=virtualDevices.create(encode(Map.of("key","stream-vd-"+UUID.randomUUID(),"displayName","Stream VD fixture",
            "profileVersionId",profile.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
        var op=vdLifecycle.provision(vd.id(),0,"provision",new io.edgeai.app.config.RuntimeSettings("public-stream-test","edgeai-runner",URI.create("http://fixture.invalid"),120),false);
        var r=vdLifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());
        var f=new VD(vd,r,UUID.randomUUID(),new VDGateway.PodIdentity(r.podUid(),UUID.randomUUID(),"fixture-node",true));
        poll(f,0,List.of(),List.of());return f;
    }
    private Map<String,Object> poll(VD vd,long sequence,List<TaskAttempt> active,List<TaskAttempt> completed){
        var body=Map.of("vdId",vd.device().id().toString(),"runtimeId",vd.runtime().id().toString(),"generation",vd.runtime().generation(),
            "podUid",vd.pod().podUid().toString(),"sessionId",vd.session().toString(),"sequence",sequence,"state","RUNNING",
            "active",active.stream().map(a->Map.of("attemptId",a.id().toString(),"epoch",a.epoch())).toList(),
            "completed",completed.stream().map(a->Map.of("attemptId",a.id().toString(),"epoch",a.epoch(),"exitCode",0)).toList());
        return vdPoll.poll(new io.edgeai.app.config.VDPrincipal(vd.runtime().id(),vd.device().id(),vd.runtime().generation(),vd.pod()),encode(body).getBytes(StandardCharsets.UTF_8));
    }
    private void claimVD(UUID run,String name,VD vd){
        var a=attempt(run,name);lifecycle.claimVD(a.id(),a.epoch(),new VDTaskProducer(vd.runtime().id(),vd.runtime().generation(),vd.session(),
            vd.pod().podUid(),vd.pod().nodeUid(),vd.pod().nodeName()));
    }
    private UUID publicRun(Map<String,Object> body)throws Exception{
        return UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
    }
    private Map<String,Object> vdTarget(VD vd){return Map.of("mode","VD","vdId",vd.device().id().toString());}
    private Map<String,VD> readyVDs(Definition d){
        var result=new HashMap<String,VD>();workflowStore.definitions(d.version()).forEach(t->result.put(t.key(),readyVD(t.serviceProfileVersionId(),1)));return result;
    }
    private void whilePeerLocked(VD peer,Callable<?> action)throws Exception{
        try(var pool=Executors.newSingleThreadExecutor()){
            new TransactionTemplate(transactions).execute(tx->{
                vds.find(peer.device().id(),true).orElseThrow();var future=pool.submit(action);
                try{future.get(8,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError("Stream operation acquired another VD's mutex",e);}return null;
            });
        }
    }
    @Test void differentVdServicesAuthorizeTheirOwnRoutesAndGrantTogetherWithoutPeerMutexes()throws Exception{
        var d=definition(false,false);var vd=readyVDs(d);var source=vd.get("source");var sink=vd.get("sink");
        var body=request(d);body.put("execution",vdTarget(source));body.put("taskExecutions",Map.of("sink",vdTarget(sink)));
        UUID run=publicRun(body);
        for(String name:List.of("source","sink")){
            assertThat(task(run,name).initialVdId()).isEqualTo(vd.get(name).device().id());poll(vd.get(name),1,List.of(),List.of());claimVD(run,name,vd.get(name));
        }
        streams.prepare(run);var saved=checkpoint(run,true,false);var a=principal(run,"source");var b=principal(run,"sink");
        assertThat(a.podUid()).isNotEqualTo(b.podUid());
        whilePeerLocked(sink,()->streamExecution.complete(a,identity(a,"checkpointId",saved.get("source").id().toString())));
        finishDevices(run);
        // Run's default VD is source; sink authorization and joint completion must never lock source.
        whilePeerLocked(source,()->{
            assertThat(routeLifecycle.authorizeTask(task(run,"sink").id(),saved.get("sink").request().generationIds(),
                new StreamBrokerGateway.Principal("TASK",new RouteGeneration.Actor(b.attemptId(),b.epoch())))).hasSize(1);
            return streamExecution.complete(b,identity(b,"checkpointId",saved.get("sink").id().toString()));
        });
        assertThat(saved.values()).allMatch(c->completions.granted(c.attemptId()).isPresent());
        parallel(i->{var p=i%2==0?a:b;return streamExecution.complete(p,identity(p,"checkpointId",saved.get(i%2==0?"source":"sink").id().toString()));});
        for(String name:List.of("source","sink")){
            commitFinalizer(run,name);var result=runtimes.result(task(run,name).id()).orElseThrow();
            assertThat(result.vdRuntimeId()).isEqualTo(vd.get(name).runtime().id());
            assertThat(result.producerPodUid()).isEqualTo(vd.get(name).pod().podUid());
            poll(vd.get(name),2,List.of(),List.of(attempt(run,name)));
            assertThat(allocations.open(vd.get(name).runtime().id())).isEmpty();
        }
        assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void sharedVdRejectsInsufficientComponentCapacityAndSeparatesAttemptTokensWithinOnePod()throws Exception{
        var device=device();var spec=service(List.of("input"),false,false);
        var d=new Definition(version(Map.of("source",spec,"sink",spec),List.of()),
            List.of(input(device,"source","input"),input(device,"sink","input")),List.of(device));
        var small=readyVD(spec,1);var body=request(d);body.put("execution",vdTarget(small));
        String key=UUID.randomUUID().toString();assertThat(create(key,body,409)).contains("VD_STREAM_CAPACITY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.workflow_run WHERE idempotency_key=?",Integer.class,UUID.fromString(key))).isZero();
        var vd=readyVD(spec,2);body.put("execution",vdTarget(vd));UUID run=publicRun(body);
        assertThat((List<?>)poll(vd,1,List.of(),List.of()).get("assignments")).hasSize(2);
        claimVD(run,"source",vd);claimVD(run,"sink",vd);streams.prepare(run);
        var a=principal(run,"source");var b=principal(run,"sink");
        assertThat(a.attemptId()).isNotEqualTo(b.attemptId());assertThat(a.podUid()).isEqualTo(b.podUid());
        org.mockito.Mockito.when(vdGateway.authenticatePod(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("stream-pod-fixture"))).thenReturn(vd.pod());
        String token=runnerTokens.issue(runtimes.byAttempt(a.attemptId()).orElseThrow());
        mvc.perform(post("/internal/v1/attempts/"+b.attemptId()+"/streams/execution").header("Authorization","Bearer "+token)
            .header("X-EdgeAI-Pod-Token","stream-pod-fixture").contentType("application/json").content(identity(b))).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/attempts/"+a.attemptId()+"/streams/execution").header("Authorization","Bearer "+token)
            .header("X-EdgeAI-Pod-Token","stream-pod-fixture").contentType("application/json").content(identity(a))).andExpect(status().isOk());
        seal(run);commitFinalizer(run,"source");commitFinalizer(run,"sink");
        poll(vd,2,List.of(),List.of(attempt(run,"source"),attempt(run,"sink")));
        assertThat(allocations.open(vd.runtime().id())).isEmpty();assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void mixedVdAndNodeGroupRetryWaitsForChildExitAndBrokerRevocationAndKeepsTargets()throws Exception{
        var d=definition(false,false);var sourceSpec=workflowStore.definitions(d.version()).stream().filter(t->t.key().equals("source")).findFirst().orElseThrow();
        var vd=readyVD(sourceSpec.serviceProfileVersionId(),1);var body=request(d);body.put("taskExecutions",Map.of("source",vdTarget(vd)));
        body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        UUID run=publicRun(body);poll(vd,1,List.of(),List.of());claimVD(run,"source",vd);claim(run,"sink");streams.prepare(run);var saved=checkpoint(run,false);
        var old=attempt(run,"source");var oldPrincipal=principal(run,"source");
        lifecycle.observeFailure(attempt(run,"sink").id(),"RUNTIME_LOST");
        assertThat(task(run,"source").state()).isEqualTo("RETRY_WAIT");assertThat(task(run,"sink").state()).isEqualTo("RETRY_WAIT");
        due(run);finishPhysical(run,"sink");assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();
        assertThat(((List<?>)poll(vd,2,List.of(old),List.of()).get("cancelAttempts")).stream().map(Object::toString).toList()).contains(old.id().toString());
        assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();poll(vd,3,List.of(),List.of(old));
        assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();revokeGroup(run);
        assertThat(parallel(i->lifecycle.retryTask(task(run,i%2==0?"source":"sink").id()))).containsOnlyOnce(true);
        assertThat(attempt(run,"source").vdId()).isEqualTo(vd.device().id());assertThat(attempt(run,"source").epoch()).isEqualTo(2);
        assertThat(attempt(run,"sink").mode()).isEqualTo("AUTO");assertThat(attempt(run,"sink").vdId()).isNull();
        poll(vd,4,List.of(),List.of());claimVD(run,"source",vd);claim(run,"sink");streams.prepare(run);
        assertThatThrownBy(()->streamExecution.execution(oldPrincipal,identity(oldPrincipal))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==2);
        for(var route:routes.forRun(run,20,0)){var g=routes.open(route.id()).orElseThrow();
            routeLifecycle.activate(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));}
        for(String name:List.of("source","sink")){
            var p=principal(run,name);assertThat(((Map<?,?>)streamExecution.execution(p,identity(p))).get("recovery")).isEqualTo("HANDOVER");
            assertThat(checkpoints.latest(task(run,name).id()).orElseThrow().id()).isEqualTo(saved.get(name).id());
        }
        // Object/state transfer is verified by the S3/SDK suite; this fixture proves its required handover boundary.
        runs.cancelRun(run,"{}");poll(vd,5,List.of(),List.of(attempt(run,"source")));finishPhysical(run,"sink");
        routes.forRun(run,20,0).forEach(r->routes.open(r.id()).ifPresent(g->routeLifecycle.reconcile(g.id())));revokeGroup(run);
        assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("CANCELLED");
    }
    @Test void sharedVdCancellationFencesBothTasksUntilAllChildExitsAreAcknowledged()throws Exception{
        var device=device();var spec=service(List.of("input"),false,false);var vd=readyVD(spec,2);
        var d=new Definition(version(Map.of("source",spec,"sink",spec),List.of()),List.of(input(device,"source","input"),input(device,"sink","input")),List.of(device));
        var body=request(d);body.put("execution",vdTarget(vd));UUID run=publicRun(body);
        poll(vd,1,List.of(),List.of());claimVD(run,"source",vd);claimVD(run,"sink",vd);streams.prepare(run);
        var a=attempt(run,"source");var b=attempt(run,"sink");var p=principal(run,"source");
        runs.cancelRun(run,"{}");assertThatThrownBy(()->streamExecution.execution(p,identity(p))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        assertThat(((List<?>)poll(vd,2,List.of(a,b),List.of()).get("cancelAttempts")).stream().map(Object::toString).toList()).containsExactlyInAnyOrder(a.id().toString(),b.id().toString());
        poll(vd,3,List.of(b),List.of(a));assertThat(allocations.open(vd.runtime().id())).hasSize(1);
        assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("CANCELLING");
        poll(vd,4,List.of(),List.of(b));
        // Production authority worker performs this reconciliation; broker receipt remains an explicit fixture.
        routes.forRun(run,20,0).forEach(r->routes.open(r.id()).ifPresent(g->routeLifecycle.reconcile(g.id())));revokeGroup(run);
        assertThat(allocations.open(vd.runtime().id())).isEmpty();assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("CANCELLED");
        assertThat(runtimes.result(task(run,"source").id())).isEmpty();assertThat(runtimes.result(task(run,"sink").id())).isEmpty();
    }
    @Test void drainingVdChildCancellationSchedulesGroupRecoveryButUserCancellationDoesNot()throws Exception{
        for(boolean userCancelled:List.of(false,true)){
            var device=device();var spec=service(List.of("input"),false,false);var vd=readyVD(spec,2);
            var d=new Definition(version(Map.of("source",spec,"sink",spec),List.of()),List.of(input(device,"source","input"),input(device,"sink","input")),List.of(device));
            var body=request(d);body.put("execution",vdTarget(vd));
            body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
            UUID run=publicRun(body);poll(vd,1,List.of(),List.of());claimVD(run,"source",vd);claimVD(run,"sink",vd);streams.prepare(run);
            var sink=attempt(run,"sink");vdLifecycle.drain(vd.device().id(),0,"drain");
            if(userCancelled){
                runs.cancelRun(run,"{}");
                assertThatThrownBy(()->lifecycle.fail(sink.id(),sink.epoch(),vd.pod().podUid(),"CANCELLED"))
                    .isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
                assertThat(executions.retry(task(run,"sink").id())).isEmpty();
            }else{
                lifecycle.fail(sink.id(),sink.epoch(),vd.pod().podUid(),"CANCELLED");
                assertThat(runtimes.byAttempt(sink.id()).orElseThrow().failureReason()).isEqualTo("RUNTIME_LOST");
                for(String name:List.of("source","sink")){
                    assertThat(task(run,name).state()).isEqualTo("RETRY_WAIT");assertThat(executions.retry(task(run,name).id())).isPresent();
                }
                assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();
                assertThat(allocations.open(vd.runtime().id())).hasSize(2);
                lifecycle.fail(sink.id(),sink.epoch(),vd.pod().podUid(),"CANCELLED"); // Exact late report is idempotent.
                runs.cancelRun(run,"{}");
            }
            poll(vd,2,List.of(),List.of(attempt(run,"source"),attempt(run,"sink")));
            routes.forRun(run,20,0).forEach(r->routes.open(r.id()).ifPresent(g->routeLifecycle.reconcile(g.id())));revokeGroup(run);
        }
    }
    @Test void vdFinalizerRetryPreservesSealedCheckpointAndDoesNotRestartCompletedPeer()throws Exception{
        var device=device();var spec=service(List.of("input"),false,false);var vd=readyVD(spec,2);
        var d=new Definition(version(Map.of("source",spec,"sink",spec),List.of()),List.of(input(device,"source","input"),input(device,"sink","input")),List.of(device));
        var body=request(d);body.put("execution",vdTarget(vd));
        body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        UUID run=publicRun(body);poll(vd,1,List.of(),List.of());claimVD(run,"source",vd);claimVD(run,"sink",vd);streams.prepare(run);
        var sealed=seal(run);commitFinalizer(run,"source");var old=attempt(run,"sink");var oldPrincipal=principal(run,"sink");
        lifecycle.observeFailure(old.id(),"RUNTIME_LOST");due(run);
        assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();
        poll(vd,2,List.of(),List.of(attempt(run,"source"),old));
        assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();
        routes.forRun(run,20,0).forEach(r->routes.open(r.id()).ifPresent(g->routeLifecycle.reconcile(g.id())));revokeGroup(run);
        assertThat(lifecycle.retryTask(task(run,"sink").id())).isTrue();
        assertThat(attempt(run,"sink").vdId()).isEqualTo(vd.device().id());assertThat(executions.attempts(task(run,"source").id())).hasSize(1);
        poll(vd,3,List.of(),List.of());claimVD(run,"sink",vd);var p=principal(run,"sink");
        var state=(Map<?,?>)streamExecution.execution(p,identity(p));assertThat(state.get("state")).isEqualTo("FINALIZE");
        assertThat(state.get("checkpointId")).isEqualTo(sealed.get("sink").id().toString());
        assertThat(((Map<?,?>)state.get("checkpointActor")).get("attemptId")).isEqualTo(old.id().toString());
        assertThat(streamExecution.finalized(p,identity(p,"checkpointId",sealed.get("sink").id().toString())).id()).isEqualTo(sealed.get("sink").id());
        assertThatThrownBy(()->streamExecution.execution(oldPrincipal,identity(oldPrincipal))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        commitFinalizer(run,"sink");poll(vd,4,List.of(),List.of(attempt(run,"sink")));
        assertThat(executions.run(run,false).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }

    @Test void distinctInitialPlacementsSurviveWholeGroupRetry()throws Exception{
        var targets=placementNodes();UUID source=targets.get(0),sink=targets.get(1);
        var body=request(definition(false,false));body.put("taskExecutions",placements(source,sink));
        body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
        assertThat(executions.run(run,false).orElseThrow().mode()).isEqualTo("AUTO");
        claimOn(run,"source",source);claimOn(run,"sink",sink);streams.prepare(run);checkpoint(run,false);
        lifecycle.observeFailure(attempt(run,"sink").id(),"RUNTIME_LOST");
        finishPhysical(run,"source");finishPhysical(run,"sink");revokeGroup(run);due(run);
        assertThat(lifecycle.retryTask(task(run,"sink").id())).isTrue();
        for(String name:List.of("source","sink")){assertThat(attempt(run,name).cause()).isEqualTo("RETRY");assertThat(attempt(run,name).number()).isEqualTo(2);}
        assertThat(attempt(run,"source").nodeId()).isEqualTo(source);assertThat(attempt(run,"sink").nodeId()).isEqualTo(sink);
        assertThat(task(run,"source").initialNodeId()).isEqualTo(source);assertThat(task(run,"sink").initialNodeId()).isEqualTo(sink);
        claimOn(run,"source",source);claimOn(run,"sink",sink);streams.prepare(run);
        assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==2);
    }

    @Test void publicStreamOffloadPinsWholeGroupAndWaitsForEveryStopRevocationAndClaim()throws Exception{
        var run=create(definition(false,true));claim(run,"source");claim(run,"sink");streams.prepare(run);
        var saved=checkpoint(run,false);var oldSource=principal(run,"source");var oldSink=attempt(run,"sink");var target=targetNode();
        var body=transferBody(run,"source",target);String key=UUID.randomUUID().toString();
        var response=(Map<?,?>)json.decode(perform(post("/api/v1/tasks/"+task(run,"source").id()+"/offload")
            .header("Idempotency-Key",key).contentType("application/json").content(body),202));
        UUID id=UUID.fromString((String)response.get("id"));assertThat((List<?>)response.get("members")).hasSize(2);
        assertThat(offloads.find(id).members()).isEqualTo(offloads.request(task(run,"source").id(),key,body).value().members());
        assertThat(parallel(i->offloads.request(task(run,"source").id(),key,body).value().id())).containsOnly(id);
        assertThat(offloadStore.forTask(task(run,"sink").id())).extracting(OffloadOperation::id).containsExactly(id);
        assertThat(offloads.find(id).members()).extracting(OffloadMember::checkpointId).containsExactlyInAnyOrderElementsOf(saved.values().stream().map(StreamCheckpoint::id).toList());
        assertThatThrownBy(()->lifecycle.authorize(oldSource.attemptId(),oldSource.epoch(),oldSource.podUid())).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        assertThatThrownBy(()->offloads.request(task(run,"sink").id(),UUID.randomUUID().toString(),transferBody(run,"sink",target))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        offloads.advance(id);finishPhysical(run,"source");offloads.advance(id);
        assertThat(executions.attempts(task(run,"source").id())).hasSize(1);
        finishPhysical(run,"sink");offloads.advance(id);assertThat(offloads.find(id).state()).isEqualTo("DRAINING");
        revokeGroup(run);parallel(i->{offloads.advance(id);return true;});
        var operation=offloads.find(id);assertThat(operation.state()).isEqualTo("STARTING");
        assertThat(operation.members()).allMatch(m->m.targetAttemptId()!=null);
        for(String name:List.of("source","sink")){
            assertThat(executions.attempts(task(run,name).id())).hasSize(2);assertThat(attempt(run,name).cause()).isEqualTo("OFFLOAD");
            assertThat(executions.retry(task(run,name).id())).isEmpty();
        }
        assertThat(attempt(run,"source").nodeId()).isEqualTo(target);
        assertThat(attempt(run,"sink").mode()).isEqualTo(oldSink.mode());assertThat(attempt(run,"sink").nodeId()).isEqualTo(oldSink.nodeId());
        claimOn(run,"source",target);assertThat(offloads.find(id).state()).isEqualTo("STARTING");
        streams.prepare(run);assertThat(routes.forRun(run,20,0)).allMatch(r->routes.open(r.id()).isEmpty());
        claim(run,"sink");assertThat(offloads.find(id).state()).isEqualTo("SUCCEEDED");
        assertThat(streams.prepare(run)).isEmpty();assertThat(routes.forRun(run,20,0)).allMatch(r->routes.open(r.id()).orElseThrow().generation()==2);
        assertThat(task(run,"child").state()).isEqualTo("WAITING");assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
        for(var cp:saved.values())assertThat(checkpoints.latest(cp.taskId()).orElseThrow().id()).isEqualTo(cp.id());
    }
    @Test void peerCancellationDuringStreamTransferWaitsForTheWholeGroupAndNeverRestartsIt()throws Exception{
        for(boolean started:List.of(false,true)){
            var run=create(definition(false,true));claim(run,"source");claim(run,"sink");streams.prepare(run);checkpoint(run,false);
            var o=transfer(run,targetNode());if(started)drainTransfer(run,o.id());
            runs.cancelTask(task(run,"sink").id(),"{}");offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLING");
            finishPhysical(run,"source");offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLING");
            finishPhysical(run,"sink");revokeGroup(run);offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("CANCELLED");
            assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
            assertThat(executions.attempts(task(run,"source").id())).hasSize(started?2:1);assertThat(runtimes.result(task(run,"source").id())).isEmpty();
        }
    }
    @Test void streamTransferTimeoutFencesClaimedPeerAndSkipsDependentsWithoutRestartingThem()throws Exception{
        for(boolean started:List.of(false,true)){
            var run=create(definition(false,true));claim(run,"source");claim(run,"sink");streams.prepare(run);checkpoint(run,false);
            var target=targetNode();var o=transfer(run,target);
            if(started){drainTransfer(run,o.id());claimOn(run,"source",target);}
            jdbc.update("UPDATE edgeai.task_offload SET "+(started?"start_deadline":"drain_deadline")+"=now()-interval '1 second' WHERE id=?",o.id());
            if(started)assertThatThrownBy(()->claim(run,"sink")).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            offloads.advance(o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("FAILED");
            assertThat(offloads.find(o.id()).failureReason()).isEqualTo(started?"TARGET_START_TIMEOUT":"SOURCE_DRAIN_TIMEOUT");
            assertThat(task(run,"source").state()).isEqualTo("FAILED");assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
            for(String name:List.of("source","sink")){assertThat(runtimes.byAttempt(attempt(run,name).id()).orElseThrow().desiredState()).isEqualTo("STOPPED");assertThat(executions.retry(task(run,name).id())).isEmpty();}
            assertThat(task(run,"child").state()).isEqualTo("SKIPPED");
        }
    }
    @Test void failureBeforeAllTransferClaimsDoesNotTurnIntoASeparateGroupRetry()throws Exception{
        var run=recoveryRun(definition(false,true),3);claim(run,"source");claim(run,"sink");streams.prepare(run);checkpoint(run,false);
        var o=transfer(run,targetNode());drainTransfer(run,o.id());claim(run,"sink");
        lifecycle.observeFailure(attempt(run,"sink").id(),"RUNTIME_LOST");
        assertThat(offloads.find(o.id()).state()).isEqualTo("FAILED");assertThat(offloads.find(o.id()).failureReason()).isEqualTo("TARGET_FAILED");
        for(String name:List.of("source","sink")){assertThat(executions.retry(task(run,name).id())).isEmpty();assertThat(runtimes.byAttempt(attempt(run,name).id()).orElseThrow().desiredState()).isEqualTo("STOPPED");}
        assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
    }
    @Test void missingCheckpointAndFinalizationRejectTransferBeforeAnySourceIsStopped()throws Exception{
        for(boolean sealed:List.of(false,true)){
            var run=create(definition(false,false));claim(run,"source");claim(run,"sink");streams.prepare(run);if(sealed)seal(run);
            var target=targetNode();perform(post("/api/v1/tasks/"+task(run,"source").id()+"/offload").header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType("application/json").content(transferBody(run,"source",target)),409);
            assertThat(offloadStore.forTask(task(run,"source").id())).isEmpty();
            assertThat(runtimes.byAttempt(attempt(run,"source").id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
        }
    }
    @Test void databaseProtectsStreamTransferMembershipCheckpointAndSuccessorLineage()throws Exception{
        var run=create(definition(false,false));claim(run,"source");claim(run,"sink");streams.prepare(run);checkpoint(run,false);
        var o=transfer(run,targetNode());var m=o.members().getFirst();
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.task_offload_member SET checkpoint_id=? WHERE operation_id=? AND task_id=?",o.members().getLast().checkpointId(),o.id(),m.taskId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.task_offload_member SET target_attempt_id=source_attempt_id WHERE operation_id=?",o.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.task_offload_member WHERE operation_id=?",o.id())).isInstanceOf(DataIntegrityViolationException.class);
        drainTransfer(run,o.id());assertThat(offloads.find(o.id()).state()).isEqualTo("STARTING");
        assertThat(offloads.find(o.id()).members()).allMatch(v->v.targetAttemptId()!=null);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.task_offload_member SET target_attempt_id=NULL WHERE operation_id=?",o.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void nodePoliciesSurviveGroupTransferAndOffloadDoesNotConsumeRetryBudget()throws Exception{
        var targets=placementNodes();UUID source=targets.get(0),target=targets.get(1),initialSource=targets.get(2);
        var d=definition(false,false);var body=request(d);body.put("execution",Map.of("mode","NODE","nodeId",source.toString()));
        body.put("taskExecutions",Map.of("source",Map.of("mode","NODE","nodeId",initialSource.toString())));
        body.put("retry",Map.of("maxAttempts",2,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        var run=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
        claimOn(run,"source",initialSource);claimOn(run,"sink",source);streams.prepare(run);checkpoint(run,false);
        var o=transfer(run,target);drainTransfer(run,o.id());
        assertThat(attempt(run,"source").nodeId()).isEqualTo(target);assertThat(attempt(run,"sink").nodeId()).isEqualTo(source);
        claimOn(run,"source",target);claimOn(run,"sink",source);streams.prepare(run);
        assertThat(task(run,"source").initialNodeId()).isEqualTo(initialSource);
        lifecycle.observeFailure(attempt(run,"sink").id(),"RUNTIME_LOST");
        for(String name:List.of("source","sink"))assertThat(task(run,name).state()).isEqualTo("RETRY_WAIT");
        finishPhysical(run,"source");finishPhysical(run,"sink");revokeGroup(run);due(run);
        assertThat(lifecycle.retryTask(task(run,"source").id())).isTrue();
        for(String name:List.of("source","sink")){
            assertThat(attempt(run,name).number()).isEqualTo(3);assertThat(attempt(run,name).cause()).isEqualTo("RETRY");
        }
        assertThat(attempt(run,"source").nodeId()).isEqualTo(target);assertThat(attempt(run,"sink").nodeId()).isEqualTo(source);
        claimOn(run,"source",target);claimOn(run,"sink",source);streams.prepare(run);
        lifecycle.observeFailure(attempt(run,"sink").id(),"RUNTIME_LOST");assertThat(task(run,"sink").state()).isEqualTo("FAILED");
        assertThat(executions.retry(task(run,"sink").id())).isEmpty();assertThat(offloads.find(o.id()).state()).isEqualTo("SUCCEEDED");
    }

    @Test void publicCreationPinsSessionsDispatchesWholeGroupAndReplaysReorderedInputs()throws Exception{
        var d=definition(false,false);String key=UUID.randomUUID().toString();var body=request(d);
        var response=(Map<?,?>)json.decode(create(key,body,201));UUID id=UUID.fromString((String)response.get("id"));
        assertThat(response.get("state")).isEqualTo("RUNNING");
        assertThat(executions.tasks(id)).hasSize(2).allMatch(t->t.state().equals("RUNNING"));
        for(String name:List.of("source","sink")){assertThat(attempt(id,name).state()).isEqualTo("DISPATCHING");assertThat(runtimes.byAttempt(attempt(id,name).id())).isPresent();}
        assertThat(routes.forRun(id,20,0)).hasSize(3).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        assertThat(store.bindings(id)).hasSize(2).allMatch(b->deviceStore.activeSession(b.deviceId()).orElseThrow().id().equals(b.sessionId()));
        assertThat(completions.bindingDigest(id)).isPresent();
        var reverse=new ArrayList<>(d.inputs());Collections.reverse(reverse);body.put("streamInputs",reverse);
        assertThat(((Map<?,?>)json.decode(create(key,body,200))).get("id")).isEqualTo(id.toString());
        body.put("streamInputs",List.of(d.inputs().getFirst()));assertThat(create(key,body,409)).contains("IDEMPOTENCY_CONFLICT");
    }
    @Test void grantedFinalizerRetriesAloneAfterPhysicalAndBrokerBarriersAndRetainsOriginalGrant()throws Exception{
        var targets=placementNodes();UUID source=targets.get(0),sink=targets.get(1);
        var body=request(definition(false,true));body.put("taskExecutions",placements(source,sink));
        body.put("retry",Map.of("maxAttempts",3,"backoffSeconds",1,"maxElapsedSeconds",300,"retryOn",List.of("RUNTIME_LOST")));
        UUID run=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));
        claimOn(run,"source",source);claimOn(run,"sink",sink);streams.prepare(run);var sealed=seal(run);
        var original=principal(run,"sink");var grant=completions.granted(original.attemptId()).orElseThrow();commitFinalizer(run,"source");
        // The broker worker is mocked in this PG test: model its closure of the completed source's Device routes.
        for(var route:routes.forRun(run,20,0))if(route.deviceSource()){
            var g=routes.open(route.id()).orElseThrow();routeLifecycle.fence(g.id(),"COMPLETED");
            routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));
        }
        for(int round=2;round<=3;round++){
            var before=principal(run,"sink");lifecycle.observeFailure(before.attemptId(),"RUNTIME_LOST");due(run);
            assertThat(task(run,"source").state()).isEqualTo("SUCCEEDED");assertThat(executions.attempts(task(run,"source").id())).hasSize(1);
            assertThat(task(run,"child").state()).isEqualTo("WAITING");assertThat(task(run,"independent").state()).isEqualTo("RUNNING");
            assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();finishPhysical(run,"sink");
            if(round==2){assertThat(lifecycle.retryTask(task(run,"sink").id())).isFalse();revokeGroup(run);}
            assertThat(parallel(i->lifecycle.retryTask(task(run,"sink").id()))).containsOnlyOnce(true);
            assertThat(attempt(run,"sink").nodeId()).isEqualTo(sink);assertThat(task(run,"sink").initialNodeId()).isEqualTo(sink);
            claimOn(run,"sink",sink);assertThat(streams.prepare(run)).isEmpty();
            var p=principal(run,"sink");assertThat(p.epoch()).isEqualTo(round);
            var reply=(Map<?,?>)streamExecution.execution(p,identity(p));assertThat(reply.get("state")).isEqualTo("FINALIZE");
            assertThat(reply.get("checkpointActor")).isEqualTo(Map.of("attemptId",original.attemptId().toString(),"epoch",original.epoch()));
            assertThat(streamExecution.finalized(p,identity(p,"checkpointId",sealed.get("sink").id().toString())).id()).isEqualTo(sealed.get("sink").id());
            assertThat(completions.granted(p.attemptId()).orElseThrow()).isEqualTo(grant);assertThat(completions.task(p.attemptId())).isEmpty();
            assertThatThrownBy(()->streamExecution.finalized(p,identity(p,"checkpointId",sealed.get("source").id().toString()))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThatThrownBy(()->streamExecution.execution(before,identity(before))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1 && routes.open(r.id()).isEmpty());
            assertThatThrownBy(()->jdbc.update("UPDATE edgeai.stream_finalization_recovery SET granted_attempt_id=? WHERE attempt_id=?",sealed.get("source").attemptId(),p.attemptId())).isInstanceOf(DataIntegrityViolationException.class);
        }
        var last=principal(run,"sink");commitFinalizer(run,"sink");
        assertThat(runtimes.result(task(run,"sink").id()).orElseThrow().attemptId()).isEqualTo(last.attemptId());
        assertThat(task(run,"child").state()).isEqualTo("RUNNING");assertThat(checkpoints.latest(task(run,"sink").id()).orElseThrow().id()).isEqualTo(sealed.get("sink").id());
    }
    @Test void finalizerRetryCancellationAndBudgetDoNotTurnSealedStateIntoLateSuccess()throws Exception{
        for(boolean cancel:List.of(false,true)){
            UUID run=recoveryRun(definition(false,false),2);claim(run,"source");claim(run,"sink");streams.prepare(run);var sealed=seal(run);
            var old=principal(run,"source");lifecycle.observeFailure(old.attemptId(),"RUNTIME_LOST");finishPhysical(run,"source");
            // Only the failed finalizer's routes are fenced; here source touches the entire component.
            revokeGroup(run);due(run);assertThat(lifecycle.retryTask(task(run,"source").id())).isTrue();claim(run,"source");
            var current=principal(run,"source");
            if(cancel)runs.cancelRun(run,"{}");else lifecycle.observeFailure(current.attemptId(),"RUNTIME_LOST");
            assertThat(executions.retry(task(run,"source").id())).isEmpty();assertThat(lifecycle.retryTask(task(run,"source").id())).isFalse();
            assertThatThrownBy(()->streamExecution.finalized(current,identity(current,"checkpointId",sealed.get("source").id().toString()))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
            assertThat(runtimes.result(task(run,"source").id())).isEmpty();
        }
    }
    @Test void databaseRejectsForgedFinalizerLineageAndUnfinishedPhysicalOrBrokerRevocation()throws Exception{
        UUID run=recoveryRun(definition(false,false),2);claim(run,"source");claim(run,"sink");streams.prepare(run);seal(run);
        var old=principal(run,"source");var task=task(run,"source");lifecycle.observeFailure(old.attemptId(),"RUNTIME_LOST");
        org.assertj.core.api.ThrowableAssert.ThrowingCallable inherit=()->new TransactionTemplate(transactions).execute(tx->{
            executions.run(run,true);var next=executions.startRetry(task.id(),Instant.now());
            completions.inheritFinalization(next.id(),old.attemptId(),Instant.now());return null;
        });
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // old producer still running
        lifecycle.confirmStopped(old.attemptId());
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // CREATE can still be in flight
        jdbc.update("UPDATE edgeai.runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",runtimes.byAttempt(old.attemptId()).orElseThrow().id());
        assertThatThrownBy(inherit).isInstanceOf(DataIntegrityViolationException.class); // old ACLs not yet revoked
        revokeGroup(run);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->{
            executions.run(run,true);var next=executions.startRetry(task.id(),Instant.now());
            jdbc.update("INSERT INTO edgeai.stream_finalization_recovery(attempt_id,predecessor_attempt_id,granted_attempt_id,created_at) VALUES (?,?,?,now())",
                next.id(),old.attemptId(),attempt(run,"sink").id());return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(executions.attempts(task.id())).hasSize(1);due(run);assertThat(lifecycle.retryTask(task.id())).isTrue();
    }
    @Test void concurrentCreationAndRestartedPreparationCreateOneRunAndOneGenerationPerRoute()throws Exception{
        var d=definition(false,false);String key=UUID.randomUUID().toString();String body=encode(request(d));
        var created=parallel(i->runs.create(key,body));assertThat(created.stream().filter(Creation::created)).hasSize(1);
        UUID id=created.getFirst().value().id();assertThat(created).allMatch(c->c.value().id().equals(id));
        claim(id,"source");worker();assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(id,"sink");parallel(i->streams.prepare(id));worker();
        for(var route:routes.forRun(id,20,0)){
            var history=routes.history(route.id(),20,0);assertThat(history).hasSize(1);var g=history.getFirst();
            assertThat(g.state()).isEqualTo("PREPARING");assertThat(g.consumer().id()).isEqualTo(attempt(id,route.deviceSource()?"source":"sink").id());
            if(route.deviceSource())assertThat(store.bindings(id).stream().filter(b->b.routeId().equals(route.id())).findFirst().orElseThrow().sessionId()).isEqualTo(g.producer().id());
            else assertThat(g.producer().id()).isEqualTo(attempt(id,"source").id());
        }
    }
    @Test void everyBatchPredecessorMustHaveSealedResultBeforeAnyStreamMemberStarts()throws Exception{
        var targets=placementNodes();UUID source=targets.get(0),sink=targets.get(1);
        var d=definition(true,false);var body=request(d);body.put("taskExecutions",placements(source,sink));
        UUID id=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),body,201))).get("id"));var prep=claim(id,"prep");
        assertThat(task(id,"source").initialNodeId()).isEqualTo(source);assertThat(task(id,"sink").initialNodeId()).isEqualTo(sink);
        assertThat(task(id,"source").state()).isEqualTo("WAITING");assertThat(task(id,"sink").state()).isEqualTo("WAITING");
        // Deliberately incomplete state fixture proves state alone cannot release a group.
        jdbc.update("UPDATE edgeai.task SET state='SUCCEEDED' WHERE id=?",task(id,"prep").id());streams.releaseReady(id);
        assertThat(executions.attempts(task(id,"source").id())).isEmpty();assertThat(executions.attempts(task(id,"sink").id())).isEmpty();
        jdbc.update("UPDATE edgeai.task SET state='RUNNING' WHERE id=?",task(id,"prep").id());
        var output=new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version");
        var permit=lifecycle.prepareCommit(prep.runtime().attemptId(),1,prep.runtime().producerPodUid(),new ResultManifest(List.of(output)));
        var content=output.content(task(id,"prep").id(),prep.runtime().attemptId());
        lifecycle.commitVerified(permit,List.of(new TaskResult.Output("output",new VerifiedArtifact("fixture-only",content.objectKey(),output.versionId(),content.sha256(),content.bytes(),content.mediaType()))));
        assertThat(attempt(id,"source").state()).isEqualTo("DISPATCHING");assertThat(attempt(id,"sink").state()).isEqualTo("DISPATCHING");
        assertThat(attempt(id,"source").nodeId()).isEqualTo(source);assertThat(attempt(id,"sink").nodeId()).isEqualTo(sink);
        assertThat(claimOn(id,"source",source).inputs()).isEmpty();assertThat(claimOn(id,"sink",sink).inputs()).hasSize(1);
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void sameDeviceFanoutSharesComponentAndWaitsForBothClaims()throws Exception{
        var device=device();var spec=service(List.of("input"),false,false);var d=new Definition(version(Map.of("a",spec,"b",spec),List.of()),
            List.of(input(device,"a","input"),input(device,"b","input")),List.of(device));
        UUID id=create(d);var records=streams.list(id,20,0);assertThat(records.stream().map(r->r.componentId()).distinct()).hasSize(1);
        claim(id,"a");streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(id,"b");streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void missingDuplicateUnknownOversizedAndDependencyReplacingInputsRollBackPublicCreation()throws Exception{
        var d=definition(false,false);
        for(var inputs:List.of(List.of(d.inputs().getFirst()),List.of(d.inputs().getFirst(),d.inputs().getFirst()),
                List.of(input(d.devices().getFirst(),"missing","a")),List.of(input(d.devices().getFirst(),"sink","input")))){
            var body=request(d);body.put("streamInputs",inputs);rejected(d,body,400);
        }
        for(Object budget:List.of(4097,262145,0,1.5,"4096")){
            var input=new TreeMap<>(d.inputs().getFirst());input.put("maxPayloadBytes",budget);var body=request(d);body.put("streamInputs",List.of(input,d.inputs().get(1)));rejected(d,body,400);
        }
        var extra=new TreeMap<>(d.inputs().getFirst());extra.put("sessionId",UUID.randomUUID().toString());var body=request(d);body.put("streamInputs",List.of(extra,d.inputs().get(1)));rejected(d,body,400);
    }
    @Test void unsupportedRecoveryAndInactiveOrMissingDeviceCannotCreatePartialRuns()throws Exception{
        var d=definition(false,false);var body=request(d);
        body.put("execution",Map.of("mode","VD","vdId",UUID.randomUUID().toString()));rejected(d,body,404);
        body=request(d);body.put("execution",Map.of("mode","REMOTE","providerKey","reference"));rejected(d,body,409);
        body=request(d);body.put("retry",Map.of("maxAttempts",9,"backoffSeconds",1,"maxElapsedSeconds",30,"retryOn",List.of("WORKLOAD_FAILED")));rejected(d,body,400);
        var missing=new TreeMap<>(d.inputs().getFirst());missing.put("deviceId",UUID.randomUUID().toString());body=request(d);body.put("streamInputs",List.of(missing,d.inputs().get(1)));rejected(d,body,404);
        devices.release(d.devices().getFirst().id());rejected(d,request(d),409);
    }
    @Test void batchWithinDeviceFanoutIsRejectedBeforeAnyRuntimeCommand()throws Exception{
        var device=device();var spec=service(List.of("input"),false,true);
        // A shared source joins both tasks despite the second task's required BATCH input.
        var a=service(List.of("input"),false,false);
        var d=new Definition(version(Map.of("a",a,"b",spec),List.of(edge("a","b","result","file-input","BATCH"))),
            List.of(input(device,"a","input"),input(device,"b","input")),List.of(device));
        rejected(d,request(d),400);
    }
    @Test void nodePolicyPinsEveryMemberAndRejectsClaimsOnAnotherNode()throws Exception{
        UUID node=UUID.randomUUID();String name="stream-node-"+UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,?,'amd64','linux','READY','1','1Gi','{}',now())",node,name);
        var d=definition(false,false);var request=request(d);request.put("execution",Map.of("mode","NODE","nodeId",node.toString()));
        UUID id=UUID.fromString((String)((Map<?,?>)json.decode(create(UUID.randomUUID().toString(),request,201))).get("id"));
        for(String key:List.of("source","sink")){
            var a=attempt(id,key);assertThat(a.nodeId()).isEqualTo(node);assertThat(a.mode()).isEqualTo("NODE");
            UUID job=UUID.randomUUID();lifecycle.submitted(a.id(),job);
            assertThatThrownBy(()->lifecycle.claim(a.id(),1,new RuntimePod(job,UUID.randomUUID(),UUID.randomUUID(),name)))
                .isInstanceOfSatisfying(io.edgeai.app.exception.ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("PRODUCER_FENCED"));
            lifecycle.claim(a.id(),1,new RuntimePod(job,UUID.randomUUID(),node,name));
        }
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void sessionReplacementFailsWholeGroupAndNeverRepinsOrGenerates()throws Exception{
        var d=definition(false,true);UUID id=create(d);var before=store.bindings(id);claim(id,"source");
        devices.openSession(d.devices().getFirst().id(),encode(Map.of("bootId",UUID.randomUUID().toString())));worker();
        assertThat(store.bindings(id)).isEqualTo(before);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        assertThat(List.of(task(id,"source").state(),task(id,"sink").state())).containsExactlyInAnyOrder("FAILED","CANCELLING");
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        assertThat(runtimes.byAttempt(attempt(id,"independent").id()).orElseThrow().desiredState()).isEqualTo("RUNNING");
    }
    @Test void cancelledConsumerStopsItsUpstreamPeerAndDescendantsButPreservesIndependentBranch()throws Exception{
        UUID id=create(definition(false,true));claim(id,"source");claim(id,"sink");streams.prepare(id);
        perform(post("/api/v1/tasks/"+task(id,"sink").id()+"/cancel").contentType("application/json").content("{}"),200);
        assertThat(task(id,"source").state()).isEqualTo("CANCELLING");assertThat(task(id,"child").state()).isEqualTo("SKIPPED");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(runtimes.byAttempt(attempt(id,name).id()).orElseThrow().desiredState()).isEqualTo("STOPPED");lifecycle.confirmStopped(attempt(id,name).id());}
        assertThat(task(id,"source").state()).isEqualTo("SKIPPED");assertThat(task(id,"sink").state()).isEqualTo("CANCELLED");
        streams.prepare(id);assertThat(routes.forRun(id,20,0)).allMatch(r->routes.history(r.id(),20,0).size()==1);
    }
    @Test void revokedGenerationFailsGroupWithoutSilentlyCreatingANewGeneration()throws Exception{
        UUID id=create(definition(false,true));claim(id,"source");claim(id,"sink");streams.prepare(id);
        var route=routes.forRun(id,20,0).getFirst();var g=routes.history(route.id(),1,0).getFirst();
        routeLifecycle.fence(g.id(),"LEASE_EXPIRED");routeLifecycle.revoked(new RouteGeneration.BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest()));worker();worker();
        assertThat(routes.history(route.id(),20,0)).hasSize(1);assertThat(routes.open(route.id())).isEmpty();
        assertThat(List.of(task(id,"source").state(),task(id,"sink").state())).containsExactlyInAnyOrder("FAILED","CANCELLING");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
    }
    @Test void unfinishedRecoveryFencesTheWholeGroupAndWaitsForAllPhysicalStopsAndBrokerRevocations()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        var pins=store.bindings(id);var old=new HashMap<UUID,RouteGeneration>();
        routes.forRun(id,20,0).forEach(r->old.put(r.id(),routes.open(r.id()).orElseThrow()));
        lifecycle.observeFailure(attempt(id,"sink").id(),"RUNTIME_LOST");
        assertThat(task(id,"source").state()).isEqualTo("RETRY_WAIT");assertThat(task(id,"sink").state()).isEqualTo("RETRY_WAIT");
        assertThat(task(id,"independent").state()).isEqualTo("RUNNING");assertThat(task(id,"child").state()).isEqualTo("WAITING");
        var a=executions.retry(task(id,"source").id()).orElseThrow();var b=executions.retry(task(id,"sink").id()).orElseThrow();
        assertThat(a.availableAt()).isEqualTo(b.availableAt());assertThat(a.deadline()).isEqualTo(b.deadline());
        assertThat(routes.forRun(id,20,0)).allMatch(r->routes.open(r.id()).orElseThrow().state().equals("FENCED"));
        assertThat(runtimes.byAttempt(attempt(id,"source").id()).orElseThrow().failureReason()).isEqualTo("STREAM_GROUP_RESTART");
        assertThat(lifecycle.retryTask(task(id,"source").id())).isFalse();due(id);
        finishPhysical(id,"source");assertThat(lifecycle.retryTask(task(id,"source").id())).isFalse();
        finishPhysical(id,"sink");assertThat(lifecycle.retryTask(task(id,"sink").id())).isFalse();
        revokeGroup(id);
        var results=parallel(i->lifecycle.retryTask(task(id,i%2==0?"source":"sink").id()));assertThat(results).containsOnlyOnce(true);
        for(String name:List.of("source","sink")){
            assertThat(executions.attempts(task(id,name).id())).hasSize(2);assertThat(attempt(id,name).epoch()).isEqualTo(2);
            assertThat(attempt(id,name).cause()).isEqualTo("RETRY");assertThat(executions.retry(task(id,name).id())).isEmpty();claim(id,name);
        }
        assertThat(streams.prepare(id)).isEmpty();assertThat(store.bindings(id)).isEqualTo(pins);
        for(var route:routes.forRun(id,20,0)){
            var g=routes.open(route.id()).orElseThrow();assertThat(g.generation()).isEqualTo(2);assertThat(g.consumer().epoch()).isEqualTo(2);
            assertThat(g.consumer().id()).isNotEqualTo(old.get(route.id()).consumer().id());
            if(route.deviceSource())assertThat(g.producer()).isEqualTo(old.get(route.id()).producer());else assertThat(g.producer().epoch()).isEqualTo(2);
        }
        assertThat(executions.attempts(task(id,"independent").id())).hasSize(1);assertThat(executions.attempts(task(id,"child").id())).isEmpty();
    }
    @Test void simultaneousPeerFailuresReserveExactlyOneGroupRetryAndCancellationPreventsReassignment()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        parallel(i->{lifecycle.observeFailure(attempt(id,i%2==0?"source":"sink").id(),"RUNTIME_LOST");return true;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.task_retry r JOIN edgeai.task t ON t.id=r.task_id WHERE t.run_id=?",Integer.class,id)).isEqualTo(2);
        perform(post("/api/v1/tasks/"+task(id,"sink").id()+"/cancel").contentType("application/json").content("{}"),200);
        finishPhysical(id,"source");finishPhysical(id,"sink");revokeGroup(id);
        for(String name:List.of("source","sink")){
            assertThat(lifecycle.retryTask(task(id,name).id())).isFalse();assertThat(executions.attempts(task(id,name).id())).hasSize(1);
            assertThat(executions.retry(task(id,name).id())).isEmpty();
        }
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
    }
    @Test void groupRetryExhaustionAndExpiredWindowNeverRestartOnlyOnePeer()throws Exception{
        UUID id=recoveryRun(definition(false,true),2);claim(id,"source");claim(id,"sink");streams.prepare(id);
        lifecycle.observeFailure(attempt(id,"source").id(),"RUNTIME_LOST");finishPhysical(id,"source");finishPhysical(id,"sink");revokeGroup(id);due(id);
        assertThat(lifecycle.retryTask(task(id,"sink").id())).isTrue();claim(id,"source");claim(id,"sink");streams.prepare(id);
        lifecycle.observeFailure(attempt(id,"sink").id(),"RUNTIME_LOST");
        assertThat(task(id,"sink").state()).isEqualTo("FAILED");assertThat(task(id,"source").state()).isEqualTo("CANCELLING");
        assertThat(task(id,"child").state()).isEqualTo("SKIPPED");assertThat(task(id,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(executions.retry(task(id,name).id())).isEmpty();assertThat(executions.attempts(task(id,name).id())).hasSize(2);}
        UUID expired=recoveryRun(definition(false,true),3);claim(expired,"source");claim(expired,"sink");streams.prepare(expired);
        lifecycle.observeFailure(attempt(expired,"source").id(),"RUNTIME_LOST");
        // Persisted clock fixture exercises restart-time deadline enforcement without waiting 300 seconds.
        jdbc.update("UPDATE edgeai.task_retry SET available_at=now()-interval '2 seconds',deadline=now()-interval '1 second' WHERE task_id IN (SELECT id FROM edgeai.task WHERE run_id=?)",expired);
        assertThat(lifecycle.retryTask(task(expired,"sink").id())).isTrue();
        assertThat(task(expired,"child").state()).isEqualTo("SKIPPED");assertThat(task(expired,"independent").state()).isEqualTo("RUNNING");
        for(String name:List.of("source","sink")){assertThat(executions.retry(task(expired,name).id())).isEmpty();assertThat(executions.attempts(task(expired,name).id())).hasSize(1);}
    }
    @Test void routeInspectionIsAuthenticatedPaginatedAndContainsOnlyProvenanceAndStatus()throws Exception{
        UUID id=create(definition(false,false));String path="/api/v1/workflow-runs/"+id+"/streams";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var first=(Map<?,?>)json.decode(perform(get(path+"?limit=2"),200));assertThat((List<?>)first.get("items")).hasSize(2);assertThat(first.get("nextOffset").toString()).isEqualTo("2");
        var last=(Map<?,?>)json.decode(perform(get(path+"?limit=2&offset=2"),200));assertThat((List<?>)last.get("items")).hasSize(1);assertThat(last.get("nextOffset")).isNull();
        assertThat(perform(get(path+"?limit=101"),400)).contains("INVALID_WORKFLOW");assertThat(perform(get("/api/v1/workflow-runs/"+UUID.randomUUID()+"/streams"),404)).contains("RUN_NOT_FOUND");
        claim(id,"source");claim(id,"sink");streams.prepare(id);
        String response=perform(get(path),200);assertThat(response).contains("PREPARING","SYNTHETIC","sourceSessionId","componentId").doesNotContain("password","mqtts://","Authorization","signedUrl");
    }
    @Test void deviceTokenDiscoversOnlyItsPinnedRoutesAndReadsPreparationWithoutRenewingAuthority()throws Exception{
        var d=definition(false,false);UUID run=create(d);var session=deviceStore.activeSession(d.devices().getFirst().id()).orElseThrow();
        var initial=mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.apiVersion").value("edgeai.device-routes/v1")).andExpect(jsonPath("$.runId").value(run.toString()))
            .andExpect(jsonPath("$.sessionId").value(session.id().toString())).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].generation").isEmpty()).andExpect(jsonPath("$.nextOffset").isEmpty())
            .andReturn().getResponse().getContentAsString();
        assertThat(initial).doesNotContain("password","credential","token","mqtt","signedUrl",d.devices().get(1).id().toString());
        assertThat(routes.forRun(run,20,0)).allMatch(r->routes.history(r.id(),20,0).isEmpty());
        claim(run,"source");claim(run,"sink");streams.prepare(run);
        var before=routes.forRun(run,20,0).stream().map(r->routes.history(r.id(),1,0).getFirst()).toList();
        mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].generation.state").value("PREPARING"));
        assertThat(routes.forRun(run,20,0).stream().map(r->routes.history(r.id(),1,0).getFirst()).toList()).isEqualTo(before);
        var foreign=deviceStore.activeSession(device().id()).orElseThrow();
        mvc.perform(deviceRoutes(foreign,run,100,0)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_RUN_NOT_FOUND"));
        mvc.perform(deviceRoutes(session,UUID.randomUUID(),100,0)).andExpect(status().isNotFound());
    }
    @Test void deviceRoutePagesPreserveFanoutAndDoNotExposeOtherSources()throws Exception{
        var source=device();var spec=service(List.of("input"),false,false);
        var d=new Definition(version(Map.of("a",spec,"b",spec),List.of()),List.of(input(source,"a","input"),input(source,"b","input")),List.of(source));
        UUID run=create(d);var session=deviceStore.activeSession(source.id()).orElseThrow();var found=new ArrayList<String>();
        for(int offset=0;offset<2;offset++){
            var page=(Map<?,?>)json.decode(mvc.perform(deviceRoutes(session,run,1,offset)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn().getResponse().getContentAsString());
            found.add((String)((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("routeId"));
            assertThat(page.get("nextOffset")==null?null:page.get("nextOffset").toString()).isEqualTo(offset==0?"1":null);
        }
        assertThat(found).doesNotHaveDuplicates().isSorted();
        mvc.perform(deviceRoutes(session,run,1,2)).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.nextOffset").isEmpty());
        for(int limit:List.of(0,101))mvc.perform(deviceRoutes(session,run,limit,0)).andExpect(status().isBadRequest());
        mvc.perform(deviceRoutes(session,run,100,1000001)).andExpect(status().isBadRequest());
    }
    @Test void deviceRouteDiscoveryRejectsManagementIdentityAndRotatedOrForeignSession()throws Exception{
        var d=definition(false,false);UUID run=create(d);var session=deviceStore.activeSession(d.devices().getFirst().id()).orElseThrow();
        var path="/internal/v1/devices/"+session.deviceId()+"/sessions/"+session.id()+"/streams/routes";
        mvc.perform(post(path).with(user("manager")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization","Bearer "+deviceTokens.issue(session))).andExpect(status().isUnauthorized());
        var wrong=encode(Map.of("epoch",session.epoch()+1,"runId",run.toString(),"limit",100,"offset",0));
        mvc.perform(deviceRoutes(session,run,100,0).content(wrong)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_SCOPE_CHANGED"));
        devices.openSession(session.deviceId(),encode(Map.of("bootId",UUID.randomUUID().toString())));
        mvc.perform(deviceRoutes(session,run,100,0)).andExpect(status().isUnauthorized());
        var next=deviceStore.activeSession(session.deviceId()).orElseThrow();
        mvc.perform(deviceRoutes(next,run,100,0)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STREAM_DEVICE_SCOPE_CHANGED"));
    }
    @Test void provisioningAndDevicePinsAreImmutableAndCannotBeRetrofittedOntoBatchRuns()throws Exception{
        UUID id=create(definition(false,false));var pin=store.bindings(id).getFirst();
        for(String table:List.of("stream_run_configuration","stream_device_binding")){
            assertThatThrownBy(()->jdbc.update("UPDATE edgeai."+table+" SET run_id=run_id WHERE run_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai."+table+" WHERE run_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(s->{s.setRollbackOnly();jdbc.execute("TRUNCATE edgeai."+table+" CASCADE");return null;})).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.stream_device_binding(route_id,run_id,device_id,session_id,epoch) VALUES (?,?,?,?,?)",UUID.randomUUID(),id,pin.deviceId(),pin.sessionId(),pin.epoch())).isInstanceOf(DataIntegrityViolationException.class);
        var version=version(Map.of("batch",service(null,false,false)),List.of());
        var batch=runs.create(UUID.randomUUID().toString(),encode(Map.of("workflowVersionId",version.toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of()))).value();
        assertThatThrownBy(()->store.create(new StreamRunRepository.Configuration(batch.id(),"public-stream-test","sha256:"+"b".repeat(64),120,Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.find(batch.id())).isEmpty();
    }
}
