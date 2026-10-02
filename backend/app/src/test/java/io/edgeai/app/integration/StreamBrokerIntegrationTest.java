package io.edgeai.app.integration;

import io.edgeai.adapters.stream.MosquittoStreamBroker;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.RouteGeneration.*;
import io.edgeai.domain.stream.StreamBrokerGateway.*;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import java.io.*;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual TLS MQTT and dynamic ACLs, plus real DB authority transitions. Task RUNNING is a fixture;
 * scheduling, Pod/Device authentication, worker recovery and Runner workload remain separate gates. */
@SpringBootTest
class StreamBrokerIntegrationTest {
    @Autowired ProfileService profiles;
    @Autowired DeviceService devices;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowRepository definitions;
    @Autowired ExecutionRepository executions;
    @Autowired DataRouteService routes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    private Process broker;private BufferedReader lines;private int port;private MosquittoStreamBroker gateway;
    private final List<MqttClient> peers=new ArrayList<>();
    private static final String DIGEST="sha256:"+"a".repeat(64),POLICY="sha256:"+"b".repeat(64);
    @BeforeEach void start()throws Exception {
        broker=new ProcessBuilder("python3","src/test/fixtures/stream_broker.py",root.toString()).redirectError(root.resolve("fixture-error.log").toFile()).start();
        lines=broker.inputReader();port=Integer.parseInt(line());gateway=gateway("localhost","server.crt");
    }
    private String line()throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<until){if(lines.ready()){String l=lines.readLine();if(l!=null)return l;}if(!broker.isAlive())break;Thread.sleep(20);}
        throw new AssertionError("Isolated broker did not become ready; private diagnostics suppressed");
    }
    private MosquittoStreamBroker gateway(String host,String ca){return new MosquittoStreamBroker("ssl://"+host+":"+port,"edgeai-admin",root.resolve("admin.password"),root.resolve(ca),root.resolve("principal.key"),DIGEST,Clock.systemUTC());}
    @AfterEach void close()throws Exception {
        for(var peer:peers){try{peer.disconnectForcibly(0,100,false);}catch(Exception ignored){}try{peer.close(true);}catch(Exception ignored){}}
        if(broker!=null){broker.destroy();if(!broker.waitFor(5,TimeUnit.SECONDS)){broker.destroyForcibly();assertThat(broker.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    private Permission permission(){return permission(new Principal("DEVICE",new Actor(UUID.randomUUID(),1)),new Actor(UUID.randomUUID(),1));}
    private Permission permission(Principal producer,Actor consumer){
        var now=Instant.now();var route=new DataRoute(UUID.randomUUID(),UUID.randomUUID(),producer.kind().equals("TASK")?UUID.randomUUID():null,
            producer.kind().equals("DEVICE")?UUID.randomUUID():null,UUID.randomUUID(),producer.kind().equals("DEVICE")?"SYNTHETIC":null,"output",UUID.randomUUID(),"input","application/json",4096,now);
        var generation=new RouteGeneration(UUID.randomUUID(),route.id(),1,producer.actor(),consumer,DIGEST,POLICY,POLICY,now,now,now.plusSeconds(120),null,null,null,null);
        return new Permission(route,generation);
    }
    private SSLSocketFactory tls()throws Exception {
        var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
        try(var in=Files.newInputStream(root.resolve("server.crt"))){store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);var context=SSLContext.getInstance("TLS");context.init(null,trust.getTrustManagers(),null);return context.getSocketFactory();
    }
    private MqttClient peer(Principal principal)throws Exception {
        var credential=gateway.credential(principal);var client=new MqttClient("ssl://localhost:"+port,credential.username(),new MemoryPersistence());peers.add(client);client.setTimeToWait(3000);
        var options=new MqttConnectionOptions();options.setUserName(credential.username());options.setPassword(credential.password());options.setSocketFactory(tls());
        options.setCleanStart(true);options.setSessionExpiryInterval(0L);options.setAutomaticReconnect(false);options.setConnectionTimeout(3);client.connect(options);return client;
    }
    private BlockingQueue<byte[]> subscribe(MqttClient client,String topic)throws Exception {
        var queue=new ArrayBlockingQueue<byte[]>(8);var subscription=new MqttSubscription(topic,1);subscription.setRetainHandling(2);
        var reply=client.subscribe(new MqttSubscription[]{subscription},new IMqttMessageListener[]{(t,m)->queue.offer(m.getPayload())});
        assertThat(reply.getReasonCodes()).containsExactly(1);return queue;
    }
    private void received(BlockingQueue<byte[]> queue,String value)throws Exception{assertThat(queue.poll(3,TimeUnit.SECONDS)).isEqualTo(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void publish(MqttClient client,String topic,String value)throws Exception{client.publish(topic,value.getBytes(java.nio.charset.StandardCharsets.UTF_8),1,false);}
    private void reason(StreamBrokerException.Reason reason,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(StreamBrokerException.class,e->assertThat(e.reason()).isEqualTo(reason));}

    @Test void grantsExactDirectionalTopicsAndRevokesAlreadyConnectedPeers()throws Exception {
        var p=permission();assertThat(gateway.grant(p)).isEqualTo(p.receipt());assertThat(gateway.grant(p)).isEqualTo(p.receipt());
        var producer=peer(p.producer());var consumer=peer(p.consumer());var data=subscribe(consumer,p.topic("frames"));var acks=subscribe(producer,p.topic("acks"));
        assertThat(producer.subscribe("$CONTROL/dynamic-security/#",1).getReasonCodes()).containsExactly(135);
        publish(producer,p.topic("frames"),"7");received(data,"7");publish(consumer,p.topic("acks"),"1");received(acks,"1");
        assertThat(gateway.revoke(p)).isEqualTo(p.receipt());assertThat(gateway.revoke(p)).isEqualTo(p.receipt());
        reason(StreamBrokerException.Reason.REVOKED,()->gateway.grant(p));
        try{publish(producer,p.topic("frames"),"999");}catch(MqttException rejected){}
        assertThat(data.poll(400,TimeUnit.MILLISECONDS)).isNull();
        assertThat(consumer.subscribe(p.topic("frames"),1).getReasonCodes()).containsExactly(135);
    }
    @Test void rolesAllowTwoInputsToOneConsumerWithoutCrossRoutePublication()throws Exception {
        var consumerActor=new Actor(UUID.randomUUID(),1);var a=permission(new Principal("DEVICE",new Actor(UUID.randomUUID(),1)),consumerActor);
        var b=permission(new Principal("DEVICE",new Actor(UUID.randomUUID(),1)),consumerActor);gateway.grant(a);gateway.grant(b);
        var producerA=peer(a.producer());var producerB=peer(b.producer());var consumer=peer(a.consumer());
        var first=subscribe(consumer,a.topic("frames"));var second=subscribe(consumer,b.topic("frames"));
        publish(producerA,a.topic("frames"),"4");publish(producerB,b.topic("frames"),"5");received(first,"4");received(second,"5");
        assertThat(producerA.subscribe("edgeai/streams/#",1).getReasonCodes()).containsExactly(135);
        try{publish(producerA,b.topic("frames"),"999");}catch(MqttException rejected){}
        assertThat(second.poll(300,TimeUnit.MILLISECONDS)).isNull();
        gateway.revoke(a);publish(producerB,b.topic("frames"),"6");received(second,"6");
    }
    @Test void revokeBeforeGrantAndBrokerSigkillLeaveDurableTombstones()throws Exception {
        var p=permission();gateway.grant(p);gateway.revoke(p);reason(StreamBrokerException.Reason.REVOKED,()->gateway.grant(p));
        var neverGranted=permission();gateway.revoke(neverGranted);
        broker.outputWriter().write("restart\n");broker.outputWriter().flush();assertThat(Integer.parseInt(line())).isEqualTo(port);
        var restarted=gateway("localhost","server.crt");assertThat(restarted.revoke(p)).isEqualTo(p.receipt());
        reason(StreamBrokerException.Reason.REVOKED,()->restarted.grant(p));
        reason(StreamBrokerException.Reason.REVOKED,()->restarted.grant(neverGranted));
        assertThat(peer(p.producer()).subscribe(p.topic("acks"),1).getReasonCodes()).containsExactly(135);
        var next=permission();restarted.grant(next);var consumer=peer(next.consumer());var queue=subscribe(consumer,next.topic("frames"));publish(peer(next.producer()),next.topic("frames"),"11");received(queue,"11");
    }
    @Test void competingLateGrantsCannotRestoreRevokedGeneration()throws Exception {
        var p=permission();gateway.grant(p);
        try(var pool=Executors.newFixedThreadPool(4)){
            var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
            for(int i=0;i<3;i++)jobs.add(pool.submit(()->{gate.await();try{gateway.grant(p);}catch(StreamBrokerException e){assertThat(e.reason()).isEqualTo(StreamBrokerException.Reason.REVOKED);}return null;}));
            jobs.add(pool.submit(()->{gate.await();gateway.revoke(p);return null;}));gate.countDown();
            for(var job:jobs)job.get(20,TimeUnit.SECONDS);
        }
        reason(StreamBrokerException.Reason.REVOKED,()->gateway.grant(p));gateway.revoke(p);
    }
    @Test void wrongBrokerPolicyExpiredLeaseAndCredentialConfigurationFailClosed()throws Exception {
        var p=permission();gateway.grant(p);var g=p.generation();
        var foreign=new RouteGeneration(g.id(),g.routeId(),g.generation(),g.producer(),g.consumer(),DIGEST,"sha256:"+"c".repeat(64),POLICY,g.createdAt(),g.updatedAt(),g.leaseUntil(),null,null,null,null);
        reason(StreamBrokerException.Reason.CONFLICT,()->gateway.grant(new Permission(p.route(),foreign)));
        reason(StreamBrokerException.Reason.CONFLICT,()->gateway.revoke(new Permission(p.route(),foreign)));
        var expired=new MosquittoStreamBroker("ssl://localhost:"+port,"edgeai-admin",root.resolve("admin.password"),root.resolve("server.crt"),root.resolve("principal.key"),DIGEST,Clock.offset(Clock.systemUTC(),Duration.ofSeconds(121)));
        reason(StreamBrokerException.Reason.REVOKED,()->expired.grant(p));
        assertThat(gateway.credential(p.producer()).toString()).doesNotContain(new String(gateway.credential(p.producer()).password()));
        var originalKey=Files.readAllBytes(root.resolve("principal.key"));Files.writeString(root.resolve("principal.key"),"c".repeat(64));
        reason(StreamBrokerException.Reason.CONFIGURATION,()->gateway.credential(p.producer()));
        reason(StreamBrokerException.Reason.CONFLICT,()->gateway("localhost","server.crt").grant(p));
        Files.write(root.resolve("principal.key"),originalKey);
        Files.setPosixFilePermissions(root.resolve("principal.key"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        reason(StreamBrokerException.Reason.CONFIGURATION,()->gateway.credential(p.producer()));
    }
    @Test void tlsRejectsWrongCaWrongHostnameAndWrongAdminPassword()throws Exception {
        var p=permission();reason(StreamBrokerException.Reason.UNAVAILABLE,()->gateway("127.0.0.1","server.crt").grant(p));
        reason(StreamBrokerException.Reason.UNAVAILABLE,()->gateway("localhost","untrusted.crt").grant(p));
        Files.writeString(root.resolve("admin.password"),"wrong-private-fixture-password");reason(StreamBrokerException.Reason.UNAVAILABLE,()->gateway.grant(p));
    }
    @Test void silentTlsPeerTimesOutAndReleasesItsSocket()throws Exception {
        try(var silent=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){
            var candidate=new MosquittoStreamBroker("ssl://localhost:"+silent.getLocalPort(),"edgeai-admin",root.resolve("admin.password"),root.resolve("server.crt"),root.resolve("principal.key"),DIGEST,Clock.systemUTC());
            long before=sockets(),started=System.nanoTime();
            reason(StreamBrokerException.Reason.UNAVAILABLE,()->candidate.grant(permission()));
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(8));
            long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(sockets()>before && System.nanoTime()<end)Thread.sleep(20);
            assertThat(sockets()).isLessThanOrEqualTo(before);
        }
    }
    private long sockets()throws Exception {
        try(var paths=Files.list(Path.of("/proc/self/fd"))){return paths.filter(p->{try{return Files.readSymbolicLink(p).toString().startsWith("socket:");}catch(IOException e){return false;}}).count();}
    }
    @Test void actualDatabaseDeviceReconnectRevokesBrokerBeforeNextGenerationCanDeliver()throws Exception {
        var json=new JsonDocuments();var spec=new HashMap<String,Object>();
        ((Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")))).forEach((k,v)->spec.put((String)k,v));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",4096,"required",true)));
        var sp=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","broker-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var dp=profiles.publish(ProfileIdentity.Kind.DEVICE,json.canonical(Map.of("key","broker-"+UUID.randomUUID(),"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
        var device=devices.create(json.canonical(Map.of("key","broker-"+UUID.randomUUID(),"displayName","Broker database fixture","profileVersionId",dp.id().toString(),"sourceMode","SYNTHETIC"))).value();
        var session=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        var workflow=workflows.create(json.canonical(Map.of("key","broker-"+UUID.randomUUID(),"displayName","Broker database fixture"))).value();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",List.of(Map.of("key","sink","serviceProfileVersionId",sp.id().toString(),"parameters",Map.of())),"dependencies",List.of()))).value();
        var now=Instant.now();var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),POLICY,"AUTO",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now,null,null);
        new TransactionTemplate(transactions).execute(status->{
            assertThat(executions.create(run)).isTrue();executions.initialize(run,definitions.definitions(version.id()),Set.of("sink"));
            // Explicit execution-state fixture; no actual Pod or public STREAM claim.
            jdbc.update("UPDATE edgeai.task SET state='RUNNING' WHERE run_id=?",run.id());
            jdbc.update("UPDATE edgeai.task_attempt SET state='RUNNING' WHERE task_id IN (SELECT id FROM edgeai.task WHERE run_id=?)",run.id());return null;
        });
        var task=executions.tasks(run.id()).getFirst();var attempt=executions.attempts(task.id()).getFirst();var actor=new Actor(attempt.id(),attempt.epoch());
        var route=routes.fromDevice(run.id(),device.id(),"samples",task.id(),"input",4096);
        var g=routes.prepare(route.id(),UUID.randomUUID(),new Actor(session.id(),session.epoch()),actor,DIGEST,120);
        var p=new Permission(route,g);routes.activate(gateway.grant(p));assertThat(routes.accepts(g.id(),g.producer(),g.consumer())).isTrue();
        var source=peer(p.producer());var sink=peer(p.consumer());var data=subscribe(sink,p.topic("frames"));publish(source,p.topic("frames"),"before");received(data,"before");
        var nextSession=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        assertThat(routes.accepts(g.id(),g.producer(),g.consumer())).isFalse();assertThat(routes.reconcile(g.id()).fenceReason()).isEqualTo("PRODUCER_CHANGED");
        var nextActor=new Actor(nextSession.id(),nextSession.epoch());
        assertThatThrownBy(()->routes.prepare(route.id(),UUID.randomUUID(),nextActor,actor,DIGEST,120)).isInstanceOfSatisfying(io.edgeai.app.exception.ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("STREAM_REVOCATION_PENDING"));
        assertThat(routes.revoked(gateway.revoke(p)).state()).isEqualTo("CLOSED");
        var next=routes.prepare(route.id(),UUID.randomUUID(),nextActor,actor,DIGEST,120);assertThat(next.generation()).isEqualTo(2);
        var permission=new Permission(route,next);routes.activate(gateway.grant(permission));
        var newSink=peer(permission.consumer());var nextData=subscribe(newSink,permission.topic("frames"));
        try{publish(source,permission.topic("frames"),"stale");}catch(MqttException rejected){}
        publish(peer(permission.producer()),permission.topic("frames"),"after");received(nextData,"after");assertThat(nextData.poll(200,TimeUnit.MILLISECONDS)).isNull();
        assertThat(routes.accepts(next.id(),next.producer(),next.consumer())).isTrue();
        routes.fence(next.id(),"COMPLETED");routes.revoked(gateway.revoke(permission));
    }
}
