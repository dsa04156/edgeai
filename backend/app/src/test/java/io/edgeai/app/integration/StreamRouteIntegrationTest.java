package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.RouteGeneration.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL state, constraints and locks. Broker receipts and RUNNING Attempts are fixtures;
 * no MQTT authorization, Pod execution or public STREAM acceptance is asserted by these tests. */
@SpringBootTest
@Import(StreamRouteIntegrationTest.TimeConfiguration.class)
class StreamRouteIntegrationTest {
    static class TestClock extends Clock {
        private final AtomicLong offset=new AtomicLong();
        public Instant instant(){return Instant.now().plusSeconds(offset.get());}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
    }
    @TestConfiguration static class TimeConfiguration{@Bean @Primary TestClock streamClock(){return new TestClock();}}
    @Autowired TestClock clock;
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowRepository definitions;
    @Autowired DeviceService devices;
    @Autowired DeviceRepository deviceRepository;
    @Autowired VirtualDeviceService virtualDevices;
    @Autowired VirtualDeviceRepository vds;
    @Autowired ExecutionRepository executions;
    @Autowired ExecutionService executionApi;
    @Autowired DataRouteService service;
    @Autowired DataRouteRepository routes;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private final JsonDocuments json=new JsonDocuments();
    private static final String BROKER="sha256:"+"a".repeat(64);
    private static final String OTHER="sha256:"+"b".repeat(64);
    private record Fixture(WorkflowRun run,Device device,DeviceSession session,Map<String,Task> tasks,Map<String,Actor> actors){}
    @BeforeEach void resetClock(){clock.offset.set(0);}
    private <T>T transaction(java.util.function.Supplier<T> action){return new TransactionTemplate(transactions).execute(s->action.get());}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,json.canonical(Map.of("key","stream-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    private Fixture fixture()throws Exception{return fixture(false);}
    private Fixture fixture(boolean vd)throws Exception {
        var spec=new HashMap<String,Object>();
        ((Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")))).forEach((k,v)->spec.put((String)k,v));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",262144,"required",true)));
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,spec);
        UUID dp=publish(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var device=devices.create(json.canonical(Map.of("key","stream-"+UUID.randomUUID(),"displayName","Stream source fixture","profileVersionId",dp.toString(),"sourceMode","SYNTHETIC"))).value();
        var session=devices.openSession(device.id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();
        UUID vdId=null;
        if(vd){
            UUID vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",sp.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),
                "runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
            vdId=virtualDevices.create(json.canonical(Map.of("key","stream-"+UUID.randomUUID(),"displayName","Stream lock fixture","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value().id();
        }
        var workflow=workflows.create(json.canonical(Map.of("key","stream-"+UUID.randomUUID(),"displayName","Stream fixture"))).value();
        var nodes=List.of("source","sink","device").stream().map(k->Map.of("key",k,"serviceProfileVersionId",sp.toString(),"parameters",Map.of())).toList();
        var edge=Map.of("fromTask","source","fromPort","output","toTask","sink","toPort","input","mode","STREAM");
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",nodes,"dependencies",List.of(edge)))).value();
        var now=clock.instant();
        var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("stream-fixture",Map.of("version",version.id().toString())),vd?"VD":"AUTO",null,"{}",RetryPolicy.disabled(),null,"PENDING",now,now,null,vdId);
        transaction(()->{
            if(run.vdId()!=null)vds.find(run.vdId(),true).orElseThrow();
            assertThat(executions.create(run)).isTrue();
            // Explicit fixture: the public runtime still rejects STREAM until the data plane is connected.
            executions.initialize(run,definitions.definitions(version.id()),Set.of("source","sink","device"));return null;
        });
        var tasks=new HashMap<String,Task>();var actors=new HashMap<String,Actor>();
        for(var task:executions.tasks(run.id())){tasks.put(task.key(),task);var a=executions.attempts(task.id()).getFirst();actors.put(task.key(),new Actor(a.id(),a.epoch()));}
        return new Fixture(run,device,session,tasks,actors);
    }
    private DataRoute taskRoute(Fixture f){return service.fromTask(f.run().id(),f.tasks().get("source").id(),"output",f.tasks().get("sink").id(),"input",4096);}
    private DataRoute deviceRoute(Fixture f){return service.fromDevice(f.run().id(),f.device().id(),"samples",f.tasks().get("device").id(),"input",4096);}
    private Actor session(Fixture f){return new Actor(f.session().id(),f.session().epoch());}
    private RouteGeneration prepare(DataRoute route,Actor producer,Actor consumer){return service.prepare(route.id(),UUID.randomUUID(),producer,consumer,BROKER,60);}
    private BrokerReceipt receipt(RouteGeneration g){return new BrokerReceipt(g.id(),g.brokerDigest(),g.policyDigest());}
    private RouteGeneration activate(RouteGeneration g){return service.activate(receipt(g));}
    private void running(Fixture f){transaction(()->{
        executions.run(f.run().id(),true).orElseThrow();
        jdbc.update("UPDATE edgeai.workflow_run SET state='RUNNING' WHERE id=?",f.run().id());
        jdbc.update("UPDATE edgeai.task SET state='RUNNING' WHERE run_id=?",f.run().id());
        jdbc.update("UPDATE edgeai.task_attempt SET state='RUNNING' WHERE task_id IN (SELECT id FROM edgeai.task WHERE run_id=?) AND state='QUEUED'",f.run().id());return null;
    });}
    private Actor retry(Fixture f,String key){return transaction(()->{
        executions.run(f.run().id(),true).orElseThrow();var task=f.tasks().get(key);
        jdbc.update("UPDATE edgeai.task_attempt SET state='FAILED' WHERE task_id=? AND state='RUNNING'",task.id());
        var next=executions.startRetry(task.id(),clock.instant());return new Actor(next.id(),next.epoch());
    });}
    private void conflict(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(ControlPlaneException.class,e->{assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo(code);});}
    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);}

    @Test void logicalRoutesPinPublishedEdgesAndDeviceIdentity()throws Exception {
        var f=fixture();var route=taskRoute(f);var device=deviceRoute(f);
        assertThat(taskRoute(f)).isEqualTo(route);assertThat(deviceRoute(f)).isEqualTo(device);
        assertThat(device.sourceProfileVersionId()).isEqualTo(f.device().profileVersionId());assertThat(device.sourceMode()).isEqualTo("SYNTHETIC");
        assertThat(routes.forRun(f.run().id(),10,0)).containsExactly(route,device);
        assertThat(routes.forRun(f.run().id(),1,1)).containsExactly(device);
        conflict("STREAM_INPUT_BOUND",()->service.fromDevice(f.run().id(),f.device().id(),"different",f.tasks().get("device").id(),"input",4096));
        assertThatThrownBy(()->service.fromDevice(f.run().id(),f.device().id(),"samples",f.tasks().get("sink").id(),"input",4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.fromTask(f.run().id(),f.tasks().get("source").id(),"absent",f.tasks().get("sink").id(),"input",4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.fromTask(f.run().id(),f.tasks().get("source").id(),"output",f.tasks().get("device").id(),"input",4096)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.fromTask(f.run().id(),f.tasks().get("source").id(),"output",f.tasks().get("sink").id(),"input",262145)).isInstanceOf(IllegalArgumentException.class);
        var other=fixture();conflict("STREAM_FOREIGN_TASK",()->service.fromTask(f.run().id(),other.tasks().get("source").id(),"output",f.tasks().get("sink").id(),"input",4096));
        invalid(()->jdbc.update("UPDATE edgeai.data_route SET source_port='changed' WHERE id=?",route.id()));
        invalid(()->jdbc.update("DELETE FROM edgeai.data_route WHERE id=?",route.id()));
    }
    @Test void concurrentReplayedAndCompetingPrepareRequestsPreserveOneGeneration()throws Exception {
        var f=fixture();var route=taskRoute(f);var request=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);
            var jobs=IntStream.range(0,8).mapToObj(i->pool.submit(()->{start.await();return service.prepare(route.id(),request,f.actors().get("source"),f.actors().get("sink"),BROKER,60);})).toList();start.countDown();
            RouteGeneration first=jobs.getFirst().get(15,TimeUnit.SECONDS);for(var job:jobs)assertThat(job.get(15,TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(routes.history(route.id(),10,0)).containsExactly(first);
            conflict("STREAM_REQUEST_CONFLICT",()->service.prepare(route.id(),request,f.actors().get("source"),f.actors().get("sink"),OTHER,60));
            var device=deviceRoute(f);var gate=new CountDownLatch(1);
            var contenders=IntStream.range(0,8).mapToObj(i->pool.submit(()->{gate.await();try{prepare(device,session(f),f.actors().get("device"));return "CREATED";}catch(ControlPlaneException e){return e.code();}})).toList();gate.countDown();
            var outcomes=new ArrayList<String>();for(var job:contenders)outcomes.add(job.get(15,TimeUnit.SECONDS));
            assertThat(Collections.frequency(outcomes,"CREATED")).isEqualTo(1);assertThat(Collections.frequency(outcomes,"STREAM_REVOCATION_PENDING")).isEqualTo(7);
            assertThat(routes.history(device.id(),10,0)).hasSize(1);
        }
    }
    @Test void activationRequiresMatchingReceiptAndDataGuardRequiresRunningActors()throws Exception {
        var f=fixture();var route=taskRoute(f);var producer=f.actors().get("source");var consumer=f.actors().get("sink");var g=prepare(route,producer,consumer);
        assertThat(g.state()).isEqualTo("PREPARING");assertThat(service.accepts(g.id(),producer,consumer)).isFalse();
        conflict("STREAM_BROKER_MISMATCH",()->service.activate(new BrokerReceipt(g.id(),OTHER,g.policyDigest())));
        conflict("STREAM_BROKER_MISMATCH",()->service.activate(new BrokerReceipt(g.id(),BROKER,OTHER)));
        conflict("STREAM_FENCE_REQUIRED",()->service.revoked(receipt(g)));
        var active=activate(g);assertThat(active.state()).isEqualTo("ACTIVE");assertThat(activate(g)).isEqualTo(active);
        assertThat(service.accepts(g.id(),producer,consumer)).isFalse();running(f);
        assertThat(service.accepts(g.id(),producer,consumer)).isTrue();assertThat(service.accepts(g.id(),new Actor(producer.id(),producer.epoch()+1),consumer)).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM edgeai.route_generation WHERE id=?",String.class,g.id())).isEqualTo("ACTIVE");
    }
    @Test void fencingWaitsForRevocationAndLateGrantCannotResurrectClosedHistory()throws Exception {
        var f=fixture();running(f);var route=taskRoute(f);var producer=f.actors().get("source");var consumer=f.actors().get("sink");var g=activate(prepare(route,producer,consumer));
        var fenced=service.fence(g.id(),"REPLACED");assertThat(service.fence(g.id(),"FAILED")).isEqualTo(fenced);
        assertThat(service.accepts(g.id(),producer,consumer)).isFalse();conflict("STREAM_GENERATION_FENCED",()->activate(g));
        conflict("STREAM_GENERATION_FENCED",()->service.renew(g.id(),producer,consumer,60));
        conflict("STREAM_REVOCATION_PENDING",()->prepare(route,producer,consumer));
        conflict("STREAM_BROKER_MISMATCH",()->service.revoked(new BrokerReceipt(g.id(),OTHER,g.policyDigest())));
        var closed=service.revoked(receipt(g));assertThat(service.revoked(receipt(g))).isEqualTo(closed);assertThat(closed.state()).isEqualTo("CLOSED");
        assertThat(service.prepare(route.id(),g.id(),producer,consumer,BROKER,60)).isEqualTo(closed);
        var next=activate(prepare(route,producer,consumer));assertThat(next.routeId()).isEqualTo(route.id());assertThat(next.generation()).isEqualTo(2);
        assertThat(service.accepts(next.id(),producer,consumer)).isTrue();assertThat(service.accepts(g.id(),producer,consumer)).isFalse();
        assertThat(routes.history(route.id(),10,0)).containsExactly(next,closed);
        invalid(()->jdbc.update("UPDATE edgeai.route_generation SET fenced_at=NULL,fence_reason=NULL,closed_at=NULL WHERE id=?",g.id()));
        invalid(()->jdbc.update("DELETE FROM edgeai.route_generation WHERE id=?",g.id()));
    }
    @Test void renewalCannotShrinkLeaseChangeActorOrReviveExpiry()throws Exception {
        var f=fixture();running(f);var route=taskRoute(f);var producer=f.actors().get("source");var consumer=f.actors().get("sink");var g=activate(prepare(route,producer,consumer));
        assertThat(service.renew(g.id(),producer,consumer,5)).isEqualTo(g);
        clock.offset.addAndGet(20);var renewed=service.renew(g.id(),producer,consumer,60);assertThat(renewed.leaseUntil()).isAfter(g.leaseUntil());
        conflict("STREAM_ACTOR_CHANGED",()->service.renew(g.id(),new Actor(UUID.randomUUID(),1),consumer,60));
        assertThatThrownBy(()->service.renew(g.id(),producer,consumer,121)).isInstanceOf(IllegalArgumentException.class);
        clock.offset.addAndGet(61);assertThat(service.accepts(g.id(),producer,consumer)).isFalse();
        conflict("STREAM_GENERATION_FENCED",()->service.renew(g.id(),producer,consumer,60));conflict("STREAM_GENERATION_FENCED",()->activate(g));
        assertThat(service.reconcile(g.id()).fenceReason()).isEqualTo("LEASE_EXPIRED");assertThat(routes.open(route.id())).isPresent();
    }
    @Test void expiredPreparingGenerationNeverAcceptsLateBrokerGrant()throws Exception {
        var f=fixture();var route=deviceRoute(f);var g=prepare(route,session(f),f.actors().get("device"));clock.offset.addAndGet(61);
        conflict("STREAM_GENERATION_FENCED",()->activate(g));assertThat(service.reconcile(g.id()).fenceReason()).isEqualTo("LEASE_EXPIRED");
        conflict("STREAM_REVOCATION_PENDING",()->prepare(route,session(f),f.actors().get("device")));
        service.revoked(receipt(g));assertThat(prepare(route,session(f),f.actors().get("device")).generation()).isEqualTo(2);
    }
    @Test void deviceReconnectAndReleaseInvalidateAuthorityBeforeReconciliation()throws Exception {
        var f=fixture();running(f);var route=deviceRoute(f);var consumer=f.actors().get("device");var g=activate(prepare(route,session(f),consumer));
        assertThat(service.accepts(g.id(),session(f),consumer)).isTrue();
        var nextSession=devices.openSession(f.device().id(),json.canonical(Map.of("bootId",UUID.randomUUID().toString()))).value();var producer=new Actor(nextSession.id(),nextSession.epoch());
        assertThat(producer.epoch()).isEqualTo(2);assertThat(service.accepts(g.id(),session(f),consumer)).isFalse();
        conflict("PRODUCER_CHANGED",()->service.renew(g.id(),session(f),consumer,60));assertThat(service.reconcile(g.id()).fenceReason()).isEqualTo("PRODUCER_CHANGED");
        service.revoked(receipt(g));conflict("PRODUCER_CHANGED",()->prepare(route,session(f),consumer));
        var next=activate(prepare(route,producer,consumer));assertThat(service.accepts(next.id(),producer,consumer)).isTrue();
        devices.release(f.device().id());assertThat(service.accepts(next.id(),producer,consumer)).isFalse();assertThat(service.reconcile(next.id()).fenceReason()).isEqualTo("PRODUCER_CHANGED");
        assertThat(routes.route(route.id(),false).orElseThrow()).isEqualTo(route);
    }
    @Test void taskRetriesRequireNewActorsAndPreserveLogicalRoute()throws Exception {
        var f=fixture();running(f);var route=taskRoute(f);var producer=f.actors().get("source");var consumer=f.actors().get("sink");var first=activate(prepare(route,producer,consumer));
        var nextProducer=retry(f,"source");assertThat(nextProducer.epoch()).isEqualTo(2);assertThat(service.accepts(first.id(),producer,consumer)).isFalse();
        assertThat(service.reconcile(first.id()).fenceReason()).isEqualTo("PRODUCER_CHANGED");service.revoked(receipt(first));
        conflict("PRODUCER_CHANGED",()->prepare(route,producer,consumer));
        var second=activate(prepare(route,nextProducer,consumer));running(f);assertThat(service.accepts(second.id(),nextProducer,consumer)).isTrue();
        var nextConsumer=retry(f,"sink");assertThat(service.accepts(second.id(),nextProducer,consumer)).isFalse();
        assertThat(service.reconcile(second.id()).fenceReason()).isEqualTo("CONSUMER_CHANGED");service.revoked(receipt(second));
        var third=activate(prepare(route,nextProducer,nextConsumer));running(f);assertThat(service.accepts(third.id(),nextProducer,nextConsumer)).isTrue();assertThat(third.generation()).isEqualTo(3);
    }
    @Test void publicRunCancellationImmediatelyRemovesDataAuthority()throws Exception {
        var f=fixture();running(f);var route=taskRoute(f);var g=activate(prepare(route,f.actors().get("source"),f.actors().get("sink")));
        executionApi.cancelRun(f.run().id(),"{}");assertThat(service.accepts(g.id(),g.producer(),g.consumer())).isFalse();
        assertThat(service.reconcile(g.id()).fenceReason()).isEqualTo("CANCELLED");
        service.revoked(receipt(g));conflict("CANCELLED",()->prepare(route,g.producer(),g.consumer()));
    }
    @Test void databaseRejectsForeignActorsSkippedGenerationsAndHistoryMutation()throws Exception {
        var f=fixture();var route=taskRoute(f);var producer=f.actors().get("source");var consumer=f.actors().get("sink");var now=clock.instant();
        for(var bad:List.of(new Actor(producer.id(),2),consumer,new Actor(UUID.randomUUID(),1))){
            var value=new RouteGeneration(UUID.randomUUID(),route.id(),1,bad,consumer,BROKER,OTHER,OTHER,now,now,now.plusSeconds(60),null,null,null,null);
            invalid(()->transaction(()->{routes.prepare(route,value);return null;}));
        }
        var skipped=new RouteGeneration(UUID.randomUUID(),route.id(),2,producer,consumer,BROKER,OTHER,OTHER,now,now,now.plusSeconds(60),null,null,null,null);
        invalid(()->transaction(()->{routes.prepare(route,skipped);return null;}));
        var g=prepare(route,producer,consumer);
        invalid(()->transaction(()->{routes.prepare(route,skipped);return null;}));
        for(var change:List.of("generation=generation+1","broker_digest='"+OTHER+"'","policy_digest='"+OTHER+"'","producer_epoch=producer_epoch+1","source_task_id=NULL","producer_session_id='"+f.session().id()+"'","closed_at=updated_at"))
            invalid(()->jdbc.update("UPDATE edgeai.route_generation SET "+change+" WHERE id=?",g.id()));
        var device=deviceRoute(f);var foreignSession=new RouteGeneration(UUID.randomUUID(),device.id(),1,new Actor(f.session().id(),2),f.actors().get("device"),BROKER,OTHER,OTHER,now,now,now.plusSeconds(60),null,null,null,null);
        invalid(()->transaction(()->{routes.prepare(device,foreignSession);return null;}));
        for(var table:List.of("data_route","route_generation"))invalid(()->transaction(()->{jdbc.execute("TRUNCATE edgeai."+table+" CASCADE");return null;}));
        assertThat(routes.generation(g.id()).orElseThrow()).isEqualTo(g);
    }
    @Test void databaseRejectsUnpublishedEdgesAndInvalidDeviceSourceUnion()throws Exception {
        var f=fixture();var route=taskRoute(f);var now=clock.instant();
        var unpublished=new DataRoute(UUID.randomUUID(),f.run().id(),f.tasks().get("source").id(),null,route.sourceProfileVersionId(),null,"output",f.tasks().get("device").id(),"input","application/json",4096,now);
        invalid(()->transaction(()->{routes.create(unpublished);return null;}));
        invalid(()->jdbc.update("""
            INSERT INTO edgeai.data_route(id,run_id,source_device_id,source_profile_version_id,source_port,consumer_task_id,consumer_port,media_type,max_payload_bytes,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,now())
            """,UUID.randomUUID(),f.run().id(),f.device().id(),f.device().profileVersionId(),"samples",f.tasks().get("device").id(),"input","application/json",4096));
        var inactive=deviceRoute(f);devices.release(f.device().id());
        conflict("PRODUCER_CHANGED",()->prepare(inactive,session(f),f.actors().get("device")));
    }
    @Test void vdDeviceRunLockOrderDoesNotInvertRegistryUpdates()throws Exception {
        var f=fixture(true);var route=deviceRoute(f);var pid=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var job=transaction(()->{
                vds.find(f.run().vdId(),true).orElseThrow();
                var future=pool.submit(()->transaction(()->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return prepare(route,session(f),f.actors().get("device"));}));
                long end=System.nanoTime()+Duration.ofSeconds(8).toNanos();boolean waiting=false;
                while(System.nanoTime()<end){
                    if(pid.get()!=0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get()))){waiting=true;break;}
                    try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }
                assertThat(waiting).isTrue();
                jdbc.queryForObject("SELECT id FROM edgeai.device WHERE id=? FOR UPDATE NOWAIT",UUID.class,f.device().id());
                jdbc.queryForObject("SELECT id FROM edgeai.workflow_run WHERE id=? FOR UPDATE NOWAIT",UUID.class,f.run().id());return future;
            });
            assertThat(job.get(10,TimeUnit.SECONDS).state()).isEqualTo("PREPARING");
        }
    }
}
