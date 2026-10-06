package io.edgeai.app.service;

import io.edgeai.domain.execution.*;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.workflow.Dag;
import io.edgeai.app.support.StreamRunInput;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.support.WorkflowInput.*;
import static io.edgeai.app.service.WorkflowService.error;

@Service
public class ExecutionService {
    private final ExecutionRepository repository;
    private final WorkflowRepository workflows;
    private final NodeRepository nodes;
    private final RuntimeRepository runtimes;
    private final Clock clock;
    private final OffloadRepository offloads;
    private final TelemetryRepository telemetry;
    private final RuntimeLifecycleService lifecycle;
    private final boolean runtimeEnabled;
    private final String runtimeNamespace;
    private final RemoteProvider remoteProvider;
    private final StreamRunService streams;
    public ExecutionService(ExecutionRepository repository,WorkflowRepository workflows,NodeRepository nodes,RuntimeRepository runtimes,OffloadRepository offloads,TelemetryRepository telemetry,Clock clock,
            RuntimeLifecycleService lifecycle,@Value("${edgeai.runtime.enabled:false}") boolean runtimeEnabled,@Value("${edgeai.runtime.namespace:edgeai-runtimes}") String runtimeNamespace,RemoteProvider remoteProvider,StreamRunService streams) {
        this.repository=repository;this.workflows=workflows;this.nodes=nodes;this.runtimes=runtimes;this.offloads=offloads;this.telemetry=telemetry;this.clock=clock;
        this.lifecycle=lifecycle;this.runtimeEnabled=runtimeEnabled;this.runtimeNamespace=runtimeNamespace;
        this.remoteProvider=remoteProvider;
        this.streams=streams;
    }
    @Transactional
    public Creation<WorkflowRun> create(String key,String body) {
        UUID idempotency=uuid(key);var input=runRequest(body);
        var taskExecutions=input.containsKey("taskExecutions")?taskExecutions(input.get("taskExecutions")):Map.<String,Object>of();
        var streamInputs=input.containsKey("streamInputs")?StreamRunInput.parse(input.get("streamInputs")):List.<StreamRunInput>of();
        var retry=input.containsKey("retry")?retryPolicy(input.get("retry")):RetryPolicy.disabled();
        var offload=offloadPolicy(input.get("offload"));String offloadJson=offload==null?null:JSON.canonical(input.get("offload"));
        UUID versionId=uuid(input.get("workflowVersionId"));var parameters=parameters(input.get("parameters"));
        if(!(input.get("execution") instanceof Map<?,?> policy)) throw new IllegalArgumentException("Execution policy required");
        String mode=text(policy.get("mode"),8);UUID nodeId;UUID vdId=null;String providerKey=null;
        if(mode.equals("AUTO")) { object(policy,"mode");nodeId=null; }
        else if(mode.equals("NODE")) { object(policy,"mode","nodeId");nodeId=uuid(policy.get("nodeId")); }
        else if(mode.equals("REMOTE")){object(policy,"mode","providerKey");providerKey=text(policy.get("providerKey"),63);nodeId=null;}
        else if(mode.equals("VD")){object(policy,"mode","vdId");vdId=uuid(policy.get("vdId"));nodeId=null;}
        else throw new IllegalArgumentException("Execution mode must be AUTO, NODE, REMOTE or VD");
        var normalized=new TreeMap<String,Object>(Map.of("workflowVersionId",versionId.toString(),"parameters",parameters,
            "execution",nodeId==null?Map.of("mode",mode):Map.of("mode",mode,"nodeId",nodeId.toString())));
        if(providerKey!=null)normalized.put("execution",Map.of("mode",mode,"providerKey",providerKey));
        if(vdId!=null)normalized.put("execution",Map.of("mode",mode,"vdId",vdId.toString()));
        if(!retry.equals(RetryPolicy.disabled()))normalized.put("retry",document(retry));
        if(offload!=null)normalized.put("offload",JSON.decode(offloadJson));
        if(!streamInputs.isEmpty())normalized.put("streamInputs",streamInputs.stream().map(StreamRunInput::document).toList());
        if(!taskExecutions.isEmpty())normalized.put("taskExecutions",taskExecutions);
        String digest=JSON.digest("edgeai-run-create-v1",normalized);
        var existing=repository.byIdempotencyKey(idempotency);
        if(existing.isPresent()) return replay(existing.get(),digest);
        if(providerKey!=null && !runtimeEnabled)throw error(503,"RUNTIME_DISABLED","Remote 실행은 실행 worker와 저장소 설정을 먼저 활성화해야 합니다.");
        if(vdId!=null && offload!=null)throw error(409,"VD_AUTOMATIC_OFFLOAD_UNSUPPORTED","VD 자원 측정은 공유 컨테이너 값이므로 작업별 자동 전환을 설정할 수 없습니다.");
        var version=workflows.version(versionId).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","발행된 DAG 버전이 없습니다."));
        var dag=storedDag(version.dagJson());
        var definitions=workflows.definitions(versionId);
        var keys=definitions.stream().map(io.edgeai.domain.workflow.TaskDefinition::key).collect(java.util.stream.Collectors.toSet());
        if(!keys.containsAll(taskExecutions.keySet()))throw error(400,"TASK_PLACEMENT_INVALID","작업별 실행 위치는 발행된 DAG의 작업 키를 참조해야 합니다.");
        for(var value:taskExecutions.values()) {
            var placement=(Map<?,?>)value;
            if(placement.get("mode").equals("NODE") && nodes.find(uuid(placement.get("nodeId"))).isEmpty())
                throw error(404,"NODE_NOT_FOUND","작업별 실행 위치에서 참조할 노드를 찾을 수 없습니다.");
        }
        boolean stream=!streamInputs.isEmpty() || streams.streaming(dag);
        var vdProfiles=new TreeMap<UUID,Set<UUID>>(Comparator.comparing(UUID::toString));
        if(vdId!=null)vdProfiles.put(vdId,new HashSet<>());
        var taskRemoteTargets=new TreeMap<String,io.edgeai.domain.remote.RemoteTarget>();
        for(var definition:definitions) {
            var placement=(Map<?,?>)taskExecutions.getOrDefault(definition.key(),policy);
            String targetMode=(String)placement.get("mode");
            if(stream && targetMode.equals("REMOTE"))
                throw error(409,"STREAM_TARGET_UNSUPPORTED","STREAM Run의 Remote 배치에는 별도 스트림 실행 연결이 필요합니다.");
            if(targetMode.equals("VD")) {
                if(offload!=null)throw error(409,"VD_AUTOMATIC_OFFLOAD_UNSUPPORTED","VD 공유 자원을 작업별 자동 전환에 사용할 수 없습니다.");
                vdProfiles.computeIfAbsent(uuid(placement.get("vdId")),id->new HashSet<>()).add(definition.serviceProfileVersionId());
            } else if(targetMode.equals("REMOTE")) {
                if(!runtimeEnabled)throw error(503,"RUNTIME_DISABLED","Remote 실행은 실행 worker와 저장소 설정을 먼저 활성화해야 합니다.");
                if(offload!=null)throw error(409,"REMOTE_TELEMETRY_UNSUPPORTED","Remote 배치에는 Kubernetes 측정 기반 자동 전환을 사용할 수 없습니다.");
                if(taskExecutions.containsKey(definition.key()))taskRemoteTargets.put(definition.key(),remoteProvider.select((String)placement.get("providerKey")));
            }
        }
        var remoteTarget=providerKey==null?null:remoteProvider.select(providerKey);
        if(remoteTarget!=null && offload!=null)throw error(409,"REMOTE_TELEMETRY_UNSUPPORTED","현재 자동 전환 정책은 Kubernetes의 실행 측정을 사용합니다. Remote는 명시적 전환을 사용하세요.");
        if(nodeId!=null && nodes.find(nodeId).isEmpty()) throw error(404,"NODE_NOT_FOUND","실행 정책에서 참조할 노드를 찾을 수 없습니다.");
        for(var entry:vdProfiles.entrySet())lifecycle.validateVDTaskProfiles(entry.getKey(),entry.getValue(),runtimeNamespace);
        var sessions=stream?streams.pin(streamInputs,mode):Map.<UUID,io.edgeai.domain.device.DeviceSession>of();
        if(runtimeEnabled)lifecycle.validateRequest(versionId,JSON.canonical(parameters),stream);
        if(offload!=null)lifecycle.validateAutomaticOffload(versionId,stream);
        var now=clock.instant();var run=new WorkflowRun(UUID.randomUUID(),versionId,idempotency,digest,mode,nodeId,JSON.canonical(parameters),retry,offloadJson,"PENDING",now,now,remoteTarget,vdId,JSON.canonical(taskExecutions));
        if(!repository.create(run)) return replay(repository.byIdempotencyKey(idempotency).orElseThrow(),digest);
        repository.initialize(run,definitions,stream?Set.of():dag.roots(),taskRemoteTargets);
        if(stream)streams.configure(run,runtimeNamespace,streamInputs,sessions);
        if(runtimeEnabled)lifecycle.startRun(run.id(),runtimeNamespace);
        return new Creation<>(repository.run(run.id(),false).orElseThrow(),true);
    }
    private Creation<WorkflowRun> replay(WorkflowRun run,String digest) {
        if(!run.requestDigest().equals(digest)) throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 실행 입력이 있습니다.");
        return new Creation<>(run,false);
    }
    public List<WorkflowRun> list(int limit,int offset) { WorkflowService.page(limit,offset);return repository.runs(limit+1,offset); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RunSnapshot detail(UUID id) { return new RunSnapshot(run(id,false),repository.tasks(id)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<io.edgeai.app.dto.PlacementResponse> placements(UUID runId) {
        run(runId,false);
        return repository.tasks(runId).stream().map(task -> {
            var attempts=repository.attempts(task.id());
            var latest=attempts.isEmpty()?null:attempts.getFirst();
            var runtime=latest==null?null:runtimes.byAttempt(latest.id()).orElse(null);
            return io.edgeai.app.dto.PlacementResponse.from(task,latest,runtime);
        }).toList();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TaskSnapshot taskDetail(UUID id) { return snapshot(task(id)); }
    private TaskSnapshot snapshot(Task task) {
        var attempts=repository.attempts(task.id());
        var latest=attempts.isEmpty()?null:telemetry.recent(attempts.getFirst().id(),1).stream().findFirst().orElse(null);
        return new TaskSnapshot(task,attempts,offloads.forTask(task.id()),latest);
    }
    @Transactional
    public WorkflowRun cancelRun(UUID id,String body) {
        parse(body);var run=run(id,true);
        if(Set.of("SUCCEEDED","FAILED").contains(run.state())) throw error(409,"CANNOT_CANCEL","완료된 실행 결과는 취소로 덮어쓸 수 없습니다.");
        if(run.state().equals("CANCELLED")) return run;
        var now=clock.instant();
        for(var task:repository.tasks(id)) {repository.cancelTask(task.id(),"CANCELLED","RUN_CANCELLED",now);offloads.cancelForTask(task.id(),now);}
        runtimes.stopForRun(id,now);
        repository.reconcileRunState(id,now);return run(id,false);
    }
    @Transactional
    public TaskSnapshot cancelTask(UUID id,String body) {
        parse(body);var initial=task(id);var run=run(initial.runId(),true);var task=task(id);
        if(Set.of("SUCCEEDED","FAILED").contains(task.state())) throw error(409,"CANNOT_CANCEL","완료된 작업 결과는 취소로 덮어쓸 수 없습니다.");
        if(Set.of("CANCELLED","SKIPPED").contains(task.state())) return snapshot(task);
        var descendants=streams.affected(run,task.key());
        var now=clock.instant();repository.cancelTask(id,"CANCELLED","TASK_CANCELLED",now);offloads.cancelForTask(id,now);
        for(var child:repository.tasks(run.id())) if(!child.id().equals(id) && descendants.contains(child.key())) {repository.cancelTask(child.id(),"SKIPPED","UPSTREAM_CANCELLED",now);offloads.cancelForTask(child.id(),now);}
        runtimes.stopForRun(run.id(),now);
        repository.reconcileRunState(run.id(),now);return snapshot(task(id));
    }
    private WorkflowRun run(UUID id,boolean lock) { return repository.run(id,lock).orElseThrow(()->error(404,"RUN_NOT_FOUND","실행 요청을 찾을 수 없습니다.")); }
    private Task task(UUID id) { return repository.task(id).orElseThrow(()->error(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.")); }
}
