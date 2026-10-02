package io.edgeai.app.integration;

import io.edgeai.adapters.remote.ReferenceRemoteGateway;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.remote.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.edgeai.domain.remote.RemoteGatewayException.Reason.*;

/** A real Python provider process + SQLite + HTTP + files. No physical/OCI execution claim. */
class RemoteGatewayIntegrationTest {
    @TempDir Path directory;
    private Process provider;
    private ReferenceRemoteGateway gateway;
    private String origin;
    private final JsonDocuments json=new JsonDocuments();
    @BeforeEach void start() throws Exception {
        Files.writeString(directory.resolve("provider.token"),UUID.randomUUID().toString().replace("-",""));
        Files.setPosixFilePermissions(directory.resolve("provider.token"),PosixFilePermissions.fromString("rw-------"));
        boot();
    }
    private void boot() throws Exception {
        Path ready=directory.resolve("ready.json");Files.deleteIfExists(ready);
        provider=new ProcessBuilder("python3","../../simulator/remote_server.py","--state-dir",directory.resolve("state").toString(),
            "--token-file",directory.resolve("provider.token").toString(),"--ready-file",ready.toString(),"--fault-file",directory.resolve("faults.json").toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(!Files.exists(ready) && provider.isAlive() && System.nanoTime()<until)Thread.sleep(20);
        assertThat(provider.isAlive() && Files.exists(ready)).as("Owned reference provider must start; no credential output").isTrue();
        var document=(Map<?,?>)json.decode(Files.readString(ready));origin="http://127.0.0.1:"+document.get("port");gateway=client(Duration.ofSeconds(3));
    }
    private ReferenceRemoteGateway client(Duration timeout){return new ReferenceRemoteGateway(origin,directory.resolve("provider.token"),null,timeout,"SYNTHETIC");}
    @AfterEach void stop() throws Exception {
        if(gateway!=null)gateway.close();
        if(provider!=null && provider.isAlive()){provider.destroy();if(!provider.waitFor(8,TimeUnit.SECONDS)){provider.destroyForcibly();assertThat(provider.waitFor(5,TimeUnit.SECONDS)).isTrue();}}
    }
    private RemoteIdentity identity(){return new RemoteIdentity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1);}
    private RemoteWork work(RemoteIdentity identity,int delay,List<RemoteFile> inputs,Instant deadline) throws Exception {
        var spec=new HashMap<Object,Object>((Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        if(!inputs.isEmpty())spec.put("inputs",Map.of("input",Map.of("mediaType","application/json","maxBytes",1048576,"required",true)));
        return new RemoteWork(identity,json.canonical(spec),json.canonical(Map.of("features",List.of(99,99),"weights",List.of(2,3),"bias",1,"simulationDelayMillis",delay)),inputs,deadline);
    }
    private RemoteWork work(int delay) throws Exception{return work(identity(),delay,List.of(),Instant.now().plusSeconds(30));}
    private RemoteStatus terminal(RemoteIdentity id) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        do {var status=gateway.inspect(id).orElseThrow();if(Set.of(RemoteStatus.State.SUCCEEDED,RemoteStatus.State.FAILED,RemoteStatus.State.CANCELLED).contains(status.state()))return status;Thread.sleep(20);}while(System.nanoTime()<end);
        throw new AssertionError("Remote computation did not terminate within its bound");
    }
    private long executions(RemoteIdentity id) throws Exception {
        var query=new ProcessBuilder("python3","-c","import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute('SELECT executions FROM allocations WHERE id=?',(sys.argv[2],)).fetchone()[0])",
            directory.resolve("state/allocations.sqlite").toString(),id.allocationId().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(query.waitFor(5,TimeUnit.SECONDS)).isTrue();assertThat(query.exitValue()).isZero();return Long.parseLong(new String(query.getInputStream().readAllBytes()).strip());
    }
    private void rejected(Runnable call,RemoteGatewayException.Reason reason){assertThatThrownBy(call::run).isInstanceOfSatisfying(RemoteGatewayException.class,e->assertThat(e.reason()).isEqualTo(reason));}

    @Test void fixedInputIsTransferredAndRealOutputIsVerifiedBeforePublication() throws Exception {
        byte[] input="{\"features\":[2,1]}".getBytes();Path file=directory.resolve("input.json");Files.write(file,input);
        var metadata=new RemoteFile("input",input.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)),"application/json");
        var work=work(identity(),50,List.of(metadata),Instant.now().plusSeconds(30));
        assertThat(gateway.inspect(work.identity())).isEmpty();var reserved=gateway.reserve(work);assertThat(reserved.state()).isEqualTo(RemoteStatus.State.ALLOCATED);
        rejected(()->gateway.start(work.identity()),CONFLICT);
        gateway.uploadInput(work.identity(),metadata,file);gateway.uploadInput(work.identity(),metadata,file);
        gateway.start(work.identity());var completed=terminal(work.identity());assertThat(completed.state()).isEqualTo(RemoteStatus.State.SUCCEEDED);
        assertThat(completed.revision()).isGreaterThan(reserved.revision());assertThat(completed.sourceMode()).isEqualTo("SYNTHETIC");
        Path result=directory.resolve("result.json");gateway.downloadOutput(work.identity(),completed.outputs().getFirst(),result);
        var output=(Map<?,?>)json.decode(Files.readString(result));assertThat(output.get("score").toString()).isEqualTo("8.0");
        assertThat(output.get("features").toString()).isEqualTo("[2, 1]");assertThat(executions(work.identity())).isEqualTo(1);
        rejected(()->gateway.downloadOutput(work.identity(),completed.outputs().getFirst(),result),INVALID_INPUT);assertThat(Files.readString(result)).contains("8.0");
        assertThat(gateway.cancel(work.identity()).state()).isEqualTo(RemoteStatus.State.SUCCEEDED);
        assertThat(gateway.reserve(work).requestDigest()).isEqualTo(reserved.requestDigest());
    }
    @Test void concurrentReserveAndStartExecuteOnceAndDifferentWorkConflicts() throws Exception {
        var work=work(100);var receipts=new ArrayList<RemoteStatus>();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Future<RemoteStatus>>();for(int i=0;i<8;i++)calls.add(pool.submit(()->gateway.reserve(work)));
            for(var call:calls)receipts.add(call.get(5,TimeUnit.SECONDS));calls.clear();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->gateway.start(work.identity())));for(var call:calls)call.get(5,TimeUnit.SECONDS);
        }
        assertThat(receipts.stream().map(RemoteStatus::requestDigest).distinct()).hasSize(1);assertThat(terminal(work.identity()).state()).isEqualTo(RemoteStatus.State.SUCCEEDED);
        assertThat(executions(work.identity())).isEqualTo(1);
        var changed=new RemoteWork(work.identity(),work.serviceSpecJson(),"{}",work.inputs(),work.expiresAt());rejected(()->gateway.reserve(changed),CONFLICT);
    }
    @Test void timeoutAfterReserveRecoversByIdentityWithoutAnotherExecution() throws Exception {
        var work=work(0);Files.writeString(directory.resolve("faults.json"),"[\"reserve_timeout_once\"]");
        try(var impatient=client(Duration.ofMillis(250))){rejected(()->impatient.reserve(work),UNAVAILABLE);}
        var found=gateway.inspect(work.identity()).orElseThrow();assertThat(found.state()).isEqualTo(RemoteStatus.State.ALLOCATED);
        assertThat(gateway.reserve(work).requestDigest()).isEqualTo(found.requestDigest());gateway.start(work.identity());
        assertThat(terminal(work.identity()).state()).isEqualTo(RemoteStatus.State.SUCCEEDED);assertThat(executions(work.identity())).isEqualTo(1);
    }
    @Test void cancellationBeforeReserveIsDurableAndIdentityCannotBeReused() throws Exception {
        var work=work(0);var cancelled=gateway.cancel(work.identity());assertThat(cancelled.state()).isEqualTo(RemoteStatus.State.CANCELLED);assertThat(cancelled.requestDigest()).isNull();
        assertThat(gateway.cancel(work.identity()).revision()).isEqualTo(cancelled.revision());rejected(()->gateway.reserve(work),CONFLICT);rejected(()->gateway.start(work.identity()),CONFLICT);
        var wrong=new RemoteIdentity(work.identity().allocationId(),work.identity().runId(),work.identity().taskId(),UUID.randomUUID(),2);
        rejected(()->gateway.inspect(wrong),CONFLICT);rejected(()->gateway.cancel(wrong),CONFLICT);assertThat(executions(work.identity())).isZero();
        gateway.close();provider.destroyForcibly();assertThat(provider.waitFor(5,TimeUnit.SECONDS)).isTrue();boot();
        assertThat(gateway.inspect(work.identity()).orElseThrow().state()).isEqualTo(RemoteStatus.State.CANCELLED);rejected(()->gateway.reserve(work),CONFLICT);
    }
    @Test void cancelRunningAndLeaseExpiryNeverPublishALateOutput() throws Exception {
        var cancelled=work(1000);gateway.reserve(cancelled);gateway.start(cancelled.identity());gateway.cancel(cancelled.identity());
        assertThat(terminal(cancelled.identity()).state()).isEqualTo(RemoteStatus.State.CANCELLED);
        Thread.sleep(1100);assertThat(gateway.inspect(cancelled.identity()).orElseThrow().outputs()).isEmpty();
        var expired=work(identity(),1000,List.of(),Instant.now().plusMillis(400));gateway.reserve(expired);gateway.start(expired.identity());
        var result=terminal(expired.identity());assertThat(result.state()).isEqualTo(RemoteStatus.State.FAILED);assertThat(result.failureReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(result.outputs()).isEmpty();rejected(()->gateway.start(expired.identity()),CONFLICT);
    }
    @Test void providerRestartRetainsCompletedFilesButNeverReplaysRunningWork() throws Exception {
        var complete=work(0);gateway.reserve(complete);gateway.start(complete.identity());var done=terminal(complete.identity());
        var pending=work(10000);gateway.reserve(pending);gateway.start(pending.identity());
        gateway.close();provider.destroyForcibly();assertThat(provider.waitFor(5,TimeUnit.SECONDS)).isTrue();boot();
        var interrupted=gateway.inspect(pending.identity()).orElseThrow();assertThat(interrupted.state()).isEqualTo(RemoteStatus.State.FAILED);assertThat(interrupted.failureReason()).isEqualTo("PROVIDER_RESTART");
        assertThat(interrupted.outputs()).isEmpty();assertThat(executions(pending.identity())).isEqualTo(1);rejected(()->gateway.start(pending.identity()),CONFLICT);
        assertThat(gateway.inspect(complete.identity()).orElseThrow()).isEqualTo(done);gateway.downloadOutput(complete.identity(),done.outputs().getFirst(),directory.resolve("after-restart.json"));
    }
    @Test void corruptedOutputAndWrongInputDoNotLeavePublishedFiles() throws Exception {
        var work=work(0);gateway.reserve(work);gateway.start(work.identity());var done=terminal(work.identity());
        Path actual=directory.resolve("state").resolve(work.identity().allocationId().toString()).resolve("outputs/output");Files.writeString(actual,"corrupted");
        Path destination=directory.resolve("should-not-exist.json");rejected(()->gateway.downloadOutput(work.identity(),done.outputs().getFirst(),destination),INTEGRITY_FAILED);
        assertThat(Files.exists(destination)).isFalse();try(var paths=Files.list(directory)){assertThat(paths.noneMatch(p->p.getFileName().toString().endsWith(".part"))).isTrue();}
        var fake=new RemoteFile("input",3,"a".repeat(64),"application/json");Path input=directory.resolve("wrong.json");Files.writeString(input,"bad");
        rejected(()->gateway.uploadInput(work.identity(),fake,input),INTEGRITY_FAILED);
    }
    @Test void unsupportedCommandsAreRejectedInsteadOfExecutingArbitraryHostCode() throws Exception {
        var work=work(0);var spec=(Map<?,?>)json.decode(work.serviceSpecJson());var changed=new HashMap<Object,Object>(spec);changed.put("command",List.of("sh","-c","exit 0"));
        var unsafe=new RemoteWork(work.identity(),json.canonical(changed),work.parametersJson(),List.of(),work.expiresAt());rejected(()->gateway.reserve(unsafe),UNSUPPORTED);
        assertThat(gateway.inspect(work.identity())).isEmpty();
    }
    @Test void crashBeforeReservationCommitCanRecoverAnOrphanDirectory() throws Exception {
        var work=work(0);Path folder=directory.resolve("state").resolve(work.identity().allocationId().toString());
        Files.createDirectories(folder.resolve("inputs"));
        assertThat(gateway.reserve(work).state()).isEqualTo(RemoteStatus.State.ALLOCATED);gateway.start(work.identity());
        assertThat(terminal(work.identity()).state()).isEqualTo(RemoteStatus.State.SUCCEEDED);assertThat(executions(work.identity())).isEqualTo(1);
    }
    @Test void undeclaredInputsCannotChangeThePinnedExecution() throws Exception {
        var work=work(0);var input=new RemoteFile("input",0,"0".repeat(64),"application/json");
        var invalid=new RemoteWork(work.identity(),work.serviceSpecJson(),work.parametersJson(),List.of(input),work.expiresAt());
        rejected(()->gateway.reserve(invalid),INVALID_INPUT);assertThat(gateway.inspect(work.identity())).isEmpty();
    }
    @Test void authenticationRejectsUnknownBearerAndRotatesWithoutRestart() throws Exception {
        var work=work(0);Path unknown=directory.resolve("unknown.token");Files.writeString(unknown,UUID.randomUUID().toString().replace("-",""));
        try(var denied=new ReferenceRemoteGateway(origin,unknown,null,Duration.ofSeconds(3),"SYNTHETIC")) {
            rejected(()->denied.reserve(work),AUTH_REJECTED);rejected(()->denied.cancel(work.identity()),AUTH_REJECTED);
        }
        assertThat(gateway.inspect(work.identity())).isEmpty();gateway.reserve(work);
        Files.writeString(directory.resolve("provider.token"),UUID.randomUUID().toString().replace("-",""));
        assertThat(gateway.cancel(work.identity()).state()).isEqualTo(RemoteStatus.State.CANCELLED);assertThat(executions(work.identity())).isZero();
    }
    @Test void capacityBackpressureRetainsReservationAndCanResumeAfterDrain() throws Exception {
        var active=new ArrayList<RemoteWork>();
        for(int i=0;i<8;i++){var work=work(10000);active.add(work);gateway.reserve(work);gateway.start(work.identity());}
        var waiting=work(0);gateway.reserve(waiting);rejected(()->gateway.start(waiting.identity()),UNAVAILABLE);
        assertThat(gateway.inspect(waiting.identity()).orElseThrow().state()).isEqualTo(RemoteStatus.State.ALLOCATED);assertThat(executions(waiting.identity())).isZero();
        for(var work:active)gateway.cancel(work.identity());for(var work:active)assertThat(terminal(work.identity()).state()).isEqualTo(RemoteStatus.State.CANCELLED);
        gateway.start(waiting.identity());assertThat(terminal(waiting.identity()).state()).isEqualTo(RemoteStatus.State.SUCCEEDED);assertThat(executions(waiting.identity())).isEqualTo(1);
    }
    @Test void providerRechecksPhysicalInputsBeforeComputation() throws Exception {
        byte[] bytes="{\"features\":[1,2]}".getBytes();Path source=directory.resolve("valid-input");Files.write(source,bytes);
        var input=new RemoteFile("input",bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),"application/json");
        var work=work(identity(),0,List.of(input),Instant.now().plusSeconds(30));gateway.reserve(work);gateway.uploadInput(work.identity(),input,source);
        Files.writeString(directory.resolve("state").resolve(work.identity().allocationId().toString()).resolve("inputs/input"),"{\"features\":[9,9]}");
        rejected(()->gateway.start(work.identity()),CONFLICT);assertThat(executions(work.identity())).isZero();gateway.cancel(work.identity());
    }
}
