package io.edgeai.app.integration;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Actual PostgreSQL constraints/concurrency. Runtime observations and artifact receipts are explicit fixtures.
 * This does not assert that the public VD Run API, poll allocation or Task execution is connected. */
@SpringBootTest
class VDTaskPersistenceIntegrationTest {
    @Autowired ProfileService profiles;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowRepository definitions;
    @Autowired VirtualDeviceService devices;
    @Autowired VirtualDeviceRepository vds;
    @Autowired VDLifecycleService lifecycle;
    @Autowired VDPollRepository polls;
    @Autowired ExecutionRepository executions;
    @Autowired RuntimeRepository runtimes;
    @Autowired VDTaskRepository tasks;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private final JsonDocuments json=new JsonDocuments();
    private record Fixture(VirtualDevice vd,VDRuntime supervisor,WorkflowRun run,List<RuntimeInstance> work){}
    private <T>T transaction(java.util.function.Supplier<T> work){return new TransactionTemplate(transactions).execute(s->work.get());}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,json.canonical(Map.of("key","vd-task-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    private Fixture fixture(int count,int capacity)throws Exception {
        return fixture(count,capacity,true);
    }
    private Fixture fixture(int count,int capacity,boolean compatible)throws Exception {
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        UUID vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",sp.toString(),"sources",Map.of(),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",capacity,"startupTimeoutSeconds",60,"drainTimeoutSeconds",30)));
        var vd=devices.create(json.canonical(Map.of("key","vd-task-"+UUID.randomUUID(),"displayName","VD Task persistence fixture","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
        String namespace="vd-task-"+UUID.randomUUID().toString().substring(0,12);
        var op=lifecycle.provision(vd.id(),0,"fixture",new RuntimeSettings(namespace,"edgeai-runner",URI.create("http://fixture.invalid"),120),false);
        var vr=lifecycle.submitted(op.targetRuntimeId(),UUID.randomUUID());
        vr=lifecycle.attest(vr.id(),vr.podUid(),UUID.randomUUID(),"fixture-node",UUID.randomUUID(),true,60);
        var ready=vr;
        transaction(()->{polls.save(new VDPoll(ready.id(),ready.sessionId(),0,"sha256:"+"a".repeat(64),"RUN",ready.updatedAt(),ready.updatedAt()));return null;});
        var wf=workflows.create(json.canonical(Map.of("key","vd-task-"+UUID.randomUUID(),"displayName","VD Task persistence fixture"))).value();
        UUID taskProfile=compatible?sp:publish(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        var nodes=IntStream.range(0,count).mapToObj(i->Map.of("key","task"+i,"serviceProfileVersionId",taskProfile.toString(),"parameters",Map.of())).toList();
        var version=workflows.publish(wf.id(),json.canonical(Map.of("version","1.0.0","tasks",nodes,"dependencies",List.of()))).value();
        var now=Instant.now();
        var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("vd-task-fixture",Map.of("version",version.id().toString())),"VD",null,"{}",new RetryPolicy(2,1,600,Set.of("RUNTIME_LOST")),null,"PENDING",now,now,null,vd.id());
        var work=transaction(()->{
            vds.find(vd.id(),true).orElseThrow();assertThat(executions.create(run)).isTrue();
            executions.initialize(run,definitions.definitions(version.id()),new HashSet<>(IntStream.range(0,count).mapToObj(i->"task"+i).toList()));
            var result=new ArrayList<RuntimeInstance>();
            for(var task:executions.tasks(run.id())) {
                var attempt=executions.attempts(task.id()).getFirst();assertThat(attempt.vdId()).isEqualTo(vd.id());
                var r=new RuntimeInstance(UUID.randomUUID(),attempt.id(),task.id(),run.id(),1,namespace,null,UUID.randomUUID(),"RUNNING","PENDING",null,null,null,null,now.plusSeconds(120),null,now,now,null,vd.id());
                runtimes.create(r);result.add(runtimes.runtime(r.id()).orElseThrow());
            }
            return List.copyOf(result);
        });
        return new Fixture(vd,ready,run,work);
    }
    private VDTaskAllocation allocation(Fixture f,RuntimeInstance r,int slot,long sequence){return new VDTaskAllocation(UUID.randomUUID(),r.id(),f.vd().id(),f.supervisor().id(),f.supervisor().generation(),f.supervisor().sessionId(),f.supervisor().podUid(),slot,sequence,Instant.now(),null,null,null,null);}
    private Optional<VDTaskAllocation> allocate(Fixture f,RuntimeInstance r,int capacity) {
        return transaction(()->{
            executions.run(r.runId(),true).orElseThrow();
            var old=tasks.byRuntime(r.id());if(old.isPresent())return old;
            var used=new HashSet<>(tasks.open(f.supervisor().id()).stream().map(VDTaskAllocation::slot).toList());
            for(int slot=1;slot<=capacity;slot++)if(!used.contains(slot)) {
                var a=allocation(f,r,slot,1);tasks.create(a,a.assignedAt().plusSeconds(60));return tasks.byRuntime(r.id());
            }
            return Optional.empty();
        });
    }
    private void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class);}

    @Test void vdRuntimeCannotImpersonateJobRemoteOrChangePinnedTarget()throws Exception {
        var f=fixture(1,1);var r=runtimes.runtime(f.work().getFirst().id()).orElseThrow();
        assertThat(r.vd()).isTrue();assertThat(r.remote()).isFalse();assertThat(r.jobName()).isNull();
        assertThat(executions.run(f.run().id(),false).orElseThrow().vdId()).isEqualTo(f.vd().id());
        assertThat(runtimes.active(r.namespace(),100)).isEmpty();assertThat(runtimes.activeRemote(r.namespace(),100)).isEmpty();
        assertThat(runtimes.leaseCommand(r.namespace(),UUID.randomUUID(),Instant.now(),Duration.ofSeconds(30))).isEmpty();
        assertThat(runtimes.leaseRemoteCommand(r.namespace(),UUID.randomUUID(),Instant.now(),Duration.ofSeconds(30))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id=?",Integer.class,r.id())).isZero();
        invalid(()->jdbc.update("UPDATE edgeai.runtime_instance SET job_name=? WHERE id=?","edgeai-"+r.attemptId(),r.id()));
        invalid(()->jdbc.update("UPDATE edgeai.runtime_instance SET vd_id=NULL WHERE id=?",r.id()));
        invalid(()->jdbc.update("UPDATE edgeai.workflow_run SET mode='AUTO',vd_id=NULL WHERE id=?",r.runId()));
        invalid(()->jdbc.update("UPDATE edgeai.task_attempt SET mode='AUTO',vd_id=NULL WHERE id=?",r.attemptId()));
    }
    @Test void eightCompetingTasksRespectTwoSlotsAndDurableReplay()throws Exception {
        var f=fixture(8,2);
        try(var pool=Executors.newFixedThreadPool(8)){
            var start=new CountDownLatch(1);var jobs=f.work().stream().map(r->pool.submit(()->{start.await();return allocate(f,r,2);})).toList();start.countDown();
            var accepted=new ArrayList<VDTaskAllocation>();for(var job:jobs)job.get(15,TimeUnit.SECONDS).ifPresent(accepted::add);
            assertThat(accepted).hasSize(2);assertThat(accepted.stream().map(VDTaskAllocation::slot)).containsExactlyInAnyOrder(1,2);
            for(var a:accepted)assertThat(allocate(f,runtimes.runtime(a.runtimeId()).orElseThrow(),2).orElseThrow()).isEqualTo(a);
        }
        assertThat(tasks.open(f.supervisor().id())).hasSize(2);assertThat(tasks.assigned(f.supervisor().id(),1)).hasSize(2);
        assertThat(tasks.pending(f.vd().id(),100)).hasSize(6);
        var queued=runtimes.runtime(tasks.pending(f.vd().id(),1).getFirst()).orElseThrow();
        invalid(()->transaction(()->{tasks.create(allocation(f,queued,3,1),Instant.now().plusSeconds(60));return null;}));
        invalid(()->transaction(()->{tasks.create(allocation(f,queued,1,1),Instant.now().plusSeconds(60));return null;}));
    }
    @Test void stoppedRequestDoesNotReleaseSlotAndCompletionNeverCreatesResult()throws Exception {
        var f=fixture(2,1);var first=f.work().getFirst();var second=f.work().getLast();var a=allocate(f,first,1).orElseThrow();
        transaction(()->{runtimes.stop(first.id(),"CANCELLED",Instant.now());return null;});
        invalid(()->transaction(()->{tasks.close(first.id(),"PROCESS_EXIT",2L,0,Instant.now());return null;}));
        assertThat(allocate(f,second,1)).isEmpty();
        Instant closed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        transaction(()->{executions.run(first.runId(),true);runtimes.terminated(first.id(),closed);tasks.close(first.id(),"PROCESS_EXIT",2L,0,closed);return null;});
        transaction(()->{tasks.close(first.id(),"PROCESS_EXIT",2L,0,closed);return null;});
        assertThat(runtimes.result(first.taskId())).isEmpty();assertThat(tasks.open(f.supervisor().id())).isEmpty();
        assertThat(tasks.byRuntime(first.id()).orElseThrow().open()).isFalse();assertThat(allocate(f,second,1)).isPresent();
        invalid(()->jdbc.update("UPDATE edgeai.vd_task_allocation SET exit_code=1 WHERE id=?",a.id()));
        invalid(()->jdbc.update("UPDATE edgeai.vd_task_allocation SET closed_at=NULL,close_reason=NULL,completion_sequence=NULL,exit_code=NULL WHERE id=?",a.id()));
        invalid(()->jdbc.update("DELETE FROM edgeai.vd_task_allocation WHERE id=?",a.id()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.runtime_command WHERE runtime_id=?",Integer.class,first.id())).isZero();
    }
    @Test void staleLeaseForeignIdentityAndInvalidCompletionCannotAllocateOrFreeSlot()throws Exception {
        var f=fixture(2,1);var first=f.work().getFirst();var second=f.work().getLast();var a=allocate(f,first,1).orElseThrow();
        invalid(()->jdbc.update("UPDATE edgeai.vd_task_allocation SET generation=generation+1 WHERE id=?",a.id()));
        invalid(()->jdbc.update("UPDATE edgeai.vd_task_allocation SET close_reason='PROCESS_EXIT',completion_sequence=2,exit_code=0 WHERE id=?",a.id()));
        invalid(()->transaction(()->{tasks.close(first.id(),"POD_GONE",null,null,Instant.now());return null;}));
        var r=f.supervisor();
        var foreign=new VDTaskAllocation(UUID.randomUUID(),second.id(),f.vd().id(),r.id(),r.generation(),UUID.randomUUID(),r.podUid(),1,1,Instant.now(),null,null,null,null);
        invalid(()->transaction(()->{tasks.create(foreign,Instant.now().plusSeconds(60));return null;}));
        lifecycle.drain(f.vd().id(),0,"fixture-drain");
        invalid(()->transaction(()->{tasks.create(allocation(f,second,1,1),Instant.now().plusSeconds(60));return null;}));
    }
    @Test void resultMustNameItsActualVdAllocationAndSealImmutableProducer()throws Exception {
        var f=fixture(1,1);var r=f.work().getFirst();allocate(f,r,1);var vr=f.supervisor();
        transaction(()->{tasks.claimed(r.id(),vr.podUid(),vr.nodeUid(),vr.nodeName(),Instant.now());return null;});
        var artifact=new VerifiedArtifact("fixture-only",new ArtifactContent(r.taskId(),r.attemptId(),"output","a".repeat(64),2,"application/json").objectKey(),"fixture-version","a".repeat(64),2,"application/json");
        var outputs=List.of(new TaskResult.Output("output",artifact));Instant now=Instant.now();
        var invalid=new TaskResult(UUID.randomUUID(),r.taskId(),r.attemptId(),r.id(),r.epoch(),vr.podUid(),"sha256:"+"b".repeat(64),now,outputs);
        invalid(()->transaction(()->{runtimes.commit(invalid,now);return null;}));assertThat(runtimes.result(r.taskId())).isEmpty();
        var result=new TaskResult(UUID.randomUUID(),r.taskId(),r.attemptId(),r.id(),r.epoch(),vr.podUid(),"sha256:"+"b".repeat(64),now,outputs,null,vr.id());
        transaction(()->{runtimes.commit(result,now);return null;});
        var stored=runtimes.result(r.taskId()).orElseThrow();assertThat(stored.vdRuntimeId()).isEqualTo(vr.id());assertThat(stored.producerPodUid()).isEqualTo(vr.podUid());
        assertThat(jdbc.queryForObject("SELECT producer_kind FROM edgeai.task_result WHERE id=?",String.class,stored.id())).isEqualTo("VD");
        assertThat(tasks.open(vr.id())).hasSize(1); // Result success is not process termination.
        invalid(()->jdbc.update("UPDATE edgeai.task_result SET vd_runtime_id=NULL WHERE id=?",stored.id()));
        invalid(()->jdbc.update("UPDATE edgeai.result_artifact SET bytes=3 WHERE result_id=?",stored.id()));
    }
    @Test void serviceIdentityLeaseAndProducerProofRemainRequired()throws Exception {
        var incompatible=fixture(1,1,false);
        invalid(()->allocate(incompatible,incompatible.work().getFirst(),1));
        var f=fixture(1,1);var r=f.work().getFirst();var vr=f.supervisor();
        var expired=new VDTaskAllocation(UUID.randomUUID(),r.id(),f.vd().id(),vr.id(),vr.generation(),vr.sessionId(),vr.podUid(),1,1,vr.leaseUntil(),null,null,null,null);
        invalid(()->transaction(()->{tasks.create(expired,vr.leaseUntil().plusSeconds(60));return null;}));
        allocate(f,r,1);
        assertThatThrownBy(()->transaction(()->{tasks.claimed(r.id(),UUID.randomUUID(),vr.nodeUid(),vr.nodeName(),Instant.now());return null;})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->transaction(()->{tasks.claimed(r.id(),vr.podUid(),UUID.randomUUID(),vr.nodeName(),Instant.now());return null;})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->transaction(()->{tasks.claimed(r.id(),vr.podUid(),vr.nodeUid(),vr.nodeName(),vr.leaseUntil());return null;})).isInstanceOf(IllegalStateException.class);
        assertThat(runtimes.runtime(r.id()).orElseThrow().producerPodUid()).isNull();
        lifecycle.drain(f.vd().id(),0,"finish-existing-work");
        transaction(()->{tasks.claimed(r.id(),vr.podUid(),vr.nodeUid(),vr.nodeName(),Instant.now());return null;});
        assertThat(runtimes.runtime(r.id()).orElseThrow().producerPodUid()).isEqualTo(vr.podUid());
    }
    @Test void podGoneClosureRequiresSupervisorTerminationAndRetainsAllocationHistory()throws Exception {
        var f=fixture(1,1);var r=f.work().getFirst();var vr=f.supervisor();allocate(f,r,1);
        transaction(()->{executions.run(r.runId(),true);runtimes.stop(r.id(),"RUNTIME_LOST",Instant.now());runtimes.terminated(r.id(),Instant.now());return null;});
        invalid(()->transaction(()->{tasks.close(r.id(),"POD_GONE",null,null,Instant.now());return null;}));
        lifecycle.fail(vr.id(),"RUNTIME_LOST");
        jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE runtime_id=? AND kind='CREATE'",vr.id());
        // Explicit physical absence fixture; real Kubernetes evidence is a separate acceptance gate.
        lifecycle.confirmStopped(vr.id());
        assertThat(tasks.open(vr.id())).isEmpty();assertThat(tasks.byRuntime(r.id()).orElseThrow().closeReason()).isEqualTo("POD_GONE");
        assertThat(tasks.assigned(vr.id(),1)).hasSize(1);assertThat(runtimes.result(r.taskId())).isEmpty();
    }
    @Test void retryRetainsVdTargetAndRunLockAcquiresVdBeforeRun()throws Exception {
        var f=fixture(1,1);var r=f.work().getFirst();
        transaction(()->{executions.run(r.runId(),true);runtimes.fail(r.id(),"RUNTIME_LOST",Instant.now());runtimes.terminated(r.id(),Instant.now());return null;});
        var next=transaction(()->{executions.run(r.runId(),true);return executions.startRetry(r.taskId(),Instant.now());});
        assertThat(next.vdId()).isEqualTo(f.vd().id());assertThat(next.mode()).isEqualTo("VD");assertThat(next.epoch()).isEqualTo(2);
        var pid=new AtomicInteger();
        try(var pool=Executors.newSingleThreadExecutor()){
            var job=transaction(()->{
                vds.find(f.vd().id(),true).orElseThrow();
                var future=pool.submit(()->transaction(()->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));return executions.run(f.run().id(),true).orElseThrow();}));
                long end=System.nanoTime()+Duration.ofSeconds(8).toNanos();boolean waiting=false;
                while(System.nanoTime()<end){
                    if(pid.get()!=0 && Boolean.TRUE.equals(jdbc.queryForObject("SELECT coalesce(bool_or(wait_event_type='Lock'),false) FROM pg_stat_activity WHERE pid=?",Boolean.class,pid.get()))){waiting=true;break;}
                    try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }
                assertThat(waiting).isTrue();
                jdbc.queryForObject("SELECT id FROM edgeai.workflow_run WHERE id=? FOR UPDATE NOWAIT",UUID.class,f.run().id());
                return future;
            });
            assertThat(job.get(10,TimeUnit.SECONDS).vdId()).isEqualTo(f.vd().id());
        }
    }
}
