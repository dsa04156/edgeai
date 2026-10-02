package io.edgeai.app.integration;

import io.edgeai.adapters.remote.ReferenceRemoteGateway;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.*;
import io.edgeai.domain.execution.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.remote.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.*;
import io.edgeai.domain.storage.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL lifecycle/races; explicitly synthetic provider observations and storage receipts except where stated. */
@SpringBootTest
@Import(RemoteRuntimeIntegrationTest.TimeConfiguration.class)
class RemoteRuntimeIntegrationTest {
    static final class TestClock extends Clock {
        private Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    @TestConfiguration static class TimeConfiguration {@Bean @Primary TestClock remoteClock(){return new TestClock();}}
    @Autowired TestClock clock;
    @Autowired RuntimeLifecycleService lifecycle;
    @Autowired RuntimeRepository runtimes;
    @Autowired RemoteRepository remotes;
    @Autowired ExecutionRepository repository;
    @Autowired WorkflowRepository definitions;
    @Autowired ExecutionService executions;
    @Autowired WorkflowService workflows;
    @Autowired ProfileService profiles;
    @Autowired ResultService resultService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private final RemoteTarget target=new RemoteTarget("reference","sha256:"+"a".repeat(64),"SYNTHETIC");
    private record Fixture(WorkflowRun run,UUID task,UUID child,UUID attempt,String scope){}
    private Fixture fixture() throws Exception {
        var spec=new HashMap<Object,Object>((Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",false)));
        var profile=profiles.publish(ProfileIdentity.Kind.SERVICE,json.canonical(Map.of("key","remote-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version();
        var workflow=workflows.create(json.canonical(Map.of("key","remote-"+UUID.randomUUID(),"displayName","Remote lifecycle fixture"))).value();
        var tasks=List.of("root","child").stream().map(key->Map.of("key",key,"serviceProfileVersionId",profile.id().toString(),"parameters",Map.of("features",List.of(2,1),"weights",List.of(2,3),"bias",1))).toList();
        var version=workflows.publish(workflow.id(),json.canonical(Map.of("version","1.0.0","tasks",tasks,"dependencies",List.of(Map.of("fromTask","root","toTask","child","fromPort","output","toPort","input","mode","BATCH"))))).value();
        // Internal placement fixture. This does not claim that the public REMOTE selection API is connected.
        var run=new WorkflowRun(UUID.randomUUID(),version.id(),UUID.randomUUID(),json.digest("remote-fixture",Map.of("version",version.id().toString())),"REMOTE",null,"{}",
            new RetryPolicy(2,2,600,Set.of("RUNTIME_LOST")),null,"PENDING",clock.instant(),clock.instant(),target);
        new TransactionTemplate(transactions).execute(s->{assertThat(repository.create(run)).isTrue();repository.initialize(run,definitions.definitions(version.id()),Set.of("root"));return null;});
        var rows=executions.detail(run.id()).tasks();UUID root=rows.stream().filter(t->t.key().equals("root")).findFirst().orElseThrow().id();
        UUID child=rows.stream().filter(t->t.key().equals("child")).findFirst().orElseThrow().id();
        return new Fixture(run,root,child,executions.taskDetail(root).attempts().getFirst().id(),"remote-"+UUID.randomUUID());
    }
    private RuntimeInstance plan(Fixture f){return lifecycle.planRemote(f.attempt(),f.scope(),target);}
    private RemoteStatus status(UUID attempt,long revision,RemoteStatus.State state,String failure,List<RemoteFile> outputs){
        var dispatch=lifecycle.remoteDispatch(attempt);return new RemoteStatus(dispatch.work().identity(),revision,dispatch.allocation().requestDigest(),state,dispatch.work().expiresAt(),failure,"SYNTHETIC",outputs);
    }
    private ResultManifest manifest(){return new ResultManifest(List.of(new ResultManifest.Output("output",2,"a".repeat(64),"application/json","fixture-version")));}
    private List<RemoteFile> outputMetadata(){return manifest().outputs().stream().map(f->new RemoteFile(f.port(),f.bytes(),f.sha256(),f.mediaType())).toList();}
    private RemoteStatus succeeded(Fixture f){var s=status(f.attempt(),3,RemoteStatus.State.SUCCEEDED,null,outputMetadata());lifecycle.observeRemote(s);return s;}
    private void completeCreate(UUID attempt){jdbc.update("UPDATE edgeai.runtime_command SET completed=true,lease_owner=NULL,lease_until=NULL WHERE kind='CREATE' AND runtime_id=(SELECT id FROM edgeai.runtime_instance WHERE attempt_id=?)",attempt);}
    private ArtifactStore receipts(){return new ArtifactStore(){
        public ArtifactGrant upload(ArtifactContent c){throw new UnsupportedOperationException();}
        public ArtifactGrant download(VerifiedArtifact a){throw new UnsupportedOperationException();}
        public VerifiedArtifact verify(ArtifactContent c,String version){return new VerifiedArtifact("fixture-only",c.objectKey(),version,c.sha256(),c.bytes(),c.mediaType());}
    };}
    private void fenced(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isIn("PRODUCER_FENCED","REMOTE_OBSERVATION_CONFLICT"));}
    private <T> List<T> parallel(IntFunction<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)){var latch=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();for(int i=0;i<8;i++){int index=i;futures.add(executor.submit(()->{latch.await();return call.apply(index);}));}latch.countDown();var results=new ArrayList<T>();for(var f:futures)results.add(f.get(15,TimeUnit.SECONDS));return results;}
    }

    @Test void atomicPlanningSeparatesKindsAndBindsImmutableProviderAndWork() throws Exception {
        var f=fixture();var values=parallel(i->plan(f));var r=values.getFirst();assertThat(values.stream().map(RuntimeInstance::id).distinct()).hasSize(1);
        assertThat(r.remote()).isTrue();assertThat(r.jobName()).isNull();assertThat(r.producerPodUid()).isNull();assertThat(r.nodeUid()).isNull();
        var d=lifecycle.remoteDispatch(f.attempt());assertThat(d.allocation().target()).isEqualTo(target);assertThat(d.work().identity().allocationId()).isEqualTo(r.remoteAllocationId());
        assertThat(runtimes.active(f.scope(),100)).isEmpty();assertThat(runtimes.activeRemote(f.scope(),100)).hasSize(1);
        assertThat(runtimes.leaseCommand(f.scope(),UUID.randomUUID(),clock.instant().plusSeconds(1),Duration.ofSeconds(10))).isEmpty();
        assertThat(runtimes.leaseRemoteCommand(f.scope(),UUID.randomUUID(),clock.instant().plusSeconds(1),Duration.ofSeconds(10))).isPresent();
        fenced(()->lifecycle.planRemote(f.attempt(),f.scope(),new RemoteTarget("different","sha256:"+"b".repeat(64),"SYNTHETIC")));
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.remote_allocation SET provider_key='different' WHERE id=?",r.remoteAllocationId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.runtime_instance SET producer_pod_uid=?,job_uid=?,node_uid=?,node_name='fake' WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),r.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        fenced(()->lifecycle.submitted(f.attempt(),UUID.randomUUID()));fenced(()->lifecycle.dispatch(f.attempt()));
    }
    @Test void realProviderReservationMatchesPinnedDigestAndComputationHasNoKubernetesIdentity() throws Exception {
        var f=fixture();plan(f);var d=lifecycle.remoteDispatch(f.attempt());Path token=directory.resolve("token"),ready=directory.resolve("ready.json");Files.writeString(token,UUID.randomUUID().toString().replace("-",""));
        Files.setPosixFilePermissions(token,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var process=new ProcessBuilder("python3","../../simulator/remote_server.py","--state-dir",directory.resolve("state").toString(),"--token-file",token.toString(),"--ready-file",ready.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long bound=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(process.isAlive() && !Files.exists(ready) && System.nanoTime()<bound)Thread.sleep(20);
            assertThat(Files.exists(ready)).isTrue();String origin="http://127.0.0.1:"+((Map<?,?>)json.decode(Files.readString(ready))).get("port");
            try(var gateway=new ReferenceRemoteGateway(origin,token,null,Duration.ofSeconds(3),"SYNTHETIC")) {
                var reserved=gateway.reserve(d.work());assertThat(reserved.requestDigest()).isEqualTo(d.allocation().requestDigest());lifecycle.observeRemote(reserved);
                lifecycle.observeRemote(gateway.start(d.work().identity()));RemoteStatus latest;
                do{latest=gateway.inspect(d.work().identity()).orElseThrow();if(latest.state()!=RemoteStatus.State.SUCCEEDED)Thread.sleep(20);}while(latest.state()!=RemoteStatus.State.SUCCEEDED && System.nanoTime()<bound);
                assertThat(latest.state()).isEqualTo(RemoteStatus.State.SUCCEEDED);var r=lifecycle.observeRemote(latest);assertThat(r.observedState()).isEqualTo("RUNNING");
                assertThat(r.producerPodUid()).isNull();assertThat(r.nodeUid()).isNull();assertThat(runtimes.result(f.task())).isEmpty();
                Path output=directory.resolve("output.json");gateway.downloadOutput(d.work().identity(),latest.outputs().getFirst(),output);assertThat(Files.readString(output)).contains("8.0");
            }
        }finally{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    @Test void observationOrderingAndForksCannotRewriteTerminalMetadata() throws Exception {
        var f=fixture();var r=plan(f);var allocated=status(f.attempt(),1,RemoteStatus.State.ALLOCATED,null,List.of());lifecycle.observeRemote(allocated);
        var running=status(f.attempt(),2,RemoteStatus.State.RUNNING,null,List.of());lifecycle.observeRemote(running);lifecycle.observeRemote(allocated);
        assertThat(remotes.find(r.remoteAllocationId()).orElseThrow().providerRevision()).isEqualTo(2);
        fenced(()->lifecycle.observeRemote(status(f.attempt(),2,RemoteStatus.State.ALLOCATED,null,List.of())));
        fenced(()->lifecycle.observeRemote(status(f.attempt(),3,RemoteStatus.State.ALLOCATED,null,List.of())));
        var done=succeeded(f);lifecycle.observeRemote(done);assertThat(runtimes.result(f.task())).isEmpty();
        fenced(()->lifecycle.observeRemote(status(f.attempt(),4,RemoteStatus.State.RUNNING,null,List.of())));
        var old=done.identity();var foreign=new RemoteIdentity(old.allocationId(),old.runId(),old.taskId(),UUID.randomUUID(),2);
        fenced(()->lifecycle.observeRemote(new RemoteStatus(foreign,3,done.requestDigest(),done.state(),done.expiresAt(),null,"SYNTHETIC",done.outputs())));
        assertThat(remotes.find(r.remoteAllocationId()).orElseThrow().providerState()).isEqualTo("SUCCEEDED");
    }
    @Test void cancellationBeforeReserveRequiresTombstoneAndFencesLateObservation() throws Exception {
        var f=fixture();var r=plan(f);var identity=lifecycle.remoteDispatch(f.attempt()).work().identity();executions.cancelRun(f.run().id(),"{}");
        fenced(()->lifecycle.confirmStopped(f.attempt()));assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLING");
        lifecycle.observeRemote(new RemoteStatus(identity,1,null,RemoteStatus.State.CANCELLED,null,null,"SYNTHETIC",List.of()));
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isEqualTo("TERMINATED");
        fenced(()->lifecycle.observeRemote(new RemoteStatus(identity,2,remotes.find(r.remoteAllocationId()).orElseThrow().requestDigest(),RemoteStatus.State.RUNNING,r.expiresAt(),null,"SYNTHETIC",List.of())));
        fenced(()->lifecycle.prepareRemoteCommit(f.attempt(),1,r.remoteAllocationId(),manifest()));assertThat(runtimes.retryReady(f.task())).isFalse();
        completeCreate(f.attempt());assertThat(runtimes.retryReady(f.task())).isTrue();
    }
    @Test void concurrentRemoteCommitsUseOneResultAndReleaseOneChildWithFixedInput() throws Exception {
        var f=fixture();var r=plan(f);var done=succeeded(f);var commits=new ArtifactCommitService(lifecycle,receipts());
        var results=parallel(i->commits.commitRemote(f.attempt(),1,r.remoteAllocationId(),manifest()));assertThat(results.stream().filter(Creation::created)).hasSize(1);
        assertThat(results.stream().map(v->v.value().id()).distinct()).hasSize(1);var result=runtimes.result(f.task()).orElseThrow();
        assertThat(result.producerPodUid()).isNull();assertThat(result.remoteAllocationId()).isEqualTo(r.remoteAllocationId());
        var publicResult=resultService.results(f.task()).items().getFirst();assertThat(publicResult.remoteAllocationId()).isEqualTo(r.remoteAllocationId());assertThat(publicResult.remoteSourceMode()).isEqualTo("SYNTHETIC");assertThat(publicResult.producerPodUid()).isNull();
        assertThat(executions.taskDetail(f.child()).attempts()).hasSize(1);var child=executions.taskDetail(f.child()).attempts().getFirst();lifecycle.planRemote(child.id(),f.scope(),target);
        var inputs=lifecycle.remoteDispatch(child.id());assertThat(inputs.inputs().getFirst().artifact().versionId()).isEqualTo("fixture-version");assertThat(inputs.work().inputs().getFirst().sha256()).isEqualTo("a".repeat(64));
        // The same terminal observation must also finish a newly requested DELETE after commit.
        lifecycle.observeRemote(done);assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isEqualTo("TERMINATED");
        assertThat(commits.commitRemote(f.attempt(),1,r.remoteAllocationId(),manifest()).created()).isFalse();
        fenced(()->commits.commit(f.attempt(),1,r.remoteAllocationId(),manifest()));fenced(()->commits.commitRemote(f.attempt(),2,r.remoteAllocationId(),manifest()));
    }
    @Test void cancellationDuringExternalVerificationFencesThePreviouslyValidPermit() throws Exception {
        var f=fixture();var r=plan(f);var done=succeeded(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        ArtifactStore delayed=new ArtifactStore(){
            public ArtifactGrant upload(ArtifactContent c){throw new UnsupportedOperationException();}public ArtifactGrant download(VerifiedArtifact a){throw new UnsupportedOperationException();}
            public VerifiedArtifact verify(ArtifactContent c,String v){entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}return receipts().verify(c,v);}
        };
        try(var executor=Executors.newSingleThreadExecutor()){
            var pending=executor.submit(()->new ArtifactCommitService(lifecycle,delayed).commitRemote(f.attempt(),1,r.remoteAllocationId(),manifest()));
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();executions.cancelTask(f.task(),"{}");}finally{release.countDown();}
            assertThatThrownBy(()->pending.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ControlPlaneException.class);
        }
        assertThat(runtimes.result(f.task())).isEmpty();lifecycle.observeRemote(done);assertThat(executions.taskDetail(f.task()).task().state()).isEqualTo("CANCELLED");assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("SKIPPED");
    }
    @Test void providerRestartRetryWaitsForCreateCompletionAndRetainsBindingWithNewEpoch() throws Exception {
        var f=fixture();var old=plan(f);lifecycle.observeRemote(status(f.attempt(),1,RemoteStatus.State.RUNNING,null,List.of()));
        lifecycle.observeRemote(status(f.attempt(),2,RemoteStatus.State.FAILED,"PROVIDER_RESTART",List.of()));assertThat(executions.taskDetail(f.task()).task().state()).isEqualTo("RETRY_WAIT");
        clock.advance(2);assertThat(lifecycle.retryTask(f.task())).isFalse();completeCreate(f.attempt());assertThat(lifecycle.retryTask(f.task())).isTrue();
        var next=executions.taskDetail(f.task()).attempts().getFirst();assertThat(next.epoch()).isEqualTo(2);assertThat(next.mode()).isEqualTo("REMOTE");
        var nextRuntime=runtimes.byAttempt(next.id()).orElseThrow();assertThat(nextRuntime.remoteAllocationId()).isNotEqualTo(old.remoteAllocationId());assertThat(remotes.find(nextRuntime.remoteAllocationId()).orElseThrow().target()).isEqualTo(target);
        fenced(()->lifecycle.prepareRemoteCommit(f.attempt(),1,old.remoteAllocationId(),manifest()));fenced(()->lifecycle.prepareRemoteCommit(next.id(),1,old.remoteAllocationId(),manifest()));
        assertThat(runtimes.result(f.task())).isEmpty();assertThat(executions.taskDetail(f.child()).task().state()).isEqualTo("WAITING");
    }
    @Test void databaseRejectsFabricatedPodResultAndLateOutputMetadataCannotBypassContract() throws Exception {
        var f=fixture();var r=plan(f);lifecycle.observeRemote(status(f.attempt(),1,RemoteStatus.State.RUNNING,null,List.of()));
        var tooLarge=new RemoteFile("output",1048577,"a".repeat(64),"application/json");
        assertThatThrownBy(()->lifecycle.observeRemote(status(f.attempt(),2,RemoteStatus.State.SUCCEEDED,null,List.of(tooLarge)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(remotes.find(r.remoteAllocationId()).orElseThrow().providerRevision()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.task_result(id,task_id,attempt_id,runtime_id,epoch,producer_pod_uid,manifest_digest,created_at) VALUES (?,?,?,?,1,?,?,now())",
            UUID.randomUUID(),f.task(),f.attempt(),r.id(),UUID.randomUUID(),"sha256:"+"a".repeat(64))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        succeeded(f);var wrong=new ResultManifest(List.of(new ResultManifest.Output("output",2,"b".repeat(64),"application/json","fixture-version")));
        fenced(()->new ArtifactCommitService(lifecycle,receipts()).commitRemote(f.attempt(),1,r.remoteAllocationId(),wrong));assertThat(runtimes.result(f.task())).isEmpty();
    }
    @Test void previouslyVerifiedPermitCannotCommitAfterAReplacementAttemptStarts() throws Exception {
        var f=fixture();var old=plan(f);var done=succeeded(f);var permit=lifecycle.prepareRemoteCommit(f.attempt(),1,old.remoteAllocationId(),manifest());
        lifecycle.observeFailure(f.attempt(),"RUNTIME_LOST");lifecycle.observeRemote(done);completeCreate(f.attempt());clock.advance(2);assertThat(lifecycle.retryTask(f.task())).isTrue();
        var next=executions.taskDetail(f.task()).attempts().getFirst();lifecycle.observeRemote(status(next.id(),1,RemoteStatus.State.RUNNING,null,List.of()));
        var outputs=manifest().outputs().stream().map(o->new TaskResult.Output(o.port(),receipts().verify(o.content(f.task(),f.attempt()),o.versionId()))).toList();
        fenced(()->lifecycle.commitVerified(permit,outputs));assertThat(runtimes.result(f.task())).isEmpty();assertThat(executions.taskDetail(f.task()).attempts().getFirst().epoch()).isEqualTo(2);
    }
    @Test void expiredLeaseFencesResultButStillWaitsForActualProviderTermination() throws Exception {
        var f=fixture();var r=plan(f);clock.advance(121);lifecycle.observeRemote(status(f.attempt(),1,RemoteStatus.State.RUNNING,null,List.of()));
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().desiredState()).isEqualTo("STOPPED");assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isNotEqualTo("TERMINATED");
        fenced(()->lifecycle.prepareRemoteCommit(f.attempt(),1,r.remoteAllocationId(),manifest()));
        lifecycle.observeRemote(status(f.attempt(),2,RemoteStatus.State.FAILED,"LEASE_EXPIRED",List.of()));
        assertThat(runtimes.byAttempt(f.attempt()).orElseThrow().observedState()).isEqualTo("TERMINATED");assertThat(executions.taskDetail(f.task()).task().state()).isEqualTo("FAILED");assertThat(runtimes.result(f.task())).isEmpty();
    }
    @Test void providerCannotForgetAnAlreadyObservedReservationWhenCancelling() throws Exception {
        var f=fixture();plan(f);var reserved=status(f.attempt(),1,RemoteStatus.State.ALLOCATED,null,List.of());lifecycle.observeRemote(reserved);executions.cancelRun(f.run().id(),"{}");
        fenced(()->lifecycle.observeRemote(new RemoteStatus(reserved.identity(),2,null,RemoteStatus.State.CANCELLED,null,null,"SYNTHETIC",List.of())));
        assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLING");
        lifecycle.observeRemote(status(f.attempt(),2,RemoteStatus.State.CANCELLED,null,List.of()));assertThat(executions.detail(f.run().id()).run().state()).isEqualTo("CANCELLED");
    }
}
