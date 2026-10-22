package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.StreamConnectionSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.SensorObservationSubscriber;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
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

/** Opt-in ALL_OBSERVATIONS delivery through the MQTT receiver seam, public run API and Runner input API on real PostgreSQL. */
@SpringBootTest(properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.vd.enabled=true",
    "edgeai.stream.enabled=true","edgeai.stream.bindings-enabled=true","edgeai.stream.runs-enabled=true","edgeai.data-store.raw.enabled=true",
    "edgeai.stream.broker-digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","edgeai.stream.lease-seconds=120",
    "edgeai.workflow-data.feed.max-backlog=6","edgeai.workflow-data.feed.max-entries=12"})
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class WorkflowFullObservationIntegrationTest {
    private static final String NAMESPACE="full-observation-"+UUID.randomUUID();
    private static final Path KEY=key();
    private static Path key(){try{
        var p=Files.createTempFile("edgeai-full-observation-",".key",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);Files.writeString(p,HexFormat.of().formatHex(bytes));return p;
    }catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("edgeai.runtime.namespace",()->NAMESPACE);p.add("edgeai.runtime.key-file",KEY::toString);p.add("edgeai.stream.device-key-file",KEY::toString);}
    @AfterAll static void removeKey()throws Exception{Files.delete(KEY);}
    @MockitoBean RuntimeGateway gateway;@MockitoBean S3ArtifactStore storage;@MockitoBean MosquittoStreamBroker broker;
    @MockitoBean ManagedDeviceService managedSensors;@MockitoBean SensorTelemetryRepository sensorArchive;@MockitoBean SensorObservationSubscriber mqtt;
    @MockitoBean StreamAuthorityWorker authorityWorker;@MockitoBean StreamConnectionSettings connection;@MockitoBean StreamRunWorker scheduledWorker;
    @Autowired ProfileService profiles;@Autowired WorkflowService workflows;@Autowired ExecutionRepository executions;@Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;@Autowired RunnerTokenService runnerTokens;@Autowired SensorArchiveCollector collector;@Autowired MockMvc mvc;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired ObservationFeedRepository feeds;@Autowired WorkflowSourceFeedService feedService;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final JsonDocuments json=new JsonDocuments();
    private final AtomicReference<SensorObservationSubscriber.Receiver> receiver=new AtomicReference<>();
    private String device;private String uid;private String type;private String generation;private final String hash="sha256:"+"a".repeat(64);

    private String encode(Object value){return json.canonical(value);}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(String value){return (Map<String,Object>)json.decode(value);}
    private UUID profile(Object spec){return profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key","full-observation-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    @SuppressWarnings("unchecked") private Map<String,Object> streamSpec()throws Exception{return (Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-stream.example.json")));}
    @SuppressWarnings("unchecked") private UUID sourceProfile()throws Exception{
        var spec=streamSpec();var source=(Map<String,Object>)spec.get("stream");source.put("source","WORKFLOW_DATA");source.put("inputs",Map.of());
        source.put("command",List.of("edgeai-workflow-input"));source.put("args",List.of());
        source.put("outputs",Map.of("sample",Map.of("mediaType","application/json","maxPayloadBytes",4096)));return profile(spec);
    }
    @SuppressWarnings("unchecked") private UUID sinkProfile()throws Exception{
        var spec=streamSpec();var stream=(Map<String,Object>)spec.get("stream");
        stream.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxPayloadBytes",4096)));stream.put("outputs",Map.of());return profile(spec);
    }
    private String perform(MockHttpServletRequestBuilder r,int code)throws Exception{return mvc.perform(r.with(user("test")).with(csrf())).andExpect(status().is(code)).andReturn().getResponse().getContentAsString();}
    private UUID run(Map<String,Object> dataSource)throws Exception{
        UUID run=UUID.fromString((String)decode(createRun(dataSource,201)).get("id"));claim(run,"source");claim(run,"sink");return run;
    }
    private String createRun(Map<String,Object> dataSource,int code)throws Exception{
        var workflow=workflows.create(encode(Map.of("key","full-observation-"+UUID.randomUUID(),"displayName","Full observation"))).value();
        var version=workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",List.of(
            Map.of("key","source","serviceProfileVersionId",sourceProfile().toString(),"parameters",Map.of("dataSource",dataSource)),
            Map.of("key","sink","serviceProfileVersionId",sinkProfile().toString(),"parameters",Map.of())),
            "dependencies",List.of(Map.of("fromTask","source","toTask","sink","fromPort","sample","toPort","input","mode","STREAM"))))).value();
        var request=Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of());
        return perform(post("/api/v1/workflow-runs").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(encode(request)),code);
    }
    private void claim(UUID run,String name){
        var task=executions.tasks(run).stream().filter(t->t.key().equals(name)).findFirst().orElseThrow();var a=executions.attempts(task.id()).getFirst();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");lifecycle.submitted(a.id(),pod.jobUid());lifecycle.claim(a.id(),a.epoch(),pod);
    }
    private RuntimeInstance runtime(UUID run){
        var task=executions.tasks(run).stream().filter(t->t.key().equals("source")).findFirst().orElseThrow();
        return runtimes.byAttempt(executions.attempts(task.id()).getFirst().id()).orElseThrow();
    }
    /** Runner request: identity plus optional cursor/maxItems, exactly like the Runner sends. */
    private Map<String,Object> input(UUID run,int code,Object... extra)throws Exception{
        var r=runtime(run);var body=new TreeMap<String,Object>(Map.of("epoch",r.epoch(),"podUid",r.producerPodUid().toString()));
        for(int i=0;i<extra.length;i+=2)body.put((String)extra[i],extra[i+1]);
        var response=mvc.perform(post("/internal/v1/attempts/"+r.attemptId()+"/streams/input").header("Authorization","Bearer "+runnerTokens.issue(r))
            .header("X-EdgeAI-Pod-Token","source-pod-proof").contentType("application/json").content(encode(body))).andExpect(status().is(code)).andReturn();
        return decode(response.getResponse().getContentAsString());
    }

    /** Every cross-thread wait in this class is bounded so a regression fails instead of hanging the suite. */
    private static final long BOUND_SECONDS=20;
    private static void await(java.util.concurrent.CountDownLatch latch)throws InterruptedException{
        assertThat(latch.await(BOUND_SECONDS,java.util.concurrent.TimeUnit.SECONDS)).as("bounded wait").isTrue();
    }
    private static <T> T get(java.util.concurrent.Future<T> future)throws Exception{return future.get(BOUND_SECONDS,java.util.concurrent.TimeUnit.SECONDS);}
    /** Held transactions wait at most the bound, then roll back by throwing. */
    private static void hold(java.util.concurrent.CountDownLatch release){
        try{if(!release.await(BOUND_SECONDS,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("held transaction released by timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    private static void stop(java.util.concurrent.ExecutorService pool)throws InterruptedException{
        pool.shutdownNow();assertThat(pool.awaitTermination(BOUND_SECONDS,java.util.concurrent.TimeUnit.SECONDS)).as("workers stopped").isTrue();
    }
    /** Holds the next Runner input request at its source-identity check (between the subscription and the page read). */
    private java.util.concurrent.CountDownLatch[] barrier(){
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        var route=managedSensors.workflowObservation(device);var armed=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(managedSensors.workflowObservation(device)).thenAnswer(call->{if(armed.compareAndSet(true,false)){entered.countDown();hold(resume);}return route;});
        return new java.util.concurrent.CountDownLatch[]{entered,resume};
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> reply){return (List<Map<String,Object>>)reply.get("items");}
    @SuppressWarnings("unchecked") private static double value(Map<String,Object> item){return ((Number)((Map<String,Object>)item.get("sample")).get("value")).doubleValue();}

    @BeforeEach void sensor()throws Exception {
        device="fixture-"+UUID.randomUUID().toString().substring(0,8);uid=UUID.randomUUID().toString();generation=UUID.randomUUID().toString();
        when(sensorArchive.enabled()).thenReturn(true);when(sensorArchive.rawRetentionDays()).thenReturn(30);
        doAnswer(call->{receiver.set(call.getArgument(1));return null;}).when(mqtt).configure(any(),any());
        when(gateway.authenticatePod(any(),eq("source-pod-proof"))).thenAnswer(call->{RuntimeInstance r=call.getArgument(0);return new RuntimePod(r.jobUid(),r.producerPodUid(),r.nodeUid(),r.nodeName());});
        sensorType("float");
    }
    /** (Re)registers the fixture measurement with one Mapper data type and refreshes the collector routes. */
    private void sensorType(String type){
        this.type=type;
        var route=new TreeMap<String,Object>(Map.of("deviceId",device,"deviceUid",uid,"property","value","sourceGeneration",generation,
            "configurationSha256",hash,"type",type,"unit","C","namespace","edgeai-devices"));
        when(managedSensors.workflowObservation(device)).thenReturn(Map.copyOf(route));
        var archive=new TreeMap<String,Object>(route);archive.put("broker","tcp://198.51.100.4:1883");archive.put("topic","edgeai/observations/v1/edgeai-devices/"+device+"/value");archive.put("format","QUALIFIED");
        when(managedSensors.archiveCatalog(any())).thenReturn(List.of(archive));
        collector.refresh();assertThat(receiver.get()).isNotNull();
    }
    /** One MQTT QoS1 delivery of a Mapper-qualified observation; returns only after the collector would ACK. */
    private void observe(long sequence,Instant observedAt,double value)throws Exception{observe(sequence,observedAt,value,"");}
    /** The collector's completed-delivery cache is per process; a byte-different copy of the same observation models a redelivery after restart. */
    private void redeliverUncached(long sequence,Instant observedAt,double value)throws Exception{observe(sequence,observedAt,value," ");}
    private void observe(long sequence,Instant observedAt,Object value,String suffix)throws Exception{
        var payload=new TreeMap<String,Object>(Map.of("apiVersion","edgeai.observation/v1","deviceName",device,"namespace","edgeai-devices","property","value","sourceGeneration",generation,
            "configurationSha256",hash,"type",type,"unit","C"));
        payload.putAll(Map.of("sessionId","k1","sequence",sequence,"timeBasis","SOURCE","observedAt",observedAt.toEpochMilli(),"value",value));
        receiver.get().receive(new SensorObservationSubscriber.Delivery("tcp://198.51.100.4:1883","edgeai/observations/v1/edgeai-devices/"+device+"/value",(encode(payload)+suffix).getBytes(StandardCharsets.UTF_8),false));
    }
    private Map<String,Object> selection(){return Map.of("kind","SENSOR","deviceId",device,"property","value","delivery","ALL_OBSERVATIONS");}

    @Test void sensorBurstWithEqualAndOutOfOrderTimestampsIsDeliveredOnceInAcceptanceOrderAfterTheStartBoundary()throws Exception {
        var t=Instant.now().minusSeconds(10);
        observe(1,t,10);
        UUID run=run(selection());
        observe(2,t,20);observe(3,t,30);observe(4,t.minusSeconds(5),40);redeliverUncached(3,t,30);observe(5,t.plusSeconds(1),50);
        var first=input(run,200);
        assertThat(first.get("state")).isEqualTo("DATA");
        assertThat(items(first).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(20.0,30.0,40.0,50.0);
        assertThat(items(first).stream().map(i->i.get("sampleId")).distinct()).hasSize(4);
        var replay=input(run,200,"cursor",items(first).get(1).get("cursor"));
        assertThat(replay.get("state")).isEqualTo("DATA");
        assertThat(items(replay).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(40.0,50.0);
        assertThat(input(run,200,"cursor",items(first).getLast().get("cursor"))).isEqualTo(Map.of("state","WAITING"));
    }

    /** Two control-plane replicas may append the same source concurrently; neither the reader nor the order may skip an uncommitted position. */
    @Test void aConcurrentUncommittedAppendIsNeverSkippedByAReaderOrOvertakenByALaterCommit()throws Exception {
        UUID run=run(selection());String key=WorkflowSourceFeedService.sensorKey(uid,"value");var now=Instant.now();
        var first=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                var r=feeds.append(key,generation+":k1:1",now,encode(document(1,now,10)),now,feedService.limits());first.countDown();
                hold(release);return r;}));
            await(first);
            var later=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->
                feeds.append(key,generation+":k1:2",now,encode(document(2,now,20)),now,feedService.limits())));
            Thread.sleep(500);
            assertThat(later.isDone()).as("second append must wait for the first commit").isFalse();
            assertThat(input(run,200)).isEqualTo(Map.of("state","WAITING"));
            release.countDown();
            assertThat(get(holder)).isEqualTo(ObservationFeedRepository.Append.APPENDED);
            assertThat(get(later)).isEqualTo(ObservationFeedRepository.Append.APPENDED);
        }finally{release.countDown();stop(pool);}
        assertThat(items(input(run,200)).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(10.0,20.0);
    }
    private Map<String,Object> document(long sequence,Instant at,double value){
        var d=new TreeMap<String,Object>(Map.of("deviceUid",uid,"property","value","sourceGeneration",generation,"configurationSha256",hash,"type","float","unit","C",
            "sessionId","k1","sequence",sequence,"observedAt",at.toString(),"timeBasis","SOURCE"));d.put("value",value);d.put("quality","QUALIFIED");return d;
    }

    @Test void booleanSensorObservationsAreDeliveredAndStringSensorsAreRejectedBeforeTheRunStarts()throws Exception {
        sensorType("bool");UUID run=run(selection());var t=Instant.now();
        observe(1,t,true,"");observe(2,t,false,"");observe(3,t,true,"");
        assertThat(items(input(run,200)).stream().<Object>map(i->((Map<?,?>)i.get("sample")).get("value")).toList()).containsExactly(true,false,true);
        sensorType("string");
        assertThat(decode(createRun(selection(),409)).get("code")).isEqualTo("WORKFLOW_SOURCE_UNSUPPORTED");
    }

    @Test void cursorsAreScopedToTheirRunTaskAndCommittedHeadAndLatestInputsRejectThem()throws Exception {
        UUID run=run(selection()),other=run(selection());var t=Instant.now();
        observe(1,t,1);
        var mine=(String)items(input(run,200)).getFirst().get("cursor");var theirs=(String)items(input(other,200)).getFirst().get("cursor");
        assertThat(input(run,409,"cursor",theirs).get("code")).isEqualTo("WORKFLOW_CURSOR_INVALID");
        assertThat(input(run,409,"cursor",mine.substring(0,mine.indexOf(':'))+":99").get("code")).isEqualTo("WORKFLOW_CURSOR_INVALID");
        input(run,400,"cursor","not-a-cursor");
        input(run,400,"maxItems",0);
        assertThat(input(run,200,"cursor",mine)).isEqualTo(Map.of("state","WAITING"));
        UUID latest=run(Map.of("kind","SENSOR","deviceId",device,"property","value"));
        input(latest,400,"cursor",mine);
    }
    @Test void changedSensorGenerationFailsExplicitlyInsteadOfDeliveringOrSkipping()throws Exception {
        UUID run=run(selection());observe(1,Instant.now(),1);
        when(managedSensors.workflowObservation(device)).thenReturn(Map.of("deviceId",device,"deviceUid",uid,"property","value","sourceGeneration",UUID.randomUUID().toString(),
            "configurationSha256",hash,"type","float","unit","C","namespace","edgeai-devices"));
        assertThat(input(run,409).get("code")).isEqualTo("WORKFLOW_SOURCE_CHANGED");
    }

    @Test void backlogAndRetentionBoundsAreExplicitGapsWhileAnUpToDateConsumerKeepsEveryObservation()throws Exception {
        UUID lagging=run(selection()),reading=run(selection());var t=Instant.now().minusSeconds(5);var cursors=new ArrayList<String>();var values=new ArrayList<Double>();
        for(int i=1;i<=20;i++){
            observe(i,t.plusMillis(i),i);
            var reply=cursors.isEmpty()?input(reading,200):input(reading,200,"cursor",cursors.getLast());
            for(var item:items(reply)){cursors.add((String)item.get("cursor"));values.add(value(item));}
        }
        assertThat(values).containsExactly(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0,13.0,14.0,15.0,16.0,17.0,18.0,19.0,20.0);
        assertThat(input(lagging,409).get("code")).isEqualTo("WORKFLOW_SOURCE_GAP");
        // A Runner restored from an old checkpoint cannot silently resume past the bounded retained window.
        assertThat(input(reading,409,"cursor",cursors.getFirst()).get("code")).isEqualTo("WORKFLOW_SOURCE_GAP");
        assertThat(input(reading,200,"cursor",cursors.getLast())).isEqualTo(Map.of("state","WAITING"));
        // A redelivery older than every retained observation may be a trimmed duplicate; it is a gap, not a second delivery.
        redeliverUncached(1,t.plusMillis(1),1);
        assertThat(input(reading,409,"cursor",cursors.getLast()).get("code")).isEqualTo("WORKFLOW_SOURCE_GAP");
    }

    /** Two writers capture acceptance times before the feed lock, so a later position can carry the older time. */
    @Test void ageRetentionTrimsOnlyTheExpiredPrefixWhenAcceptanceTimesAreNotMonotonic()throws Exception {
        UUID run=run(selection());var key="retention-"+UUID.randomUUID();var t=Instant.now().minusSeconds(600);
        var limits=new ObservationFeedRepository.Limits(6,12,60);var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var subscription=tx.execute(status->feeds.subscribe(run,"retention","SENSOR",key,t));
        tx.executeWithoutResult(status->{
            assertThat(feeds.append(key,"n",t,"{}",t.plusSeconds(60),limits)).isEqualTo(ObservationFeedRepository.Append.APPENDED);
            assertThat(feeds.append(key,"n+1",t,"{}",t,limits)).isEqualTo(ObservationFeedRepository.Append.APPENDED);
        });
        // Cutoff t+30: position 2 has aged out, but position 1 has not, so nothing may be trimmed yet.
        tx.executeWithoutResult(status->feeds.acknowledge(subscription,2,t.plusSeconds(90),limits));
        var page=feeds.read(subscription,0,256).orElseThrow();
        assertThat(page.floor()).isZero();
        assertThat(page.entries().stream().map(ObservationFeedRepository.Entry::position).toList()).containsExactly(1L,2L);
        tx.executeWithoutResult(status->feeds.acknowledge(subscription,2,t.plusSeconds(121),limits));
        page=feeds.read(subscription,0,256).orElseThrow();
        assertThat(page.floor()).isEqualTo(2);
        assertThat(page.entries()).isEmpty();
    }

    @Test void aFencedAttemptCannotReadOrAdvanceTheFeedAfterCancellation()throws Exception {
        UUID run=run(selection());observe(1,Instant.now(),1);
        perform(post("/api/v1/workflow-runs/"+run+"/cancel").contentType("application/json").content("{}"),200);
        assertThat(input(run,409).get("code")).isIn("PRODUCER_FENCED","RUNTIME_FENCED","ATTEMPT_FENCED");
    }

    @Test void fullSensorDeliveryIsRejectedAtRunStartWhenTheArchiveCollectorCannotFeedIt()throws Exception {
        when(sensorArchive.enabled()).thenReturn(false);
        var workflow=workflows.create(encode(Map.of("key","full-observation-"+UUID.randomUUID(),"displayName","Collector off"))).value();
        var version=workflows.publish(workflow.id(),encode(Map.of("version","1.0.0","tasks",List.of(
            Map.of("key","source","serviceProfileVersionId",sourceProfile().toString(),"parameters",Map.of("dataSource",selection())),
            Map.of("key","sink","serviceProfileVersionId",sinkProfile().toString(),"parameters",Map.of())),
            "dependencies",List.of(Map.of("fromTask","source","toTask","sink","fromPort","sample","toPort","input","mode","STREAM"))))).value();
        var request=Map.of("workflowVersionId",version.id().toString(),"execution",Map.of("mode","AUTO"),"parameters",Map.of());
        var reply=decode(perform(post("/api/v1/workflow-runs").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(encode(request)),409));
        assertThat(reply.get("code")).isEqualTo("WORKFLOW_SOURCE_UNAVAILABLE");
    }

    /** The acknowledgement can wait on another transaction; the owner is checked again after it, and reads never renew the lease. */
    @Test void ownerRevokedWhileAcknowledgementBlocksGetsNoDataAndReadsNeverExtendTheLease()throws Exception {
        UUID run=run(selection());observe(1,Instant.now(),1);
        var lease=runtime(run).expiresAt();
        var cursor=(String)items(input(run,200)).getFirst().get("cursor");
        assertThat(runtime(run).expiresAt()).isEqualTo(lease);
        observe(2,Instant.now(),2);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            // An uncommitted drain holds this consumer's subscription row, so the Runner's acknowledgement blocks.
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                feeds.drain(run,Instant.now());held.countDown();
                hold(release);status.setRollbackOnly();return null;}));
            await(held);
            var read=pool.submit(()->input(run,409,"cursor",cursor));
            Thread.sleep(500);
            assertThat(read.isDone()).as("acknowledgement waits for the concurrent transaction").isFalse();
            perform(post("/api/v1/workflow-runs/"+run+"/cancel").contentType("application/json").content("{}"),200);
            release.countDown();get(holder);
            assertThat(get(read).get("code")).isIn("PRODUCER_FENCED","RUNTIME_FENCED","ATTEMPT_FENCED");
        }finally{release.countDown();stop(pool);}
    }
    @Test void cleanupWorkerBoundsIdleAndTerminalFeedsWithoutNewInputAndKeepsUnacknowledgedEntries()throws Exception {
        // Sensor-only deployment: continuous VD maintenance is absent, and worker-enabled=false registers no scheduled feed worker.
        assertThat(context.getBeanNamesForType(ProcessingMaintenance.class)).isEmpty();
        assertThat(context.getBeanNamesForType(WorkflowSourceFeedWorker.class)).isEmpty();
        UUID live=run(selection()),ended=run(selection());var t=Instant.now();
        observe(1,t,1);observe(2,t.plusMillis(1),2);observe(3,t.plusMillis(2),3);
        perform(post("/api/v1/workflow-runs/"+ended+"/cancel").contentType("application/json").content("{}"),200);
        var endedSubscription=feeds.subscription(ended,"source").orElseThrow();
        var later=Instant.now().plusSeconds(feedService.limits().retentionSeconds()+1);
        new WorkflowSourceFeedWorker(feedService,true).maintain(later);
        assertThat(feeds.subscription(ended,"source")).as("inspect-only never cleans").isPresent();
        var worker=new WorkflowSourceFeedWorker(feedService,false);
        worker.maintain(later);
        // No new observation arrived, yet the terminal consumer's metadata expired and the live consumer kept every unacknowledged entry.
        assertThat(feeds.subscription(ended,"source")).isEmpty();
        var reply=input(live,200);
        assertThat(items(reply).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(1.0,2.0,3.0);
        var first=(String)items(reply).getFirst().get("cursor");
        assertThat(input(live,200,"cursor",items(reply).getLast().get("cursor"))).isEqualTo(Map.of("state","WAITING"));
        worker.maintain(later);
        var liveSubscription=feeds.subscription(live,"source").orElseThrow();
        assertThat(feeds.read(liveSubscription,endedSubscription.startPosition(),256).orElseThrow().entries()).as("acknowledged and aged entries are removed").isEmpty();
        assertThat(feeds.read(endedSubscription,endedSubscription.startPosition(),256)).as("expired metadata is gone").isEmpty();
        assertThat(input(live,409,"cursor",first).get("code")).isEqualTo("WORKFLOW_SOURCE_GAP");
        perform(post("/api/v1/workflow-runs/"+live+"/cancel").contentType("application/json").content("{}"),200);
        worker.maintain(later);
        assertThat(feeds.subscription(live,"source")).isEmpty();
    }

    /** Consumer B keeps the source accepting after A's drain; A's in-flight read must honour the committed watermark. */
    @Test void drainCommittedDuringAnInFlightReadNeverDeliversPastTheWatermarkAndTheRetryEnds()throws Exception {
        UUID drained=run(selection()),other=run(selection());
        observe(1,Instant.now(),1);
        var cursor=(String)items(input(drained,200)).getFirst().get("cursor");
        var gate=barrier();var pool=java.util.concurrent.Executors.newFixedThreadPool(1);
        try{
            var read=pool.submit(()->input(drained,200,"cursor",cursor));
            await(gate[0]);
            perform(post("/api/v1/workflow-runs/"+drained+"/drain").contentType("application/json").content("{}"),200);
            observe(2,Instant.now(),2);
            gate[1].countDown();
            assertThat(get(read)).isEqualTo(Map.of("state","END"));
        }finally{gate[1].countDown();stop(pool);}
        assertThat(input(drained,200,"cursor",cursor)).as("retry after END").isEqualTo(Map.of("state","END"));
        assertThat(items(input(other,200)).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(1.0,2.0);
    }
    /** A drain committing while the acknowledgement waits on it changes nothing already decided; the retry still ends after the backlog. */
    @Test void drainCommittingWhileTheAcknowledgementBlocksIsHonouredByTheRetry()throws Exception {
        UUID drained=run(selection()),other=run(selection());
        observe(1,Instant.now(),1);observe(2,Instant.now(),2);
        var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        Map<String,Object> reply;
        try{
            // The drain transaction locks A's subscription row until released, then commits watermark 2.
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->{
                feeds.drain(drained,Instant.now());held.countDown();hold(release);return null;}));
            await(held);
            var read=pool.submit(()->input(drained,200,"maxItems",1));
            Thread.sleep(300);
            assertThat(read.isDone()).as("acknowledgement waits on the drain transaction").isFalse();
            release.countDown();get(holder);
            reply=get(read);
            // Consumer B keeps accepting after A's watermark 2 was committed.
            observe(3,Instant.now(),3);
        }finally{release.countDown();stop(pool);}
        var values=new ArrayList<Double>();String cursor=null;
        for(int page=0;page<5 && !"END".equals(reply.get("state"));page++){
            for(var item:items(reply)){values.add(value(item));cursor=(String)item.get("cursor");}
            reply=input(drained,200,"cursor",cursor,"maxItems",1);
        }
        assertThat(reply).isEqualTo(Map.of("state","END"));
        assertThat(values).containsExactly(1.0,2.0);
        assertThat(items(input(other,200)).stream().map(WorkflowFullObservationIntegrationTest::value).toList()).containsExactly(1.0,2.0,3.0);
    }

    /** A GAP committed while a caught-up read is in flight, followed by drain, must surface as GAP, never as END. */
    @Test void gapCommittedDuringAnInFlightReadThenDrainIsReportedInsteadOfEnding()throws Exception {
        UUID run=run(selection());var t=Instant.now().minusSeconds(5);String cursor=null;
        for(int i=1;i<=20;i++){
            observe(i,t.plusMillis(i),i);
            var reply=cursor==null?input(run,200):input(run,200,"cursor",cursor);
            for(var item:items(reply))cursor=(String)item.get("cursor");
        }
        var caughtUp=cursor;var gate=barrier();var pool=java.util.concurrent.Executors.newFixedThreadPool(1);
        try{
            var read=pool.submit(()->input(run,409,"cursor",caughtUp));
            await(gate[0]);
            // Older than every trimmed observation: possibly a trimmed duplicate, so the consumer becomes GAP without a head change.
            redeliverUncached(1,t.plusMillis(1),1);
            perform(post("/api/v1/workflow-runs/"+run+"/drain").contentType("application/json").content("{}"),200);
            gate[1].countDown();
            assertThat(get(read).get("code")).isEqualTo("WORKFLOW_SOURCE_GAP");
        }finally{gate[1].countDown();stop(pool);}
        assertThat(input(run,409,"cursor",caughtUp).get("code")).as("retry").isEqualTo("WORKFLOW_SOURCE_GAP");
    }
}
