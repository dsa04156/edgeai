package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ServiceExecutionInput;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.ResourceRequirements;
import io.edgeai.domain.runtime.ServiceExecutionSpec;
import io.edgeai.domain.scheduling.*;
import io.edgeai.domain.workflow.Dag;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.service.WorkflowService.error;
import static io.edgeai.app.support.WorkflowInput.*;

/**
 * Measured artifact-store BATCH profiles and the per-Task minimum-cost initial placement derived from them.
 * Eligibility uses the observed inventory only: allocatable is total node capacity, not free capacity or a reservation,
 * taints/reservations are not observed, and kube-scheduler keeps the final admission decision.
 */
@Service
public class SchedulingService {
    public static final String METHOD="ARTIFACT_PROFILE_MIN_COST";
    private static final int MAX_PROFILE_BYTES=1048576;
    private final SchedulingRepository repository;
    private final WorkflowRepository workflows;
    private final ProfileRepository profiles;
    private final NodeRepository nodes;
    private final RuntimeLifecycleService lifecycle;
    private final Clock clock;
    public SchedulingService(SchedulingRepository repository,WorkflowRepository workflows,ProfileRepository profiles,NodeRepository nodes,RuntimeLifecycleService lifecycle,Clock clock) {
        this.repository=repository;this.workflows=workflows;this.profiles=profiles;this.nodes=nodes;this.lifecycle=lifecycle;this.clock=clock;
    }
    /** Server-selected placement for a new Run plus the document persisted as its decision. */
    public record Decision(SchedulingProfile profile,Map<String,Object> taskExecutions,Map<String,Object> document) {}

    @Transactional
    public io.edgeai.domain.repository.Creation<SchedulingProfile> register(String key,String body) {
        UUID idempotency=uuid(key);var document=invalid(()->normalize(JSON.parse(body,MAX_PROFILE_BYTES)));
        String digest=JSON.digest("edgeai-scheduling-profile-v1",document);
        repository.lockProfileKey(idempotency);
        var existing=repository.profileByIdempotencyKey(idempotency);
        if(existing.isPresent()) {
            if(!existing.get().digest().equals(digest))throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 측정 프로파일이 있습니다.");
            return new io.edgeai.domain.repository.Creation<>(existing.get(),false);
        }
        Instant now=clock.instant(),measuredAt=Instant.parse((String)document.get("measuredAt")),validUntil=Instant.parse((String)document.get("validUntil"));
        if(measuredAt.isAfter(now))throw invalid("측정 시각은 현재보다 미래일 수 없습니다.");
        UUID versionId=UUID.fromString((String)document.get("workflowVersionId"));
        var dag=supportedDag(versionId);
        @SuppressWarnings("unchecked") var tasks=(Map<String,Map<String,Map<String,Object>>>)document.get("tasks");
        if(!tasks.keySet().equals(keys(dag)))throw invalid("tasks는 발행된 DAG의 모든 작업 키와 정확히 일치해야 합니다.");
        var known=new HashSet<String>();
        for(var task:dag.tasks()) {
            var costs=tasks.get(task.key());var profiled=costs.get("computeMs").keySet();
            if(!costs.get("downloadMs").keySet().equals(inputs(dag,task.key())))
                throw invalid("downloadMs는 작업에 연결된 BATCH 입력 포트와 정확히 일치해야 합니다.");
            if(!costs.get("uploadMs").keySet().equals(spec(task).outputs().keySet()))
                throw invalid("uploadMs는 SERVICE의 모든 출력 포트와 정확히 일치해야 합니다.");
            for(String direction:List.of("downloadMs","uploadMs"))for(var port:costs.get(direction).values())
                if(!((Map<?,?>)port).keySet().equals(profiled))throw invalid("포트별 측정 노드는 computeMs 노드와 같아야 합니다. 누락 비용을 0으로 채우지 않습니다.");
            known.addAll(profiled);
        }
        for(String node:known)if(nodes.find(UUID.fromString(node)).isEmpty())throw error(404,"NODE_NOT_FOUND","측정 프로파일에서 참조한 노드를 찾을 수 없습니다.");
        var profile=new SchedulingProfile(UUID.randomUUID(),versionId,idempotency,digest,measuredAt,validUntil,JSON.canonical(document),now);
        repository.create(profile);
        return new io.edgeai.domain.repository.Creation<>(profile,true);
    }
    @Transactional(readOnly=true)
    public SchedulingProfile profile(UUID id) {
        return repository.profile(id).orElseThrow(()->error(404,"SCHEDULING_PROFILE_NOT_FOUND","측정 프로파일을 찾을 수 없습니다."));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(UUID id,String body) {
        var input=parse(body,"parameters");
        return plan(profile(id),parameters(input.get("parameters")),clock.instant()).document();
    }
    /** Called inside Run creation after idempotency replay; the caller stores the decision in the same transaction. */
    public Decision decide(UUID profileId,UUID versionId,Map<?,?> parameters) {
        var profile=profile(profileId);
        if(!profile.workflowVersionId().equals(versionId))throw error(409,"SCHEDULING_PROFILE_MISMATCH","측정 프로파일과 실행할 DAG 버전이 다릅니다.");
        return plan(profile,parameters,clock.instant());
    }
    public void record(UUID runId,Decision decision,Instant now) {
        repository.record(new SchedulingDecision(runId,decision.profile().id(),decision.profile().digest(),METHOD,JSON.canonical(decision.document()),now));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> decision(UUID runId) {
        var decision=repository.decision(runId).orElseThrow(()->error(404,"SCHEDULING_DECISION_NOT_FOUND","측정 프로파일 기반 배치로 생성된 실행이 아닙니다."));
        @SuppressWarnings("unchecked") var document=new TreeMap<String,Object>((Map<String,Object>)JSON.decode(decision.decisionJson()));
        var effective=new TreeMap<String,Object>();
        ((Map<?,?>)document.get("placements")).forEach((task,node)->effective.put((String)task,Map.of("mode","NODE","nodeId",node)));
        document.put("runId",runId.toString());document.put("decidedAt",decision.createdAt().toString());
        document.put("effectiveTaskExecutions",effective);
        document.put("effectiveTaskExecutionsNote","server-selected initial placement; actual Attempt locations after retry/offload are in /placements");
        return document;
    }

    private Decision plan(SchedulingProfile profile,Map<?,?> parameters,Instant now) {
        if(!now.isBefore(profile.validUntil()))throw error(409,"SCHEDULING_PROFILE_EXPIRED","측정 프로파일 유효기간이 지났습니다. 새로 측정해 등록하세요.");
        @SuppressWarnings("unchecked") var document=(Map<String,Object>)JSON.decode(profile.documentJson());
        if(!JSON.canonical(parameters).equals(JSON.canonical(document.get("parameters"))))
            throw error(409,"SCHEDULING_PROFILE_MISMATCH","실행 parameters가 측정 조건과 다릅니다.");
        var dag=storedDag(workflows.version(profile.workflowVersionId()).orElseThrow().dagJson());
        @SuppressWarnings("unchecked") var tasks=(Map<String,Map<String,Map<String,Object>>>)document.get("tasks");
        var inventory=inventory();
        var costs=new TreeMap<String,ArtifactPathPlanner.TaskCosts>();var candidates=new TreeMap<String,Object>();
        for(var task:dag.tasks()) {
            var spec=spec(task);var measured=tasks.get(task.key());var totals=new HashMap<UUID,BigDecimal>();var listed=new ArrayList<Map<String,Object>>();
            var ids=new TreeSet<String>(measured.get("computeMs").keySet());inventory.values().stream().filter(n->!n.status(now).equals("REMOVED")).forEach(n->ids.add(n.id().toString()));
            for(String id:ids) {
                var reasons=reasons(inventory.get(UUID.fromString(id)),spec,now);var entry=new LinkedHashMap<String,Object>();entry.put("nodeId",id);
                if(measured.get("computeMs").containsKey(id)) {
                    var total=decimal(measured.get("computeMs").get(id));
                    for(String direction:List.of("downloadMs","uploadMs"))for(var port:measured.get(direction).values())total=total.add(decimal(((Map<?,?>)port).get(id)));
                    entry.put("costMs",total);
                    if(reasons.isEmpty())totals.put(UUID.fromString(id),total);
                } else reasons.add("NOT_PROFILED");
                entry.put("eligible",reasons.isEmpty());entry.put("reasons",reasons);listed.add(entry);
            }
            if(totals.isEmpty())throw error(409,"SCHEDULING_NO_CANDIDATE","작업 "+task.key()+"에 측정되고 현재 조건에 맞는 노드가 없습니다.");
            var parents=new HashSet<String>();dag.dependencies().forEach(e->{if(e.toTask().equals(task.key()))parents.add(e.fromTask());});
            costs.put(task.key(),new ArtifactPathPlanner.TaskCosts(parents,totals));candidates.put(task.key(),listed);
        }
        var plan=ArtifactPathPlanner.plan(costs);
        var placements=new TreeMap<String,Object>();var executions=new TreeMap<String,Object>();
        plan.placements().forEach((task,node)->{placements.put(task,node.toString());executions.put(task,Map.of("mode","NODE","nodeId",node.toString()));});
        var result=new TreeMap<String,Object>();
        result.put("profileId",profile.id().toString());result.put("profileDigest",profile.digest());result.put("workflowVersionId",profile.workflowVersionId().toString());
        result.put("method",METHOD);result.put("predicted",true);
        result.put("predictedCriticalPathMs",plan.criticalPathMs());result.put("criticalPath",plan.criticalPath());
        result.put("predictedCommitMs",new TreeMap<>(plan.commits()));
        result.put("placements",placements);result.put("candidates",candidates);
        result.put("costModel","sequential download+compute+upload per Task through the artifact store; no queueing, contention, Pod start or image pull");
        result.put("admission","kube-scheduler");result.put("evaluatedAt",now.toString());
        return new Decision(profile,executions,result);
    }
    private List<String> reasons(ExecutionNode node,ServiceExecutionSpec spec,Instant now) {
        var reasons=new ArrayList<String>();
        if(node==null) {reasons.add("NOT_READY");return reasons;}
        if(!node.status(now).equals("READY"))reasons.add("NOT_READY");
        if(!node.operatingSystem().equals("linux"))reasons.add("OS");
        if(!spec.architectures().contains(node.architecture()))reasons.add("ARCHITECTURE");
        var labels=parameters(JSON.decode(node.labelsJson()));
        if(spec.nodeSelector().entrySet().stream().anyMatch(e->!e.getValue().equals(labels.get(e.getKey()))))reasons.add("NODE_SELECTOR");
        var allocatable=parameters(JSON.decode(node.allocatableJson()));
        for(var request:new TreeMap<>(spec.resources().requests()).entrySet()) {
            String reason=switch(request.getKey()){case "cpu"->"ALLOCATABLE_CPU";case "memory"->"ALLOCATABLE_MEMORY";default->"ALLOCATABLE_RESOURCE";};
            try {
                var capacity=ResourceRequirements.quantity(request.getKey(),(String)allocatable.get(request.getKey()));
                if(capacity.compareTo(ResourceRequirements.quantity(request.getKey(),request.getValue()))<0 && !reasons.contains(reason))reasons.add(reason);
            } catch(IllegalArgumentException|ClassCastException e) { if(!reasons.contains("ALLOCATABLE_RESOURCE"))reasons.add("ALLOCATABLE_RESOURCE"); }
        }
        return reasons;
    }
    private Map<UUID,ExecutionNode> inventory() {
        var result=new HashMap<UUID,ExecutionNode>();
        for(int offset=0;;offset+=100) {
            var page=nodes.list(100,offset);page.forEach(n->result.put(n.id(),n));
            if(page.size()<100)return result;
        }
    }
    private Dag supportedDag(UUID versionId) {
        var version=workflows.version(versionId).orElseThrow(()->error(404,"WORKFLOW_NOT_FOUND","발행된 DAG 버전이 없습니다."));
        var dag=storedDag(version.dagJson());
        try {
            for(var task:dag.tasks())if(spec(task).stream()!=null)throw new IllegalArgumentException("stream");
            if(dag.dependencies().stream().anyMatch(e->e.mode()==Dag.Mode.STREAM))throw new IllegalArgumentException("stream");
            lifecycle.validateDag(versionId);
        } catch(IllegalArgumentException e) {
            throw error(409,"SCHEDULING_UNSUPPORTED","측정 기반 배치는 strict edgeai/v1 SERVICE의 BATCH artifact DAG만 지원합니다.");
        }
        return dag;
    }
    private ServiceExecutionSpec spec(Dag.Node task) {
        return ServiceExecutionInput.parseSpec(profiles.find(task.serviceProfileVersionId()).orElseThrow().specJson());
    }
    private static Set<String> keys(Dag dag) { var keys=new HashSet<String>();dag.tasks().forEach(t->keys.add(t.key()));return keys; }
    /** The Runner downloads only artifacts of BATCH edges; optional unconnected ports and run parameters are not transfers. */
    private static Set<String> inputs(Dag dag,String task) {
        var ports=new HashSet<String>();
        dag.dependencies().forEach(e->{if(e.mode()==Dag.Mode.BATCH && e.toTask().equals(task))ports.add(e.toPort());});
        return ports;
    }
    private static Map<String,Object> normalize(Object value) {
        var root=object(value,"workflowVersionId","measuredAt","validUntil","provenance","parameters","tasks");
        var provenance=object(root.get("provenance"),"kind","evidenceId","sampleCount");
        if(!"MEASURED_BATCH_ARTIFACT".equals(provenance.get("kind")))throw new IllegalArgumentException("provenance.kind");
        if(!(provenance.get("sampleCount") instanceof Number count) || new BigDecimal(count.toString()).stripTrailingZeros().scale()>0 || new BigDecimal(count.toString()).signum()<1)
            throw new IllegalArgumentException("sampleCount");
        Instant measuredAt=Instant.parse(text(root.get("measuredAt"),40)),validUntil=Instant.parse(text(root.get("validUntil"),40));
        if(!validUntil.isAfter(measuredAt))throw new IllegalArgumentException("validUntil");
        var tasks=new TreeMap<String,Object>();
        parameters(root.get("tasks")).forEach((key,costs)->{
            var task=object(costs,"computeMs","downloadMs","uploadMs");var compute=nodeCosts(task.get("computeMs"));
            if(compute.isEmpty())throw new IllegalArgumentException("computeMs");
            tasks.put(text(key,100),Map.of("computeMs",compute,"downloadMs",portCosts(task.get("downloadMs")),"uploadMs",portCosts(task.get("uploadMs"))));
        });
        var result=new TreeMap<String,Object>();
        result.put("workflowVersionId",uuid(root.get("workflowVersionId")).toString());
        result.put("measuredAt",measuredAt.toString());result.put("validUntil",validUntil.toString());
        result.put("provenance",Map.of("kind","MEASURED_BATCH_ARTIFACT","evidenceId",text(provenance.get("evidenceId"),200),"sampleCount",new BigDecimal(provenance.get("sampleCount").toString()).longValueExact()));
        result.put("parameters",parameters(root.get("parameters")));result.put("tasks",tasks);
        return result;
    }
    private static Map<String,Object> portCosts(Object value) {
        var result=new TreeMap<String,Object>();
        parameters(value).forEach((port,costs)->result.put(text(port,100),nodeCosts(costs)));
        return result;
    }
    private static Map<String,Object> nodeCosts(Object value) {
        var result=new TreeMap<String,Object>();
        parameters(value).forEach((node,ms)->{
            if(!(ms instanceof Number number))throw new IllegalArgumentException("ms");
            var decimal=new BigDecimal(number.toString());
            if(decimal.signum()<0 || Double.isInfinite(decimal.doubleValue()))throw new IllegalArgumentException("ms");
            result.put(uuid(node).toString(),decimal);
        });
        return result;
    }
    private static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
    private static <T> T invalid(java.util.function.Supplier<T> parse) {
        try { return parse.get(); }
        catch(IllegalArgumentException|ArithmeticException|java.time.format.DateTimeParseException e) { throw invalid("측정 프로파일 필드·UUID·시각·비음수 ms·표본 수를 확인하세요."); }
    }
    private static ControlPlaneException invalid(String message) { return error(400,"SCHEDULING_PROFILE_INVALID",message); }
}
