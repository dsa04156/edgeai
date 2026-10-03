package io.edgeai.app.service;

import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeInstance;
import io.edgeai.domain.storage.*;
import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamCheckpoint.Request;
import io.edgeai.domain.stream.StreamBrokerGateway.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.edgeai.app.support.WorkflowInput.*;

/** Authority transaction -> bounded S3 verification -> authority transaction. */
@Service
@ConditionalOnProperty(name="edgeai.runtime.enabled",havingValue="true")
public class StreamCheckpointService {
    private final StreamCheckpointRepository checkpoints;private final RuntimeRepository runtimes;
    private final ExecutionRepository executions;private final WorkflowRepository workflows;
    private final DataRouteService routes;private final RuntimeLifecycleService lifecycle;
    private final DataRouteRepository routeStore;
    private final StreamExecutionRepository streamExecutions;
    private final ArtifactStore storage;private final ArtifactFiles files;private final Clock clock;
    private final TransactionTemplate transaction;private final Semaphore verifiers=new Semaphore(2);
    private record Authority(RuntimeInstance runtime,UUID profile,List<Permission> permissions){}
    private record Permit(Authority authority,Request request,StreamCheckpoint replay){}
    private record HandoverPermit(Authority authority,StreamCheckpoint source,List<Permission> previous,boolean replay){}
    public StreamCheckpointService(StreamCheckpointRepository checkpoints,RuntimeRepository runtimes,ExecutionRepository executions,
            WorkflowRepository workflows,DataRouteService routes,RuntimeLifecycleService lifecycle,ArtifactStore storage,
            ArtifactFiles files,Clock clock,PlatformTransactionManager transactions,DataRouteRepository routeStore,StreamExecutionRepository streamExecutions){
        this.checkpoints=checkpoints;this.runtimes=runtimes;this.executions=executions;this.workflows=workflows;
        this.routes=routes;this.lifecycle=lifecycle;this.storage=storage;this.files=files;this.clock=clock;
        this.transaction=new TransactionTemplate(transactions);
        this.routeStore=routeStore;
        this.streamExecutions=streamExecutions;
    }
    public Object upload(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"checkpoint");var request=request(input.get("checkpoint"));
        var permit=transaction.execute(s->prepare(principal,request));
        if(permit.replay()!=null)return Map.of("checkpoint",receipt(permit.replay()));
        var runtime=permit.authority().runtime();var grant=storage.upload(request.content(runtime.taskId(),runtime.attemptId()));
        return Map.of("upload",Map.of("url",grant.url().toString(),"headers",grant.headers(),"expiresAt",grant.expiresAt().toString()));
    }
    public Creation<StreamCheckpoint> commit(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"checkpoint","versionId");var request=request(input.get("checkpoint"));
        String version=text(input.get("versionId"),1024);
        var permit=transaction.execute(s->prepare(principal,request));
        if(permit.replay()!=null)return new Creation<>(permit.replay(),false);
        if(!verifiers.tryAcquire())throw conflict(429,"STREAM_CHECKPOINT_BUSY");
        Path directory=null;
        try{
            var runtime=permit.authority().runtime();
            var artifact=storage.verify(request.content(runtime.taskId(),runtime.attemptId()),version);
            directory=Files.createTempDirectory("edgeai-checkpoint-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var file=directory.resolve("snapshot.json");files.downloadFile(artifact,file);
            var document=StreamCheckpointDocument.verify(file,request,permit.authority().permissions(),runtime.attemptId());
            return transaction.execute(s->{
                var current=prepare(principal,request);
                if(current.replay()!=null)return new Creation<>(current.replay(),false);
                if(!current.authority().runtime().id().equals(runtime.id()) || !current.authority().profile().equals(permit.authority().profile()))
                    throw conflict(409,"STREAM_CHECKPOINT_FENCED");
                progress(checkpoints.latest(runtime.taskId()).orElse(null),document,current.authority().permissions(),runtime.attemptId());
                UUID id=UUID.nameUUIDFromBytes(("edgeai.stream-checkpoint/v1\n"+runtime.attemptId()+"\n"+request.serial()+"\n"+request.sha256()).getBytes(StandardCharsets.US_ASCII));
                var value=new StreamCheckpoint(id,runtime.runId(),runtime.taskId(),runtime.attemptId(),runtime.id(),runtime.epoch(),principal.podUid(),
                    current.authority().profile(),request,document.revision(),document.json(),artifact,clock.instant().truncatedTo(ChronoUnit.MICROS),null);
                checkpoints.insert(value);return new Creation<>(checkpoints.byAttemptSerial(runtime.attemptId(),request.serial()).orElseThrow(),true);
            });
        }catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
        finally{
            try{if(directory!=null){Files.deleteIfExists(directory.resolve("snapshot.json"));Files.deleteIfExists(directory);}}
            catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
            finally{verifiers.release();}
        }
    }
    public Object latest(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"generationIds");var ids=generations(input.get("generationIds"));
        var value=transaction.execute(s->{
            var a=authorize(principal,ids);var current=checkpoints.latest(a.runtime().taskId()).orElse(null);
            if(current!=null && !sameScope(current,a,ids))throw conflict(409,"STREAM_CHECKPOINT_HANDOVER_REQUIRED");
            return current;
        });
        return download(value);
    }
    private Object download(StreamCheckpoint value){
        var result=new TreeMap<String,Object>();result.put("checkpoint",value==null?null:receipt(value));
        if(value!=null){var grant=storage.download(value.artifact());result.put("download",Map.of("url",grant.url().toString(),"expiresAt",grant.expiresAt().toString()));}
        return result;
    }
    public Object handover(RunnerPrincipal principal,String body){
        var input=RunnerInput.parse(body,principal,"generationIds","executionSha256");var ids=generations(input.get("generationIds"));
        String execution=text(input.get("executionSha256"),64);
        if(!execution.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid checkpoint execution digest");
        var permit=transaction.execute(s->prepareHandover(principal,ids,execution));
        if(permit.replay())return download(permit.source());
        if(!verifiers.tryAcquire())throw conflict(429,"STREAM_CHECKPOINT_BUSY");
        Path directory=null;
        try{
            directory=Files.createTempDirectory("edgeai-handover-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var source=directory.resolve("source.json");var target=directory.resolve("target.json");
            files.downloadFile(permit.source().artifact(),source);
            var runtime=permit.authority().runtime();
            var transformed=StreamCheckpointDocument.rebind(source,permit.source(),permit.previous(),permit.authority().permissions(),runtime.attemptId(),target);
            var q=transformed.request();var content=q.content(runtime.taskId(),runtime.attemptId());
            var version=files.uploadFile(content,target);var artifact=storage.verify(content,version);
            var value=transaction.execute(s->{
                var current=prepareHandover(principal,ids,execution);
                if(current.replay())return current.source();
                if(!current.source().id().equals(permit.source().id()) || !current.authority().runtime().id().equals(runtime.id()))
                    throw conflict(409,"STREAM_CHECKPOINT_STALE");
                UUID id=UUID.nameUUIDFromBytes(("edgeai.stream-checkpoint/v1\n"+runtime.attemptId()+"\n"+q.serial()+"\n"+q.sha256()).getBytes(StandardCharsets.US_ASCII));
                var document=transformed.summary();
                var checkpoint=new StreamCheckpoint(id,runtime.runId(),runtime.taskId(),runtime.attemptId(),runtime.id(),runtime.epoch(),principal.podUid(),
                    current.authority().profile(),q,document.revision(),document.json(),artifact,clock.instant().truncatedTo(ChronoUnit.MICROS),permit.source().id());
                checkpoints.insert(checkpoint);return checkpoints.byAttemptSerial(runtime.attemptId(),q.serial()).orElseThrow();
            });
            return download(value);
        }catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
        finally{
            try{if(directory!=null){Files.deleteIfExists(directory.resolve("source.json"));Files.deleteIfExists(directory.resolve("target.json"));Files.deleteIfExists(directory);}}
            catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
            finally{verifiers.release();}
        }
    }
    private static boolean sameScope(StreamCheckpoint value,Authority authority,List<UUID> ids){
        return value.attemptId().equals(authority.runtime().attemptId()) && value.serviceProfileVersionId().equals(authority.profile())
            && new HashSet<>(value.request().generationIds()).equals(new HashSet<>(ids));
    }
    private HandoverPermit prepareHandover(RunnerPrincipal principal,List<UUID> ids,String execution){
        var a=authorize(principal,ids);var source=checkpoints.latest(a.runtime().taskId()).orElseThrow(()->conflict(409,"STREAM_CHECKPOINT_MISSING"));
        if(!source.serviceProfileVersionId().equals(a.profile()) || !source.request().executionSha256().equals(execution))
            throw conflict(409,"STREAM_CHECKPOINT_EXECUTION_CHANGED");
        if(sameScope(source,a,ids))return new HandoverPermit(a,source,List.of(),true);
        if(!source.attemptId().equals(a.runtime().attemptId())){
            var old=runtimes.byAttempt(source.attemptId()).orElseThrow();
            if(!old.desiredState().equals("STOPPED") || !old.observedState().equals("TERMINATED") || old.epoch()>=a.runtime().epoch())
                throw conflict(409,"STREAM_CHECKPOINT_SOURCE_RUNNING");
        }
        var current=new HashMap<UUID,Permission>();for(var p:a.permissions())current.put(p.route().id(),p);
        var previous=new ArrayList<Permission>();
        for(var id:source.request().generationIds()){
            var old=routeStore.generation(id).orElseThrow();var next=current.remove(old.routeId());
            if(next==null)throw conflict(409,"STREAM_CHECKPOINT_ROUTES_CHANGED");
            if(!old.id().equals(next.generation().id()) && (old.closedAt()==null || old.generation()>=next.generation().generation()))
                throw conflict(409,"STREAM_CHECKPOINT_REVOCATION_REQUIRED");
            if(next.route().deviceSource() && !old.producer().equals(next.generation().producer()))
                throw conflict(409,"DEVICE_STREAM_HANDOVER_REQUIRED");
            previous.add(new Permission(next.route(),old));
        }
        if(!current.isEmpty())throw conflict(409,"STREAM_CHECKPOINT_ROUTES_CHANGED");
        return new HandoverPermit(a,source,List.copyOf(previous),false);
    }
    private Authority authorize(RunnerPrincipal principal,List<UUID> ids){
        var runtime=runtimes.byAttempt(principal.attemptId()).orElseThrow(()->conflict(409,"STREAM_CHECKPOINT_FENCED"));
        var caller=new Principal("TASK",new RouteGeneration.Actor(principal.attemptId(),principal.epoch()));
        var permissions=routes.authorizeTask(runtime.taskId(),ids,caller);
        lifecycle.authorizeProducerUntil(principal.attemptId(),principal.epoch(),principal.podUid());
        var task=executions.task(runtime.taskId()).orElseThrow();var run=executions.run(runtime.runId(),false).orElseThrow();
        var profile=workflows.definitions(run.workflowVersionId()).stream().filter(d->d.id().equals(task.definitionId())).findFirst().orElseThrow().serviceProfileVersionId();
        return new Authority(runtime,profile,permissions);
    }
    private Permit prepare(RunnerPrincipal principal,Request request){
        var a=authorize(principal,request.generationIds());var runtime=a.runtime();
        var latest=checkpoints.latest(runtime.taskId()).orElse(null);
        var replay=checkpoints.byAttemptSerial(runtime.attemptId(),request.serial()).orElse(null);
        if(replay!=null){
            if(!replay.request().equals(request) || latest==null || !latest.id().equals(replay.id()))throw conflict(409,"STREAM_CHECKPOINT_CONFLICT");
            return new Permit(a,request,replay);
        }
        if(streamExecutions.task(runtime.attemptId()).isPresent())throw conflict(409,"STREAM_CHECKPOINT_TERMINAL");
        if(!Objects.equals(request.previousId(),latest==null?null:latest.id()) || (latest!=null && request.serial()<=latest.request().serial()))
            throw conflict(409,"STREAM_CHECKPOINT_STALE");
        if(latest!=null && (!latest.attemptId().equals(runtime.attemptId()) || !latest.serviceProfileVersionId().equals(a.profile())
            || !latest.request().executionSha256().equals(request.executionSha256()) || !latest.request().generationIds().equals(request.generationIds())))throw conflict(409,"STREAM_CHECKPOINT_HANDOVER_REQUIRED");
        return new Permit(a,request,null);
    }
    private static void progress(StreamCheckpoint previous,StreamCheckpointDocument.Summary current,List<Permission> permissions,UUID attempt){
        if(previous==null)return;
        var old=(Map<?,?>)JSON.decode(previous.summaryJson());var next=(Map<?,?>)JSON.decode(current.json());
        if(current.revision()<previous.revision() || !JSON.canonical(old.get("manifest")).equals(JSON.canonical(next.get("manifest"))))
            throw conflict(409,"STREAM_CHECKPOINT_REGRESSION");
        boolean same=current.revision()==previous.revision();
        if(same && !Objects.equals(old.get("stateSha256"),next.get("stateSha256")))throw conflict(409,"STREAM_CHECKPOINT_REGRESSION");
        var before=new HashMap<String,Map<?,?>>();for(var item:(List<?>)old.get("routes")){var row=(Map<?,?>)item;before.put((String)row.get("routeId"),row);}
        var inputs=new HashSet<String>();for(var p:permissions)if(p.generation().consumer().id().equals(attempt))inputs.add(p.route().id().toString());
        for(var item:(List<?>)next.get("routes")){
            var row=(Map<?,?>)item;String id=(String)row.get("routeId");var prior=before.get(id);
            for(String field:List.of("received","committed")){
                long was=RunnerInput.integer(prior.get(field)),now=RunnerInput.integer(row.get(field));
                if(now<was || (same && field.equals(inputs.contains(id)?"committed":"received") && now!=was))throw conflict(409,"STREAM_CHECKPOINT_REGRESSION");
            }
            if(Boolean.TRUE.equals(prior.get("ended")) && !Boolean.TRUE.equals(row.get("ended")))throw conflict(409,"STREAM_CHECKPOINT_REGRESSION");
        }
    }
    private static Request request(Object value){
        var input=object(value,"previousCheckpointId","serial","sha256","bytes","executionSha256","generationIds");
        return new Request(input.get("previousCheckpointId")==null?null:uuid(input.get("previousCheckpointId")),RunnerInput.integer(input.get("serial")),
            text(input.get("sha256"),64),RunnerInput.integer(input.get("bytes")),text(input.get("executionSha256"),64),generations(input.get("generationIds")));
    }
    private static List<UUID> generations(Object value){
        if(!(value instanceof List<?> items) || items.isEmpty() || items.size()>32)throw new IllegalArgumentException("Checkpoint generation set required");
        var ids=items.stream().map(WorkflowInput::uuid).toList();if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Duplicate checkpoint generation");return ids;
    }
    public static Object receipt(StreamCheckpoint value){
        var result=new TreeMap<String,Object>();var q=value.request();
        result.put("id",value.id().toString());result.put("runId",value.runId().toString());result.put("taskId",value.taskId().toString());
        result.put("attemptId",value.attemptId().toString());result.put("epoch",value.epoch());result.put("serviceProfileVersionId",value.serviceProfileVersionId().toString());
        result.put("previousCheckpointId",q.previousId()==null?null:q.previousId().toString());result.put("serial",q.serial());result.put("revision",value.revision());
        result.put("sha256",q.sha256());result.put("bytes",q.bytes());result.put("executionSha256",q.executionSha256());result.put("generationIds",q.generationIds().stream().map(UUID::toString).toList());
        result.put("versionId",value.artifact().versionId());result.put("summary",JSON.decode(value.summaryJson()));result.put("createdAt",value.createdAt().toString());return result;
    }
    private static ControlPlaneException conflict(int status,String code){return new ControlPlaneException(status,code,"체크포인트의 현재 실행 주체·경로·순번·이전 확정본을 확인하세요.");}
}
