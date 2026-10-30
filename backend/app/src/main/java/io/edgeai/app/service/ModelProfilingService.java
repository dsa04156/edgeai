package io.edgeai.app.service;

import io.edgeai.adapters.kubernetes.KubernetesProfilingGateway;
import io.edgeai.app.config.ModelProfilingSettings;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.AiModelDocuments;
import io.edgeai.app.support.ModelProfilingDocuments;
import io.edgeai.app.support.ModelProfilingInput;
import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.repository.AiModelRepository;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.repository.ModelProfilingRepository;
import io.edgeai.domain.repository.NodeRepository;
import io.edgeai.domain.resourceprofile.WorkloadMetricsSource;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.edgeai.app.config.ModelProfilingSettings.*;
import static io.edgeai.app.support.ResourceProfileDocuments.ordered;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Measurement runs and confirmed resource profiles of registered AI models. Admission (model, node and global slots) is
 * decided in one short transaction under an advisory lock; Kubernetes, the model and Prometheus are only ever called
 * by {@link ModelProfilingReconciler} after the commit. A model is READY only with a confirmed profile, which in turn
 * can only cite a SUCCEEDED run of that model; failed or cancelled runs never replace an earlier confirmed profile.
 */
@Service
@ConditionalOnProperty(name="edgeai.ai-models.enabled",havingValue="true")
public class ModelProfilingService {
    private final AiModelRepository models;
    private final ModelProfilingRepository repository;
    private final ModelProfilingSettings settings;
    private final ModelProfilingReconciler reconciler;
    private final ObjectProvider<ProfilingGateway> gateway;
    private final ObjectProvider<InferenceLoadDriver> driver;
    private final ObjectProvider<WorkloadMetricsSource> metrics;
    private final NodeRepository nodes;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ModelProfilingService(AiModelRepository models,ModelProfilingRepository repository,ModelProfilingSettings settings,ModelProfilingReconciler reconciler,
            ObjectProvider<ProfilingGateway> gateway,ObjectProvider<InferenceLoadDriver> driver,ObjectProvider<WorkloadMetricsSource> metrics,NodeRepository nodes,
            PlatformTransactionManager transactions,Clock clock) {
        this.models=models;this.repository=repository;this.settings=settings;this.reconciler=reconciler;this.gateway=gateway;this.driver=driver;
        this.metrics=metrics;this.nodes=nodes;this.transaction=new TransactionTemplate(transactions);this.clock=clock;
    }

    private static ControlPlaneException error(int status,String code,String message){return new ControlPlaneException(status,code,message);}
    private static ControlPlaneException invalid(String code,String detail){return error(400,code,"측정 입력을 확인하세요."+(detail==null?"":" "+detail));}
    private static UUID key(String value) {
        try{var id=UUID.fromString(value);if(id.toString().equals(value))return id;}catch(RuntimeException ignored){}
        throw error(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key는 UUID여야 합니다.");
    }
    private AiModelExecution model(UUID id) {
        return models.find(id).orElseThrow(()->error(404,"AI_MODEL_NOT_FOUND","AI 모델 실행 구성을 찾을 수 없습니다."));
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> document(AiModelExecution model){return (Map<String,Object>)JSON.decode(model.documentJson());}
    @SuppressWarnings("unchecked")
    static Map<String,Object> image(AiModelExecution model){return (Map<String,Object>)document(model).get("image");}
    @SuppressWarnings("unchecked")
    static Map<String,Object> platform(AiModelExecution model){return (Map<String,Object>)image(model).get("platform");}
    @SuppressWarnings("unchecked")
    static Map<String,Object> execution(AiModelExecution model) {
        var e=(Map<String,Object>)document(model).get("execution");
        return e==null?ordered("command",null,"args",null):ordered("command",e.get("command"),"args",e.get("args"));
    }

    /** Why measurement cannot start at all (server configuration), or null. */
    public String disabledReason() {
        if(!settings.enabled())return "PROFILING_DISABLED";
        if(gateway.getIfAvailable()==null)return "KUBERNETES_DISABLED";
        if(metrics.getIfAvailable()==null)return "PROMETHEUS_DISABLED";
        if(driver.getIfAvailable()==null)return "PROFILING_DISABLED";
        return null;
    }

    // ---- options --------------------------------------------------------------------------------------------------

    public Map<String,Object> options(UUID modelId) {
        var model=model(modelId);var platform=platform(model);
        String reason=disabledReason();
        if(reason==null && !Boolean.TRUE.equals(platform.get("runnable")))reason="PLATFORM_NOT_RUNNABLE";
        var result=ordered("enabled",reason==null,"disabledReason",reason,
            "image",ordered("reference",image(model).get("platformReference"),"platform",platform.get("label")),
            "execution",execution(model));
        var limits=ordered("durationSeconds",range(MIN_DURATION,DEFAULT_DURATION,MAX_DURATION),"concurrency",range(MIN_CONCURRENCY,DEFAULT_CONCURRENCY,MAX_CONCURRENCY),
            "requestTimeoutSeconds",range(MIN_REQUEST_TIMEOUT,DEFAULT_REQUEST_TIMEOUT,MAX_REQUEST_TIMEOUT),"readinessTimeoutSeconds",range(MIN_READINESS,DEFAULT_READINESS,MAX_READINESS));
        limits.put("bodyMaxBytes",BODY_MAX_BYTES);
        limits.put("responseMaxBytes",io.edgeai.adapters.profiling.HttpInferenceLoadDriver.MAX_RESPONSE_BYTES);
        limits.put("methods",List.of("GET","POST"));
        limits.put("contentTypes",ModelProfilingInput.CONTENT_TYPES.stream().sorted().toList());
        limits.put("cpuMillicores",ordered("min",MIN_CPU,"max",MAX_CPU));
        limits.put("memoryBytes",ordered("min",MIN_MEMORY,"max",MAX_MEMORY));
        limits.put("acceleratorCount",ordered("min",MIN_ACCELERATORS,"max",MAX_ACCELERATORS));
        limits.put("minSuccessRatio",MIN_SUCCESS_RATIO);
        limits.put("minSuccessfulRequests",MIN_SUCCESSFUL_REQUESTS);
        limits.put("minSamples",io.edgeai.adapters.profiling.PrometheusWorkloadMetrics.MIN_SAMPLES);
        limits.put("loadTypes",List.of("HTTP","COMMAND"));
        limits.put("maxActiveRuns",settings.maxActiveRuns());
        result.put("limits",limits);
        var busy=new HashSet<String>();
        for(var run:repository.holding())busy.add(run.nodeName());
        var views=new ArrayList<Object>();
        var now=clock.instant();
        for(var node:allNodes()) {
            String status=node.status(now);
            if(status.equals("REMOVED"))continue;
            String incompatible=!"linux".equals(node.operatingSystem())?"OS_MISMATCH"
                :!Objects.equals(node.architecture(),platform.get("architecture"))?"ARCHITECTURE_MISMATCH":!status.equals("READY")?"NOT_READY":null;
            var view=ordered("name",node.name(),"architecture",node.architecture(),"status",status,"compatible",incompatible==null,"incompatibleReason",incompatible);
            view.put("allocatable",ordered("cpuMillicores",millicores(node.cpu()),"memoryBytes",bytes(node.memory())));
            view.put("accelerators",accelerators(node).entrySet().stream().map(e->(Object)ordered("resourceName",e.getKey(),"allocatableCount",e.getValue())).toList());
            view.put("busy",busy.contains(node.name()));
            views.add(view);
        }
        result.put("nodes",views);
        return result;
    }
    private static Map<String,Object> range(int min,int def,int max){return ordered("min",min,"default",def,"max",max);}
    private List<ExecutionNode> allNodes() {
        var all=new ArrayList<ExecutionNode>();
        for(int offset=0;;offset+=1000){var page=nodes.list(1000,offset);all.addAll(page);if(page.size()<1000)break;}
        all.sort(Comparator.comparing(ExecutionNode::name));
        return all;
    }
    static Long millicores(String quantity) {
        if(quantity==null)return null;
        var q=KubernetesProfilingGateway.quantity(quantity);
        return q.signum()<0?null:q.movePointRight(3).setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
    }
    static Long bytes(String quantity) {
        if(quantity==null)return null;
        var q=KubernetesProfilingGateway.quantity(quantity);
        return q.signum()<0?null:q.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
    }
    /** Allow-listed extended resources the node's allocatable (capacity, not current free amount) advertises with at least one unit. */
    Map<String,Long> accelerators(ExecutionNode node) {
        Map<?,?> allocatable;
        try{allocatable=(Map<?,?>)JSON.decode(node.allocatableJson());}catch(RuntimeException e){return Map.of();}
        var result=new TreeMap<String,Long>();
        for(String name:settings.acceleratorResources())
            if(allocatable.get(name) instanceof String q && q.matches("[1-9][0-9]{0,5}"))result.put(name,Long.parseLong(q));
        return result;
    }

    // ---- runs -----------------------------------------------------------------------------------------------------

    public Creation<ProfilingRun> start(UUID modelId,String idempotencyKey,String body) {
        var idempotency=key(idempotencyKey);
        ModelProfilingInput.Run input;
        try{input=ModelProfilingInput.run(body);}
        catch(IllegalArgumentException e){throw invalid("INVALID_PROFILING_RUN",e.getMessage());}
        String digest=JSON.digest(ModelProfilingInput.RUN_SCOPE,ordered("modelId",modelId.toString(),"request",input.normalized()));
        var replay=repository.runByIdempotencyKey(idempotency);
        if(replay.isPresent())return replayed(replay.get(),digest);
        String reason=disabledReason();
        if(reason!=null)throw error(503,reason,"서버에서 모델 자원 측정을 사용할 수 없습니다 ("+reason+").");
        var model=model(modelId);var platform=platform(model);var image=image(model);
        if(!Boolean.TRUE.equals(platform.get("runnable")) || !"linux".equals(platform.get("os")))
            throw error(409,"PLATFORM_NOT_RUNNABLE","등록한 이미지 플랫폼("+platform.get("label")+")은 측정할 수 없습니다.");
        var node=allNodes().stream().filter(n->n.name().equals(input.nodeName())).min(Comparator.comparing(n->n.status(clock.instant()).equals("REMOVED")))
            .orElseThrow(()->error(404,"NODE_NOT_FOUND","노드 "+input.nodeName()+"를 인벤토리에서 찾을 수 없습니다."));
        String arch=(String)platform.get("architecture");
        if(!"linux".equals(node.operatingSystem()) || !arch.equals(node.architecture()) || !node.status(clock.instant()).equals("READY"))
            throw error(409,"PROFILING_NODE_INCOMPATIBLE","노드 "+node.name()+"("+node.operatingSystem()+"/"+node.architecture()+", "+node.status(clock.instant())
                +")는 이미지 플랫폼 "+platform.get("label")+"을 실행할 수 있는 READY 노드가 아닙니다.");
        var allocation=input.allocation();
        Long cpu=millicores(node.cpu()),memory=bytes(node.memory());
        if(cpu==null || memory==null || allocation.cpuMillicores()>cpu || allocation.memoryBytes()>memory)
            throw error(409,"PROFILING_ALLOCATION_EXCEEDS_NODE","시험 할당량이 노드 "+node.name()+"의 allocatable(CPU "+node.cpu()+", 메모리 "+node.memory()+")을 넘습니다.");
        if(allocation.accelerator()!=null) {
            var a=allocation.accelerator();Long available=accelerators(node).get(a.resourceName());
            if(!settings.acceleratorResources().contains(a.resourceName()) || available==null || a.count()>available)
                throw error(409,"PROFILING_ACCELERATOR_UNAVAILABLE","노드 "+node.name()+"의 allocatable에 시험 가속기 "+a.resourceName()+" "+a.count()+"개가 없습니다.");
        }
        var execution=input.load().http()?execution(model):ordered("command",input.load().command(),"args",input.load().args());
        var request=new LinkedHashMap<>(input.normalized());
        request.put("image",ordered("reference",image.get("platformReference"),"digest",image.get("digest"),
            "platformManifestDigest",image.get("platformManifestDigest"),"platform",platform.get("label")));
        request.put("execution",execution);
        var now=clock.instant();
        var runId=UUID.randomUUID();
        var run=new ProfilingRun(runId,modelId,idempotency,digest,"PENDING",node.name(),arch,JSON.canonical(request),ProfilingJob.jobName(runId),
            null,null,null,null,null,null,"측정 Job 생성을 기다립니다.",now,null,null,null,null,null,null,null,null,null,"NOT_STARTED",null,now,now);
        ModelProfilingReconciler.job(run); // validates the whole intended Job before anything is stored
        Creation<ProfilingRun> created;
        try {
            created=transaction.execute(status->{
                repository.lockSlots();
                var existing=repository.runByIdempotencyKey(idempotency);
                if(existing.isPresent())return replayed(existing.get(),digest);
                var holding=repository.holding();
                if(holding.stream().anyMatch(r->r.modelId().equals(modelId)))
                    throw error(409,"PROFILING_RUN_ACTIVE","이 모델의 측정이 진행 중이거나 이전 측정 Pod 회수가 끝나지 않았습니다.");
                if(holding.stream().anyMatch(r->r.nodeName().equals(node.name())))
                    throw error(409,"PROFILING_NODE_BUSY","노드 "+node.name()+"에서 다른 측정이 진행 중이거나 회수 중입니다.");
                if(holding.size()>=settings.maxActiveRuns())
                    throw error(409,"PROFILING_CAPACITY","동시에 진행할 수 있는 측정 수("+settings.maxActiveRuns()+")를 모두 쓰고 있습니다.");
                repository.create(run);
                return new Creation<>(run,true);
            });
        } catch(DuplicateKeyException e) {
            var raced=repository.runByIdempotencyKey(idempotency);
            if(raced.isPresent())return replayed(raced.get(),digest);
            throw error(409,"PROFILING_RUN_ACTIVE","같은 모델 또는 노드의 측정이 동시에 시작되었습니다.");
        }
        if(created.created())reconciler.wake(runId);
        return created;
    }
    private static Creation<ProfilingRun> replayed(ProfilingRun run,String digest) {
        if(!run.requestDigest().equals(digest))throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key로 다른 내용의 측정이 이미 있습니다.");
        return new Creation<>(run,false);
    }
    public ProfilingRun run(UUID modelId,UUID runId) {
        return repository.run(runId).filter(r->r.modelId().equals(modelId))
            .orElseThrow(()->error(404,"PROFILING_RUN_NOT_FOUND","측정 기록을 찾을 수 없습니다."));
    }
    public List<ProfilingRun> runs(UUID modelId,int limit,int offset) {
        model(modelId);
        if(limit<1 || limit>100 || offset<0 || offset>1000000)throw invalid("INVALID_PROFILING_RUN","limit은 1–100, offset은 0–1000000입니다.");
        return repository.runs(modelId,limit+1,offset);
    }
    /** Idempotent; a finished run is returned unchanged. The Job is removed by the reconciler, which keeps the slot until it is gone. */
    public ProfilingRun cancel(UUID modelId,UUID runId,String body) {
        try{ModelProfilingInput.fieldsOnly(body);}catch(IllegalArgumentException e){throw invalid("INVALID_PROFILING_RUN","본문은 빈 객체({})입니다.");}
        for(int attempt=0;attempt<5;attempt++) {
            var run=run(modelId,runId);
            if(run.finished())return run;
            var now=clock.instant();
            if(repository.update(run.finish("CANCELLED",null,"사용자가 측정을 취소했습니다.",run.resultJson(),now,now),run.state(),run.updatedAt())) {
                reconciler.stopDriver(runId);
                reconciler.wake(runId);
                return run(modelId,runId);
            }
        }
        throw error(409,"PROFILING_RUN_BUSY","측정 상태가 바뀌는 중입니다. 다시 시도하세요.");
    }
    public String imageReference(ProfilingRun run){return (String)ModelProfilingReconciler.part(run,"image").get("reference");}
    public Map<String,Object> view(ProfilingRun run) {
        var map=ModelProfilingDocuments.run(run,settings.namespace(),imageReference(run));
        map.put("execution",ModelProfilingReconciler.part(run,"execution"));
        return map;
    }

    // ---- confirmed profiles ---------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public Creation<ModelResourceProfile> confirm(UUID modelId,String idempotencyKey,String body) {
        var idempotency=key(idempotencyKey);
        ModelProfilingInput.Confirm input;
        try{input=ModelProfilingInput.confirm(body);}
        catch(IllegalArgumentException e){throw invalid("INVALID_RESOURCE_PROFILE",e.getMessage());}
        String fingerprint=JSON.digest(ModelProfilingInput.CONFIRM_SCOPE,ordered("modelId",modelId.toString(),"request",input.normalized()));
        var replay=repository.profileByIdempotencyKey(idempotency);
        if(replay.isPresent())return replayedProfile(replay.get(),modelId,fingerprint);
        var model=model(modelId);
        var run=repository.run(input.runId()).filter(r->r.modelId().equals(modelId))
            .orElseThrow(()->error(404,"PROFILING_RUN_NOT_FOUND","측정 기록을 찾을 수 없습니다."));
        if(!run.state().equals("SUCCEEDED"))
            throw error(409,"PROFILING_RUN_NOT_SUCCEEDED","성공(SUCCEEDED)한 측정만 요구량 확정 근거가 됩니다. 현재 상태: "+run.state());
        var result=(Map<String,Object>)JSON.decode(run.resultJson());
        var recommendation=(Map<String,Object>)result.get("recommendation");
        check(input.cpu(),(Map<String,Object>)recommendation.get("cpu"),"Millicores","CPU");
        check(input.memory(),(Map<String,Object>)recommendation.get("memory"),"Bytes","메모리");
        var request=ModelProfilingReconciler.request(run);
        var allocation=(Map<String,Object>)request.get("allocation");var tested=(Map<String,Object>)allocation.get("accelerator");
        var expected=tested==null?List.<ModelProfilingInput.Accelerator>of()
            :List.of(new ModelProfilingInput.Accelerator((String)tested.get("resourceName"),((Number)tested.get("count")).intValue()));
        if(!input.accelerators().equals(expected))
            throw error(409,"ACCELERATOR_NOT_TESTED","확정할 가속기 구성("+input.accelerators()+")이 시험한 구성("+expected
                +")과 다릅니다. 다른 가속기 구성은 시험하지 않았으므로 그 구성으로 다시 측정하세요.");
        var requirements=ordered("cpu",ModelProfilingInput.quantity(input.cpu(),"Millicores"),"memory",ModelProfilingInput.quantity(input.memory(),"Bytes"),
            "accelerators",input.normalized().get("accelerators"));
        var image=(Map<String,Object>)request.get("image");var load=new LinkedHashMap<>((Map<String,Object>)request.get("load"));
        var scope=ordered("image",image.get("reference"),"imageDigest",image.get("digest"),"platform",image.get("platform"),"execution",request.get("execution"),
            "loadType",load.get("type"),"loadDigest",JSON.digest("edgeai-ai-model-profiling-load-v1",load),"nodeName",run.nodeName(),"nodeArchitecture",run.nodeArchitecture());
        scope.put("allocation",allocation);
        scope.put("durationSeconds",request.get("durationSeconds"));
        scope.put("window",ordered("start",run.windowStart().toString(),"end",run.windowEnd().toString()));
        var evidence=ordered("runId",run.id().toString(),"target",result.get("target"),"measurement",result.get("measurement"),"loadEvidence",result.get("loadEvidence"));
        evidence.put("devices",result.get("devices"));
        evidence.put("deviceStatus",result.get("deviceStatus"));
        evidence.put("recommendation",recommendation);
        evidence.put("notices",result.get("notices"));
        var document=ordered("modelKey",model.modelKey(),"modelVersion",model.modelVersion(),"configName",model.configName(),
            "requirements",requirements,"scope",scope,"evidence",evidence);
        document.put("note",input.note());
        document.put("limitations",ModelProfilingDocuments.PROFILE_LIMITATIONS);
        String digest=JSON.digest(ModelProfilingDocuments.PROFILE_SCOPE,document);
        var now=clock.instant();
        return transaction.execute(status->{
            repository.lockProfiles(modelId,idempotency);
            var existing=repository.profileByIdempotencyKey(idempotency);
            if(existing.isPresent())return replayedProfile(existing.get(),modelId,fingerprint);
            int revision=repository.latestProfile(modelId).map(p->p.revision()+1).orElse(1);
            var profile=new ModelResourceProfile(UUID.randomUUID(),modelId,revision,run.id(),idempotency,fingerprint,digest,JSON.canonical(document),now);
            repository.createProfile(profile);
            return new Creation<>(profile,true);
        });
    }
    /** RECOMMENDED means exactly the run's recommendation (request and maximum, no minimum); anything else is MANUAL. */
    private static void check(ModelProfilingInput.Quantity q,Map<String,Object> recommended,String unit,String label) {
        if(q.basis()!=ModelProfilingInput.Basis.RECOMMENDED)return;
        boolean same=q.minimum()==null && q.request()==((Number)recommended.get("request"+unit)).longValue()
            && q.maximum()!=null && q.maximum()==((Number)recommended.get("maximum"+unit)).longValue();
        if(!same)throw invalid("INVALID_RESOURCE_PROFILE",label+" 값이 측정 추천값과 달라 RECOMMENDED로 저장할 수 없습니다. 수정한 값은 MANUAL입니다.");
    }
    private static Creation<ModelResourceProfile> replayedProfile(ModelResourceProfile profile,UUID modelId,String fingerprint) {
        if(!profile.modelId().equals(modelId) || !profile.requestFingerprint().equals(fingerprint))
            throw error(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key로 다른 내용의 요구량 확정이 이미 있습니다.");
        return new Creation<>(profile,false);
    }
    public List<ModelResourceProfile> profiles(UUID modelId){model(modelId);return repository.profiles(modelId);}
    public ModelResourceProfile profile(UUID modelId,UUID profileId) {
        return repository.profile(profileId).filter(p->p.modelId().equals(modelId))
            .orElseThrow(()->error(404,"RESOURCE_PROFILE_NOT_FOUND","확정 자원 프로파일을 찾을 수 없습니다."));
    }

    // ---- availability ---------------------------------------------------------------------------------------------

    /** READY only with a confirmed profile; the reason otherwise follows the latest run. */
    public Map<String,Object> availability(AiModelExecution model) {
        var profile=repository.latestProfile(model.id());
        var latest=repository.latestRun(model.id());
        String active=latest.filter(r->!r.finished()).map(r->r.id().toString()).orElse(null);
        String reason=null;
        if(profile.isEmpty()) {
            if(latest.isEmpty())reason="NO_MEASUREMENT";
            else reason=switch(latest.get().state()) {
                case "SUCCEEDED" -> "AWAITING_CONFIRMATION";
                case "FAILED" -> "MEASUREMENT_FAILED";
                case "CANCELLED" -> "NO_MEASUREMENT";
                default -> "MEASURING";
            };
        }
        var map=ordered("status",profile.isPresent()?"READY":"NOT_READY","reason",reason,"activeRunId",active,
            "latestRunId",latest.map(r->r.id().toString()).orElse(null));
        map.put("profile",profile.map(ModelProfilingDocuments::profileSummary).orElse(null));
        return map;
    }
    public Map<String,Object> entry(AiModelExecution model) {
        var map=AiModelDocuments.entry(model);
        map.put("availability",availability(model));
        return map;
    }
}
