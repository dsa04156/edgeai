package io.edgeai.app.integration;

import io.edgeai.adapters.storage.S3ArtifactStore;
import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.app.config.*;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.RouteGeneration.Actor;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real DB, Spring authentication/transactions, worker and TLS broker. Pod proof is an explicit
 * RuntimeGateway fixture; the Python probe computes with the SDK but does not claim Kubernetes execution. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"edgeai.runtime.enabled=true","edgeai.runtime.worker-enabled=false","edgeai.stream.enabled=true",
    "edgeai.stream.bindings-enabled=true","edgeai.stream.reconcile-ms=50"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class StreamBindingIntegrationTest {
    private static final String DIGEST="sha256:"+UUID.randomUUID().toString().replace("-","").repeat(2);
    private static final String NAMESPACE="stream-api-"+UUID.randomUUID();
    private static final Fixture BROKER=new Fixture();
    private static StreamAuthorityWorker runningWorker;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("edgeai.stream.broker-url",()->"ssl://localhost:"+BROKER.port);p.add("edgeai.stream.broker-digest",()->DIGEST);
        p.add("edgeai.stream.ca-file",()->BROKER.file("server.crt"));p.add("edgeai.stream.admin-password-file",()->BROKER.file("admin.password"));
        p.add("edgeai.stream.principal-key-file",()->BROKER.file("principal.key"));p.add("edgeai.stream.device-key-file",()->BROKER.file("device.key"));
        p.add("edgeai.runtime.key-file",()->BROKER.file("runner.key"));p.add("edgeai.runtime.namespace",()->NAMESPACE);
    }
    @Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired DeviceService devices;@Autowired WorkflowService workflows;
    @Autowired ExecutionService runs;@Autowired RuntimeRepository runtimes;@Autowired RunnerTokenService runnerTokens;
    @Autowired DataRouteService routes;@Autowired DataRouteRepository routeStore;@Autowired StreamBindingService bindings;
    @Autowired StreamAuthorityWorker worker;@Autowired MosquittoStreamBroker broker;
    @org.springframework.boot.test.web.server.LocalServerPort int apiPort;
    @Autowired org.springframework.core.env.Environment environment;
    @MockitoBean RuntimeGateway podGateway;@MockitoBean S3ArtifactStore storage;
    private final JsonDocuments json=new JsonDocuments();private final Map<UUID,RuntimePod> pods=new ConcurrentHashMap<>();
    private final List<UUID> runIds=new ArrayList<>();private final List<MqttClient> clients=new ArrayList<>();
    private record Execution(UUID run,UUID task,UUID attempt,RuntimePod pod){}
    private record Source(Device device,DeviceSession session){}
    private record Route(Execution execution,Source source,Permission permission){}
    @BeforeEach void setup(){runningWorker=worker;when(podGateway.authenticatePod(any(),any())).thenAnswer(call->{
        RuntimeInstance r=call.getArgument(0);if(!"pod-proof-fixture".equals(call.getArgument(1)) || !pods.containsKey(r.attemptId()))throw new RuntimeGatewayException(RuntimeGatewayException.Reason.AUTH_REJECTED);return pods.get(r.attemptId());});}
    @AfterEach void clean()throws Exception {
        for(var client:clients){try{client.disconnectForcibly(0,100,false);}catch(Exception ignored){}try{client.close(true);}catch(Exception ignored){}}
        for(var id:runIds)runs.cancelRun(id,"{}");
        until(()->runIds.stream().flatMap(id->routeStore.forRun(id,100,0).stream()).noneMatch(r->routeStore.open(r.id()).isPresent()));
    }
    @AfterAll static void stop()throws Exception {if(runningWorker!=null)runningWorker.close();BROKER.close();}
    @SuppressWarnings("unchecked") private Execution execution()throws Exception {
        var spec=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",4096,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","stream-api-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var w=workflows.create(json.canonical(Map.of("key","stream-api-"+UUID.randomUUID(),"displayName","Stream binding fixture"))).value();
        var v=workflows.publish(w.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","sink","serviceProfileVersionId",profile.id().toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var run=runs.create(UUID.randomUUID().toString(),json.canonical(Map.of("workflowVersionId",v.id().toString(),"parameters",Map.of(),"execution",Map.of("mode","AUTO")))).value();runIds.add(run.id());
        var task=runs.detail(run.id()).tasks().getFirst();var attempt=runs.taskDetail(task.id()).attempts().getFirst();
        var pod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");pods.put(attempt.id(),pod);
        var e=new Execution(run.id(),task.id(),attempt.id(),pod);runner(e,"claim",Map.of("epoch",1,"podUid",pod.podUid().toString()),200);return e;
    }
    private Source source(){
        var profile=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key","stream-api-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
        var device=devices.create(json.canonical(Map.of("key","stream-api-"+UUID.randomUUID(),"displayName","Stream source fixture","profileVersionId",profile.id().toString(),"sourceMode","SYNTHETIC"))).value();
        return new Source(device,devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value());
    }
    private Route route(int ttl)throws Exception {var e=execution();var s=source();var r=routes.fromDevice(e.run(),s.device().id(),"samples",e.task(),"input",4096);
        var g=routes.prepare(r.id(),UUID.randomUUID(),new Actor(s.session().id(),s.session().epoch()),new Actor(e.attempt(),1),DIGEST,ttl);
        until(()->routeStore.generation(g.id()).orElseThrow().state().equals("ACTIVE"));return new Route(e,s,new Permission(r,g));}
    private String tokenPath(Source s){return "/api/v1/devices/"+s.device().id()+"/sessions/"+s.session().id()+"/stream-token";}
    private String devicePath(Source s){return "/internal/v1/devices/"+s.device().id()+"/sessions/"+s.session().id()+"/streams";}
    private String token(Source s)throws Exception {return (String)document(mvc.perform(post(tokenPath(s)).with(user("manager")).with(csrf()).contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString()).get("token");}
    private Map<?,?> document(String text){return (Map<?,?>)json.decode(text);}
    private Map<String,Object> identity(Route r){return Map.of("epoch",1,"generationId",r.permission().generation().id().toString());}
    private Map<?,?> device(Source s,String token,Object body,int expected)throws Exception {var response=mvc.perform(post(devicePath(s)).header("Authorization","Bearer "+token).contentType("application/json").content(json.canonical(body)))
        .andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();return response.getContentAsString().isBlank()?Map.of():document(response.getContentAsString());}
    private Map<?,?> runner(Execution e,String operation,Object body,int expected)throws Exception {return document(mvc.perform(post("/internal/v1/attempts/"+e.attempt()+"/"+operation)
        .header("Authorization","Bearer "+runnerTokens.issue(runtimes.byAttempt(e.attempt()).orElseThrow())).header("X-EdgeAI-Pod-Token","pod-proof-fixture")
        .contentType("application/json").content(json.canonical(body))).andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store"))
        .andReturn().getResponse().getContentAsString());}
    private Object runnerBody(Route r){return Map.of("epoch",1,"podUid",r.execution().pod().podUid().toString(),"generationId",r.permission().generation().id().toString());}
    @Test void actualAuthenticatedBindingsConnectOnlyTheirTopicsToRealTlsBroker()throws Exception {
        var r=route(120);String token;Map<?,?> producer,consumer;
        try(var http=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build()){
            String basic="Basic "+Base64.getEncoder().encodeToString((environment.getRequiredProperty("spring.security.user.name")+":"+environment.getRequiredProperty("spring.security.user.password")).getBytes(StandardCharsets.UTF_8));
            var csrf=wire(http,"GET","/api/v1/csrf",null,Map.of("Authorization",basic));
            var issued=wire(http,"POST",tokenPath(r.source()),Map.of(),Map.of("Authorization",basic,"X-CSRF-TOKEN",(String)csrf.get("token")));
            token=(String)issued.get("token");
            producer=wire(http,"POST",devicePath(r.source()),identity(r),Map.of("Authorization","Bearer "+token));
            consumer=wire(http,"POST","/internal/v1/attempts/"+r.execution().attempt()+"/streams",runnerBody(r),Map.of("Authorization","Bearer "+runnerTokens.issue(runtimes.byAttempt(r.execution().attempt()).orElseThrow()),"X-EdgeAI-Pod-Token","pod-proof-fixture"));
        }
        assertThat(java.security.MessageDigest.isEqual(token.getBytes(),token(r.source()).getBytes())).isTrue();
        assertThat(producer.get("direction")).isEqualTo("PRODUCER");assertThat(consumer.get("direction")).isEqualTo("CONSUMER");
        assertThat(producer.get("producer")).isEqualTo(consumer.get("producer"));assertThat(producer.get("generationId")).isEqualTo(r.permission().generation().id().toString());
        assertThat(Instant.parse((String)producer.get("leaseUntil"))).isEqualTo(r.permission().generation().leaseUntil());
        assertThat(Instant.parse((String)consumer.get("leaseUntil"))).isBeforeOrEqualTo(r.permission().generation().leaseUntil());
        var pub=client(producer);var sub=client(consumer);var data=new ArrayBlockingQueue<byte[]>(2);
        String topic=(String)((Map<?,?>)consumer.get("mqtt")).get("framesTopic");
        assertThat(sub.subscribe(new MqttSubscription[]{new MqttSubscription(topic,1)},new IMqttMessageListener[]{(t,m)->data.offer(m.getPayload())}).getReasonCodes()).containsExactly(1);
        pub.publish(topic,"authenticated-data".getBytes(StandardCharsets.UTF_8),1,false);assertThat(data.poll(3,TimeUnit.SECONDS)).isEqualTo("authenticated-data".getBytes(StandardCharsets.UTF_8));
        assertThat(pub.subscribe("$CONTROL/dynamic-security/#",1).getReasonCodes()).containsExactly(135);
        assertThat(json.canonical(producer).contains("PRIVATE KEY")).isFalse();assertThat(json.canonical(producer).contains(Files.readString(BROKER.root.resolve("admin.password")))).isFalse();
        verify(podGateway,atLeast(2)).authenticatePod(any(),eq("pod-proof-fixture"));
    }
    @Test void managerCsrfDeviceScopeBodyAndSizeAreEnforced()throws Exception {
        var r=route(120);var other=route(120);String credential=token(r.source());
        mvc.perform(post(tokenPath(r.source())).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(tokenPath(r.source())).with(user("manager")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/devices").header("Authorization","Bearer "+credential)).andExpect(status().isUnauthorized());
        device(other.source(),credential,identity(other),401);device(r.source(),token(other.source()),identity(r),401);
        device(r.source(),credential,identity(other),409);device(r.source(),credential,Map.of("epoch",2,"generationId",r.permission().generation().id().toString()),409);
        device(r.source(),credential,Map.of("epoch",1,"generationId",r.permission().generation().id().toString(),"role","admin"),400);
        mvc.perform(post(devicePath(r.source())).header("Authorization","Bearer "+credential).contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post(tokenPath(r.source())).with(user("manager")).with(csrf()).contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
    }
    @Test void actualPythonSdkDiscoversSpringBindingsAndCommitsTlsMqttCalculation()throws Exception {
        var r=route(8);var root=Files.createDirectory(BROKER.root.resolve("sdk-"+UUID.randomUUID()));
        var request=new TreeMap<String,Object>(Map.of("origin","http://127.0.0.1:"+apiPort,"generationId",r.permission().generation().id().toString(),
            "deviceId",r.source().device().id().toString(),"sessionId",r.source().session().id().toString(),"deviceEpoch",r.source().session().epoch(),
            "attemptId",r.execution().attempt().toString(),"attemptEpoch",1,"podUid",r.execution().pod().podUid().toString()));
        for(var entry:Map.of("request.json",json.canonical(request),"device.token",token(r.source()),
                "runner.token",runnerTokens.issue(runtimes.byAttempt(r.execution().attempt()).orElseThrow()),"pod.token","pod-proof-fixture").entrySet()){
            var path=root.resolve(entry.getKey());Files.writeString(path,entry.getValue());Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        String python=System.getenv().getOrDefault("EDGEAI_STREAM_PYTHON","python3");
        var process=new ProcessBuilder(python,"-W","error::ResourceWarning","src/test/fixtures/stream_sdk_probe.py",root.toString())
            .redirectOutput(root.resolve("probe.log").toFile()).redirectError(root.resolve("probe-error.log").toFile()).start();
        try{
            assertThat(process.waitFor(25,TimeUnit.SECONDS)).as("Actual Python SDK process completed").isTrue();
            assertThat(process.exitValue()).as("Actual Python SDK probe status: %s",Files.readString(root.resolve("probe.log"))).isZero();
            assertThat(Files.readString(root.resolve("probe.log")).equals("PASS actual Spring/Python TLS stream SDK calculation and processing acknowledgement\n")).as("SDK success marker without private output").isTrue();
            assertThat(Files.size(root.resolve("probe-error.log"))).as("No SDK warnings or private error output").isZero();
        }finally{
            if(process.isAlive()){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}
        }
    }
    @Test void staleSessionAndCancelledRunCannotUsePreviouslyAuthenticatedPrincipals()throws Exception {
        var r=route(120);String old=token(r.source());devices.openSession(r.source().device().id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString())));
        device(r.source(),old,identity(r),401);
        mvc.perform(post(tokenPath(r.source())).with(user("manager")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isConflict());
        assertThatThrownBy(()->bindings.device(new DeviceStreamPrincipal(r.source().device().id(),r.source().session().id(),1),json.canonical(identity(r)))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        var q=route(120);String token=token(q.source());runs.cancelRun(q.execution().run(),"{}");device(q.source(),token,identity(q),409);
        assertThatThrownBy(()->bindings.runner(new RunnerPrincipal(q.execution().attempt(),1,q.execution().pod()),json.canonical(runnerBody(q)))).isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        runner(q.execution(),"streams",runnerBody(q),409);
    }
    @Test void runnerRequiresPodProofMatchingAttemptAndCurrentBinding()throws Exception {
        var r=route(120);var other=route(120);String path="/internal/v1/attempts/"+r.execution().attempt()+"/streams";
        var token=runnerTokens.issue(runtimes.byAttempt(r.execution().attempt()).orElseThrow());
        mvc.perform(post(path).with(user("manager")).with(csrf()).contentType("application/json").content(json.canonical(runnerBody(r)))).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer "+token).header("X-EdgeAI-Pod-Token","wrong").contentType("application/json").content(json.canonical(runnerBody(r)))).andExpect(status().isUnauthorized());
        runner(r.execution(),"streams",Map.of("epoch",1,"podUid",UUID.randomUUID().toString(),"generationId",r.permission().generation().id().toString()),409);
        runner(other.execution(),"streams",Map.of("epoch",1,"podUid",other.execution().pod().podUid().toString(),"generationId",r.permission().generation().id().toString()),409);
        var mqtt=(Map<?,?>)device(r.source(),token(r.source()),identity(r),200).get("mqtt");
        mvc.perform(post(path).header("Authorization","Bearer "+mqtt.get("password")).header("X-EdgeAI-Pod-Token","pod-proof-fixture").contentType("application/json").content(json.canonical(runnerBody(r)))).andExpect(status().isUnauthorized());
    }
    @Test void expiredLeaseAndReleasedDeviceAreRejectedWithoutExtendingTheGeneration()throws Exception {
        var r=route(5);String token=token(r.source());device(r.source(),token,identity(r),200);
        until(()->!Instant.now().isBefore(r.permission().generation().leaseUntil()));device(r.source(),token,identity(r),409);
        assertThat(routeStore.generation(r.permission().generation().id()).orElseThrow().leaseUntil()).isEqualTo(r.permission().generation().leaseUntil());
        var q=route(120);String secret=token(q.source());devices.release(q.source().device().id());device(q.source(),secret,identity(q),401);
    }
    private Map<?,?> heartbeat(Route r,String token,long sequence,int expected)throws Exception {
        var response=mvc.perform(post(devicePath(r.source())+"/heartbeat").header("Authorization","Bearer "+token)
            .contentType("application/json").content(json.canonical(Map.of("epoch",1,"generationId",r.permission().generation().id().toString(),"sequence",sequence))))
            .andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        return response.isBlank()?Map.of():document(response);
    }
    private Object runnerHeartbeatBody(Route r,long sequence){return Map.of("epoch",1,"podUid",r.execution().pod().podUid().toString(),"generationId",r.permission().generation().id().toString(),"sequence",sequence);}
    @Test void authenticatedHeartbeatReplaysAndRuntimeFailuresPreserveStoredObservation()throws Exception {
        var r=route(120);var id=r.permission().generation().id();String secret=token(r.source());
        assertThat(((Number)heartbeat(r,secret,0,200).get("sequence")).longValue()).isZero();
        heartbeat(r,secret,1,200);var one=routeStore.heartbeat(id);heartbeat(r,secret,1,200);heartbeat(r,secret,3,409);
        assertThat(routeStore.heartbeat(id)).isEqualTo(one);assertThat(routeStore.generation(id).orElseThrow().leaseUntil()).isEqualTo(r.permission().generation().leaseUntil());
        var result=runner(r.execution(),"streams/heartbeat",runnerHeartbeatBody(r,1),200);
        assertThat(((Number)result.get("sequence")).longValue()).isEqualTo(1);var both=routeStore.heartbeat(id);
        assertThat(routeStore.generation(id).orElseThrow().leaseUntil()).isEqualTo(one.producerSeen().plusSeconds(120));
        runner(r.execution(),"streams/heartbeat",runnerHeartbeatBody(r,1),200);assertThat(routeStore.heartbeat(id)).isEqualTo(both);
        var foreignPod=new RuntimePod(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"fixture-node");
        assertThatThrownBy(()->bindings.runnerHeartbeat(new RunnerPrincipal(r.execution().attempt(),1,foreignPod),
            json.canonical(Map.of("epoch",1,"podUid",foreignPod.podUid().toString(),"generationId",id.toString(),"sequence",2))))
            .isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class);
        assertThat(routeStore.heartbeat(id)).isEqualTo(both);
        heartbeat(r,secret,2,200);heartbeat(r,secret,1,409);var previous=routeStore.heartbeat(id);
        var other=route(120);heartbeat(r,token(other.source()),3,401);assertThat(routeStore.heartbeat(id)).isEqualTo(previous);
        runs.cancelRun(r.execution().run(),"{}");heartbeat(r,secret,3,409);runner(r.execution(),"streams/heartbeat",runnerHeartbeatBody(r,2),409);
        assertThat(routeStore.heartbeat(id)).isEqualTo(previous);
    }
    @Test void oneSidedHttpHeartbeatExpiresAndWorkerRevokesTheBrokerGeneration()throws Exception {
        var r=route(5);String secret=token(r.source());var id=r.permission().generation().id();
        heartbeat(r,secret,1,200);heartbeat(r,secret,2,200);
        assertThat(routeStore.generation(id).orElseThrow().leaseUntil()).isEqualTo(r.permission().generation().leaseUntil());
        until(()->routeStore.generation(id).orElseThrow().closedAt()!=null);
        heartbeat(r,secret,3,409);runner(r.execution(),"streams/heartbeat",runnerHeartbeatBody(r,1),409);
        assertThat(routeStore.generation(id).orElseThrow().fenceReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(routeStore.heartbeat(id).consumerSequence()).isZero();
    }
    private MqttClient client(Map<?,?> binding)throws Exception {
        var mqtt=(Map<?,?>)binding.get("mqtt");var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(((String)mqtt.get("caPem")).getBytes(StandardCharsets.US_ASCII)));int i=0;for(var c:certificates)store.setCertificateEntry("ca-"+i++,c);
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);var tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
        var client=new MqttClient("ssl://"+mqtt.get("host")+":"+mqtt.get("port"),(String)mqtt.get("clientId"),new MemoryPersistence());clients.add(client);client.setTimeToWait(3000);
        var options=new MqttConnectionOptions();options.setUserName((String)mqtt.get("username"));options.setPassword(((String)mqtt.get("password")).getBytes(StandardCharsets.US_ASCII));options.setSocketFactory(tls.getSocketFactory());
        options.setConnectionTimeout(3);options.setCleanStart(true);options.setSessionExpiryInterval(0L);client.connect(options);return client;
    }
    private Map<?,?> wire(java.net.http.HttpClient http,String method,String path,Object value,Map<String,String> headers)throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+apiPort+path)).timeout(Duration.ofSeconds(5));
        headers.forEach(request::header);
        if(method.equals("GET"))request.GET();else request.header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.canonical(value)));
        var reply=http.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(reply.statusCode()).isEqualTo(200);assertThat(reply.headers().firstValue("Cache-Control").orElse("")).contains("no-store");return document(reply.body());
    }
    private void until(java.util.function.BooleanSupplier condition)throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();while(!condition.getAsBoolean() && System.nanoTime()<deadline)Thread.sleep(30);assertThat(condition.getAsBoolean()).isTrue();}
    private static final class Fixture implements AutoCloseable {
        final Path root;final Process process;final int port;
        Fixture(){try{
            root=Files.createTempDirectory("edgeai-stream-http-");root.toFile().deleteOnExit();
            for(String name:List.of("device.key","runner.key")){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Files.writeString(root.resolve(name),HexFormat.of().formatHex(key));Files.setPosixFilePermissions(root.resolve(name),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}
            process=new ProcessBuilder("python3","src/test/fixtures/stream_broker.py",root.toString()).redirectError(root.resolve("fixture-error.log").toFile()).start();
            var reader=process.inputReader();long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(!reader.ready() && process.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
            if(!reader.ready()){process.destroy();throw new IllegalStateException("Isolated stream broker unavailable");}port=Integer.parseInt(reader.readLine());
        }catch(Exception e){throw new IllegalStateException("Stream HTTP fixture startup failed; private details suppressed");}}
        String file(String name){return root.resolve(name).toString();}
        public void close()throws Exception {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}
            try(var paths=Files.walk(root)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
