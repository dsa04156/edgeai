package io.edgeai.app.integration;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL lifecycle/races. Pod observations are fixtures, not Kubernetes acceptance. */
@SpringBootTest
@Import(VDLifecycleIntegrationTest.TimeConfiguration.class)
class VDLifecycleIntegrationTest {
    static final class TestClock extends Clock {
        private volatile Instant now=Instant.now();
        void set(Instant value){now=value;}
        void advance(int seconds){now=now.plusSeconds(seconds);}
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary TestClock vdTestClock(){return new TestClock();} }
    @Autowired TestClock clock;
    @Autowired VirtualDeviceService vds;
    @Autowired VDLifecycleService lifecycle;
    @Autowired DeviceService devices;
    @Autowired ProfileService profiles;
    @Autowired VDRuntimeRepository runtimes;
    @Autowired VirtualDeviceRepository vdRepository;
    @Autowired ProfileRepository profileRepository;
    @Autowired NodeRepository nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final JsonDocuments json=new JsonDocuments();
    private String encode(Object value){return json.canonical(value);}
    private String key(){return "vd-test-"+UUID.randomUUID();}
    @BeforeEach void time(){clock.set(Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS));}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key",key(),"version","1.0.0","spec",spec))).version().id();}
    private Device device(UUID profile){return devices.create(encode(Map.of("key",key(),"displayName","원본","profileVersionId",profile.toString(),"sourceMode","SYNTHETIC"))).value();}
    private record Fixture(VirtualDevice vd,Device a,Device b,RuntimeSettings settings) {}
    private Fixture fixture() throws Exception {
        UUID dp=publish(ProfileIdentity.Kind.DEVICE,Map.of("protocol","fixture"));
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        UUID vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","processing","serviceProfileVersionId",sp.toString(),
            "sources",Map.of("input",Map.of("deviceProfileVersionId",dp.toString(),"required",true,"sourceModes",List.of("SYNTHETIC"))),
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",2,"startupTimeoutSeconds",60,"drainTimeoutSeconds",5)));
        var a=device(dp);var b=device(dp);
        var vd=vds.create(encode(Map.of("key",key(),"displayName","가상 장치","profileVersionId",vp.toString(),
            "sources",List.of(Map.of("sourceKey","input","deviceId",a.id().toString())),"placement",Map.of("mode","AUTO")))).value();
        var settings=new RuntimeSettings("vd-test-"+UUID.randomUUID().toString().substring(0,12),"edgeai-runner",URI.create("http://edgeai-api.edgeai.svc:18080"),120);
        return new Fixture(vd,a,b,settings);
    }
    private VDOperation provision(Fixture f){return lifecycle.provision(f.vd().id(),f.vd().revision(),key(),f.settings(),false);}
    private void completeCreate(VDRuntime r) {
        for(int i=0;i<4;i++) {
            var cmd=runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)).orElseThrow();
            if(cmd.kind().equals("DELETE"))assertThat(lifecycle.get(cmd.runtimeId()).terminal()).isTrue();
            else { assertThat(cmd.runtimeId()).isEqualTo(r.id());assertThat(cmd.kind()).isEqualTo("CREATE"); }
            assertThat(runtimes.finishCommand(cmd.id(),cmd.leaseOwner(),clock.instant())).isTrue();
            if(cmd.kind().equals("CREATE"))return;
        }
        throw new AssertionError("Expected target CREATE command");
    }
    private VDRuntime ready(VDOperation op) {
        var r=lifecycle.get(op.targetRuntimeId());completeCreate(r);UUID pod=UUID.randomUUID(),node=UUID.randomUUID(),session=UUID.randomUUID();
        lifecycle.submitted(r.id(),pod);
        var first=lifecycle.attest(r.id(),pod,node,"worker-1",session,false,20);
        assertThat(first.ready(clock.instant())).isFalse();assertThat(runtimes.operation(op.id()).orElseThrow().state()).isEqualTo("RUNNING");
        var result=lifecycle.attest(r.id(),pod,node,"worker-1",session,true,20);
        assertThat(result.ready(clock.instant())).isTrue();assertThat(runtimes.operation(op.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
        return result;
    }
    private VirtualDevice update(Fixture f,String name,Device source) {
        var vd=vds.detail(f.vd().id()).vd();
        return vds.update(vd.id(),encode(Map.of("revision",vd.revision(),"displayName",name,"sources",List.of(Map.of("sourceKey","input","deviceId",source.id().toString())),"placement",Map.of("mode","AUTO"))));
    }
    private void error(Supplier<?> call,String code) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    @Test void sameKeyConcurrentProvisionCreatesOneRuntimeBindingAndCommandAndRequiresAttestedReadiness() throws Exception {
        var f=fixture();String key=key();
        var values=parallel(()->lifecycle.provision(f.vd().id(),0,key,f.settings(),false));
        assertThat(values.stream().map(VDOperation::id).distinct()).hasSize(1);
        assertThat(runtimes.history(f.vd().id(),100)).hasSize(1);assertThat(runtimes.bindings(f.vd().id(),100)).hasSize(1);
        var r=lifecycle.get(values.getFirst().targetRuntimeId());assertThat(r.generation()).isEqualTo(1);assertThat(r.observedState()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.vd_runtime_command WHERE runtime_id=?",Integer.class,r.id())).isEqualTo(1);
        var actual=ready(values.getFirst());
        assertThat(lifecycle.provision(f.vd().id(),0,key,f.settings(),false).id()).isEqualTo(values.getFirst().id());
        error(()->lifecycle.provision(f.vd().id(),0,key,f.settings(),true),"VD_OPERATION_CONFLICT");
        error(()->lifecycle.attest(actual.id(),actual.podUid(),actual.nodeUid(),actual.nodeName(),UUID.randomUUID(),true,20),"VD_RUNTIME_FENCED");
        error(()->lifecycle.submitted(actual.id(),UUID.randomUUID()),"VD_RUNTIME_FENCED");
    }
    @Test void replacementKeepsVdAndOldSourcesUntilPhysicalStopAndSurvivesServiceRecreation() throws Exception {
        var f=fixture();var old=ready(provision(f));
        update(f,"이름만 수정",f.a());assertThat(runtimes.current(f.vd().id()).orElseThrow().id()).isEqualTo(old.id());assertThat(runtimes.pending(f.vd().id())).isEmpty();
        var changed=update(f,"원본 교체",f.b());var replacement=runtimes.pending(f.vd().id()).orElseThrow();
        assertThat(replacement.kind()).isEqualTo("REPLACE");assertThat(replacement.sourceRuntimeId()).isEqualTo(old.id());assertThat(replacement.targetRuntimeId()).isNull();
        assertThat(lifecycle.get(old.id()).desiredState()).isEqualTo("DRAINING");assertThat(runtimes.history(f.vd().id(),100)).hasSize(1);
        error(()->devices.release(f.a().id()),"DEVICE_IN_USE");
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.device SET state='RELEASED',revision=revision+1 WHERE id=?",f.a().id())).isInstanceOf(DataIntegrityViolationException.class);
        update(f,"진행 중 이름 수정",f.b());assertThat(runtimes.pending(f.vd().id()).orElseThrow().id()).isEqualTo(replacement.id());
        lifecycle.drained(old.id(),old.sessionId());
        var recreated=new VDLifecycleService(vdRepository,runtimes,profileRepository,nodes,clock);
        new TransactionTemplate(transactions).executeWithoutResult(s->recreated.confirmStopped(old.id()));
        var op=runtimes.operation(replacement.id()).orElseThrow();var fresh=lifecycle.get(op.targetRuntimeId());
        assertThat(fresh.id()).isNotEqualTo(old.id());assertThat(fresh.vdId()).isEqualTo(old.vdId());assertThat(fresh.generation()).isEqualTo(2);
        assertThat(fresh.requestedRevision()).isEqualTo(changed.revision()+1);assertThat(runtimes.bindings(f.vd().id(),100)).hasSize(2).filteredOn(b->b.closedAt()!=null).hasSize(1);
        assertThat(devices.release(f.a().id()).state()).isEqualTo(Device.State.RELEASED);
        error(()->lifecycle.attest(old.id(),old.podUid(),old.nodeUid(),old.nodeName(),old.sessionId(),true,20),"VD_RUNTIME_FENCED");
        assertThat(lifecycle.get(old.id()).terminal()).isTrue();
        // Source termination was confirmed before CREATE; command receipts sharing one timestamp may sort either way.
        ready(op);assertThat(runtimes.history(f.vd().id(),100)).hasSize(2);
    }
    @Test void releaseDuringReplacementSupersedesItAndNeverStartsTarget() throws Exception {
        var f=fixture();var old=ready(provision(f));update(f,"교체",f.b());var replacement=runtimes.pending(f.vd().id()).orElseThrow();
        vds.release(f.vd().id());var drain=runtimes.pending(f.vd().id()).orElseThrow();assertThat(drain.kind()).isEqualTo("DRAIN");
        assertThat(runtimes.operation(replacement.id()).orElseThrow().state()).isEqualTo("SUPERSEDED");
        lifecycle.drained(old.id(),old.sessionId());lifecycle.confirmStopped(old.id());
        assertThat(runtimes.operation(drain.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");assertThat(runtimes.current(f.vd().id())).isEmpty();
        assertThat(runtimes.history(f.vd().id(),100)).hasSize(1);assertThat(runtimes.bindings(f.vd().id(),100)).allMatch(b->b.closedAt()!=null);
        assertThat(devices.release(f.a().id()).state()).isEqualTo(Device.State.RELEASED);assertThat(devices.release(f.b().id()).state()).isEqualTo(Device.State.RELEASED);
    }
    @Test void ambiguousCreateMustResolveAndLateResponseReopensCleanupWithoutRevivingRuntime() throws Exception {
        var f=fixture();var provision=provision(f);var r=lifecycle.get(provision.targetRuntimeId());
        var create=runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)).orElseThrow();
        vds.release(f.vd().id());var drain=runtimes.pending(f.vd().id()).orElseThrow();
        error(()->lifecycle.confirmStopped(r.id()),"VD_CREATE_UNRESOLVED");
        assertThat(runtimes.finishCommand(create.id(),create.leaseOwner(),clock.instant())).isTrue();
        lifecycle.confirmStopped(r.id());
        var deletion=runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)).orElseThrow();
        assertThat(deletion.kind()).isEqualTo("DELETE");assertThat(runtimes.finishCommand(deletion.id(),deletion.leaseOwner(),clock.instant())).isTrue();
        var late=lifecycle.submitted(r.id(),UUID.randomUUID());assertThat(late.terminal()).isTrue();assertThat(late.desiredState()).isEqualTo("STOPPED");
        var cleanup=runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)).orElseThrow();
        assertThat(cleanup.id()).isEqualTo(deletion.id());assertThat(cleanup.attempts()).isEqualTo(2);
        assertThat(runtimes.current(f.vd().id())).isEmpty();assertThat(runtimes.bindings(f.vd().id(),100)).singleElement().satisfies(b->assertThat(b.closedAt()).isNotNull());
        assertThat(runtimes.operation(drain.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void commandLeaseIsExclusiveReclaimableAndRejectsExpiredOwnerCompletion() throws Exception {
        var f=fixture();var r=lifecycle.get(provision(f).targetRuntimeId());
        var values=parallel(()->runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)));
        var leased=values.stream().flatMap(Optional::stream).toList();assertThat(leased).hasSize(1);var first=leased.getFirst();
        clock.advance(46);var second=runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofSeconds(45)).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());assertThat(second.leaseOwner()).isNotEqualTo(first.leaseOwner());assertThat(second.attempts()).isEqualTo(2);
        assertThat(runtimes.finishCommand(first.id(),first.leaseOwner(),clock.instant())).isFalse();
        assertThat(runtimes.deferCommand(first.id(),first.leaseOwner(),clock.instant(),clock.instant())).isFalse();
        assertThat(runtimes.finishCommand(second.id(),second.leaseOwner(),clock.instant())).isTrue();
    }
    @Test void startupAndLeaseExpiryFenceSessionAndDrainTimeoutRequestsForcedCleanup() throws Exception {
        var f=fixture();var op=provision(f);clock.advance(61);lifecycle.reconcile(op.targetRuntimeId());
        assertThat(runtimes.operation(op.id()).orElseThrow().reason()).isEqualTo("STARTUP_TIMEOUT");assertThat(lifecycle.get(op.targetRuntimeId()).desiredState()).isEqualTo("STOPPED");
        var g=fixture();var running=ready(provision(g));clock.advance(21);
        error(()->lifecycle.attest(running.id(),running.podUid(),running.nodeUid(),running.nodeName(),running.sessionId(),true,20),"VD_RUNTIME_FENCED");
        lifecycle.reconcile(running.id());assertThat(lifecycle.get(running.id()).failureReason()).isEqualTo("LEASE_EXPIRED");
        var h=fixture();var draining=ready(provision(h));var drain=lifecycle.drain(h.vd().id(),0,key());clock.advance(6);lifecycle.reconcile(draining.id());
        assertThat(lifecycle.get(draining.id()).failureReason()).isEqualTo("DRAIN_TIMEOUT");assertThat(runtimes.operation(drain.id()).orElseThrow().state()).isEqualTo("RUNNING");
        lifecycle.confirmStopped(draining.id());assertThat(runtimes.operation(drain.id()).orElseThrow().state()).isEqualTo("SUCCEEDED");
    }
    @Test void directWritesCannotChangeSnapshotIdentityReadinessOrTerminalHistory() throws Exception {
        var f=fixture();var op=provision(f);var r=lifecycle.get(op.targetRuntimeId());
        for(String sql:List.of("DELETE FROM edgeai.vd_runtime WHERE id=?","UPDATE edgeai.vd_runtime SET generation=generation+1 WHERE id=?",
            "UPDATE edgeai.vd_runtime SET configuration='{}'::jsonb WHERE id=?","UPDATE edgeai.vd_runtime SET observed_state='READY' WHERE id=?",
            "UPDATE edgeai.vd_runtime SET desired_state='STOPPED',observed_state='TERMINATED' WHERE id=?"))
            assertThatThrownBy(()->jdbc.update(sql,r.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_operation SET state='SUCCEEDED',finished_at=updated_at WHERE id=?",op.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_runtime_binding SET closed_revision=opened_revision,closed_at=opened_at WHERE runtime_id=?",r.id())).isInstanceOf(DataIntegrityViolationException.class);
        var actual=ready(op);var drain=lifecycle.drain(f.vd().id(),0,key());lifecycle.drained(actual.id(),actual.sessionId());lifecycle.confirmStopped(actual.id());
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_runtime SET desired_state='RUNNING',observed_state='UNREADY' WHERE id=?",r.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_operation SET state='RUNNING',finished_at=NULL WHERE id=?",drain.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.vd_runtime_binding WHERE runtime_id=?",r.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void releaseAndFirstReadinessRaceNeverLeavesReleasedVdAcceptingWork() throws Exception {
        for(int i=0;i<8;i++) {
            var f=fixture();var op=provision(f);var r=lifecycle.get(op.targetRuntimeId());completeCreate(r);
            UUID pod=UUID.randomUUID(),node=UUID.randomUUID(),session=UUID.randomUUID();lifecycle.submitted(r.id(),pod);
            try(var executor=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var readiness=executor.submit(()->{start.await();try { lifecycle.attest(r.id(),pod,node,"worker-1",session,true,20);return "READY"; }
                    catch(ControlPlaneException e){return e.code();}});
                var release=executor.submit(()->{start.await();return vds.release(f.vd().id());});start.countDown();
                assertThat(readiness.get(20,TimeUnit.SECONDS)).isIn("READY","VD_RUNTIME_FENCED");
                assertThat(release.get(20,TimeUnit.SECONDS).state()).isEqualTo(VirtualDevice.State.RELEASED);
            }
            var current=lifecycle.get(r.id());assertThat(current.ready(clock.instant())).isFalse();
            assertThat(current.desiredState()).isIn("DRAINING","STOPPED");
            assertThat(runtimes.pending(f.vd().id()).orElseThrow().kind()).isEqualTo("DRAIN");
        }
    }
    @Test void nodePlacementRequiresTheCapturedUidAndNameAndFailedGenerationCanBeReplaced() throws Exception {
        var f=fixture();UUID node=UUID.randomUUID();String nodeName="vd-node-"+UUID.randomUUID();
        jdbc.update("INSERT INTO edgeai.execution_node(id,name,architecture,operating_system,observed_status,cpu,memory,labels,observed_at) VALUES (?,?,'amd64','linux','READY','2','1Gi','{}'::jsonb,?)",
            node,nodeName,java.sql.Timestamp.from(clock.instant()));
        var vd=vds.update(f.vd().id(),encode(Map.of("revision",0,"displayName","고정 노드","sources",List.of(Map.of("sourceKey","input","deviceId",f.a().id().toString())),
            "placement",Map.of("mode","NODE","nodeId",node.toString()))));
        var op=lifecycle.provision(vd.id(),vd.revision(),key(),f.settings(),false);var r=lifecycle.get(op.targetRuntimeId());completeCreate(r);
        UUID pod=UUID.randomUUID(),session=UUID.randomUUID();lifecycle.submitted(r.id(),pod);
        error(()->lifecycle.attest(r.id(),pod,UUID.randomUUID(),nodeName,session,true,20),"VD_RUNTIME_FENCED");
        error(()->lifecycle.attest(r.id(),pod,node,"different-node",session,true,20),"VD_RUNTIME_FENCED");
        assertThat(lifecycle.attest(r.id(),pod,node,nodeName,session,true,20).ready(clock.instant())).isTrue();
        lifecycle.fail(r.id(),"POD_FAILED");lifecycle.confirmStopped(r.id());
        var next=lifecycle.provision(vd.id(),vd.revision(),key(),f.settings(),false);
        assertThat(lifecycle.get(next.targetRuntimeId()).generation()).isEqualTo(2);
        assertThat(lifecycle.get(r.id()).failureReason()).isEqualTo("POD_FAILED");
        assertThat(runtimes.operation(op.id()).orElseThrow().state()).isEqualTo("SUCCEEDED"); // Historical readiness is retained.
    }
    private <T> List<T> parallel(Supplier<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
            for(int i=0;i<8;i++)futures.add(executor.submit(()->{start.await();return call.get();}));start.countDown();
            var result=new ArrayList<T>();for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));return result;
        }
    }
}
