package io.edgeai.app.service;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.domain.remote.*;
import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.RuntimeInstance;
import io.edgeai.domain.storage.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Idempotent provider commands plus periodic inspection. All remote/storage I/O is outside DB transactions. */
public final class RemoteWorker {
    private final RuntimeRepository runtimes;
    private final RuntimeLifecycleService lifecycle;
    private final ArtifactCommitService commits;
    private final ArtifactFiles files;
    private final RemoteProvider provider;
    private final RuntimeSettings settings;
    private final Clock clock;
    public RemoteWorker(RuntimeRepository runtimes,RuntimeLifecycleService lifecycle,ArtifactCommitService commits,ArtifactFiles files,RemoteProvider provider,RuntimeSettings settings,Clock clock){
        this.runtimes=runtimes;this.lifecycle=lifecycle;this.commits=commits;this.files=files;this.provider=provider;this.settings=settings;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${edgeai.remote.command-poll-ms:1000}")
    public void commands(){try{for(int i=0;i<10 && dispatchOne();i++);}catch(RuntimeException e){log("Remote command polling failed",e);}}
    public boolean dispatchOne() {
        var leased=runtimes.leaseRemoteCommand(settings.namespace(),UUID.randomUUID(),clock.instant(),Duration.ofMinutes(5));
        if(leased.isEmpty())return false;var command=leased.get();var initial=runtimes.runtime(command.runtimeId()).orElseThrow();
        try {
            var dispatch=lifecycle.remoteDispatch(initial.attemptId());var runtime=dispatch.runtime();
            if(command.kind().equals("CREATE")) {
                if(running(runtime)) {
                    if(!clock.instant().isBefore(runtime.expiresAt()))lifecycle.observeFailure(runtime.attemptId(),"RUNTIME_TIMEOUT");
                    else {
                        var gateway=provider.gateway(dispatch.allocation().target());var status=gateway.reserve(dispatch.work());
                        runtime=lifecycle.observeRemote(status);
                        if(running(runtime) && status.state()==RemoteStatus.State.ALLOCATED) {
                            try(var directory=new Workspace()) {
                                for(var input:dispatch.inputs()) {
                                    if(!current(runtime))break;
                                    Path file=directory.path.resolve(input.port());
                                    try{files.downloadFile(input.artifact(),file);}catch(ArtifactVerificationException e){throw new RemoteGatewayException(RemoteGatewayException.Reason.INVALID_INPUT);}
                                    if(!current(runtime))break;
                                    var declaration=dispatch.work().inputs().stream().filter(f->f.port().equals(input.port())).findFirst().orElseThrow();
                                    lifecycle.observeRemote(gateway.uploadInput(dispatch.work().identity(),declaration,file));
                                }
                                if(current(runtime))lifecycle.observeRemote(gateway.start(lifecycle.remoteStart(runtime.attemptId())));
                            }
                        }
                    }
                }
            } else {
                var gateway=provider.gateway(dispatch.allocation().target());
                runtime=lifecycle.observeRemote(gateway.cancel(dispatch.work().identity()));
                if(!runtime.observedState().equals("TERMINATED")){defer(command,1);return true;}
            }
            runtimes.finishCommand(command.id(),command.leaseOwner(),clock.instant());
        } catch(RuntimeException failure) {
            handle(initial,failure);defer(command,Math.min(30,Math.max(1,command.attempts())));log("Remote command deferred",failure);
        }
        return true;
    }
    @Scheduled(fixedDelayString="${edgeai.remote.reconcile-ms:1000}")
    public void reconcile() {
        List<RuntimeInstance> active;
        try{active=runtimes.activeRemote(settings.namespace(),1000);}catch(RuntimeException e){log("Remote polling failed",e);return;}
        for(var runtime:active)try {
            if(running(runtime) && !clock.instant().isBefore(runtime.expiresAt()))lifecycle.observeFailure(runtime.attemptId(),"RUNTIME_TIMEOUT");
            var dispatch=lifecycle.remoteDispatch(runtime.attemptId());var gateway=provider.gateway(dispatch.allocation().target());
            var observation=gateway.inspect(dispatch.work().identity());
            if(observation.isEmpty()){
                if(!dispatch.allocation().providerState().equals("UNKNOWN"))lifecycle.observeFailure(runtime.attemptId(),"RUNTIME_LOST");
                continue;
            }
            var status=observation.get();var current=lifecycle.observeRemote(status);
            if(running(current) && status.state()==RemoteStatus.State.SUCCEEDED) {
                try(var directory=new Workspace()) {
                    var outputs=new ArrayList<ResultManifest.Output>();
                    for(var output:status.outputs()) {
                        if(!current(current))break;
                        Path file=directory.path.resolve(output.port());gateway.downloadOutput(status.identity(),output,file);
                        if(!current(current))break;
                        var content=new ArtifactContent(current.taskId(),current.attemptId(),output.port(),output.sha256(),output.bytes(),output.mediaType());
                        String version=files.uploadFile(content,file);outputs.add(new ResultManifest.Output(output.port(),output.bytes(),output.sha256(),output.mediaType(),version));
                    }
                    if(current(current))commits.commitRemote(current.attemptId(),current.epoch(),current.remoteAllocationId(),new ResultManifest(outputs));
                }
            }
        } catch(RuntimeException e){handle(runtime,e);log("Remote reconciliation deferred",e);}
    }
    private boolean current(RuntimeInstance r){var current=runtimes.runtime(r.id()).orElseThrow();return running(current) && clock.instant().isBefore(current.expiresAt());}
    private static boolean running(RuntimeInstance r){return r.desiredState().equals("RUNNING");}
    private void defer(io.edgeai.domain.runtime.RuntimeCommand command,long seconds){runtimes.deferCommand(command.id(),command.leaseOwner(),clock.instant().plusSeconds(seconds),clock.instant());}
    private void handle(RuntimeInstance r,RuntimeException failure) {
        String reason=null;
        if(failure instanceof ArtifactVerificationException)reason="OUTPUT_INVALID";
        if(failure instanceof RemoteGatewayException e)reason=switch(e.reason()){
            case INVALID_INPUT -> "INPUT_INVALID";case UNSUPPORTED -> "WORKLOAD_FAILED";
            case INTEGRITY_FAILED -> "OUTPUT_INVALID";case INVALID_RESPONSE -> "OWNERSHIP_CONFLICT";default -> null;};
        if(reason!=null)try{lifecycle.observeFailure(r.attemptId(),reason);}catch(RuntimeException e){log("Remote failure recording deferred",e);}
    }
    private static void log(String message,RuntimeException error){LoggerFactory.getLogger(RemoteWorker.class).warn("{} ({})",message,error.getClass().getSimpleName());}
    private static final class Workspace implements AutoCloseable {
        final Path path;
        Workspace(){try{path=Files.createTempDirectory("edgeai-remote-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}catch(Exception e){throw new ArtifactStoreUnavailableException();}}
        public void close(){try(var files=Files.walk(path)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}catch(Exception e){throw new ArtifactStoreUnavailableException();}}
    }
}
