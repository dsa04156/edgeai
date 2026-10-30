package io.edgeai.app.service;

import io.edgeai.app.config.ModelProfilingSettings;
import io.edgeai.app.support.ModelProfilingDocuments;
import io.edgeai.app.support.ModelProfilingInput;
import io.edgeai.app.support.ResourceProfileDocuments;
import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.aimodel.ProfilingGateway.PodState;
import io.edgeai.domain.repository.ModelProfilingRepository;
import io.edgeai.domain.resourceprofile.*;
import io.edgeai.domain.runtime.RuntimeGatewayException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.ResourceProfileDocuments.ordered;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Drives every run that still holds a slot through PENDING → STARTING → WARMING_UP → MEASURING → COLLECTING → terminal,
 * then deletes its Job until Kubernetes reports nothing left. Each run advances on its own virtual thread with at most one
 * step in flight, so a slow Kubernetes, Prometheus or model answer (all bounded by timeouts) never stalls other runs or a
 * cancel. No database transaction is held across an external call: every write is a single conditional UPDATE on the
 * state and update time read before the call, so a concurrent cancel always wins over a stale step.
 * <p>
 * A conditional UPDATE cannot fence a Kubernetes call that another process sends late (a create delayed past a cancel and
 * the cleanup that released the slot). So the Job is created suspended, its UID is recorded with STARTING, and only then is
 * it resumed by a patch conditioned on that UID and the resourceVersion read before re-checking the run. A late create
 * leaves a suspended Job with no Pod (orphan cleanup deletes it); a late resume of a deleted or deleting Job is refused.
 */
@Service
@ConditionalOnProperty(name="edgeai.ai-models.enabled",havingValue="true")
public class ModelProfilingReconciler {
    private static final Logger LOG=LoggerFactory.getLogger(ModelProfilingReconciler.class);
    static final Duration LEASE=Duration.ofSeconds(30), COLLECT_RETRY=Duration.ofSeconds(30);
    private static final Set<String> PULL=Set.of("ErrImagePull","ImagePullBackOff","InvalidImageName","ErrImageNeverPull");
    private static final Set<String> CONFIG=Set.of("CreateContainerConfigError","CreateContainerError","RunContainerError");
    private final ModelProfilingRepository repository;
    private final ModelProfilingSettings settings;
    private final ObjectProvider<ProfilingGateway> gateway;
    private final ObjectProvider<InferenceLoadDriver> driver;
    private final ObjectProvider<WorkloadMetricsSource> metrics;
    private final ObjectProvider<NodeMetricsService> nodeMetrics;
    private final Clock clock;
    /** This API process; an HTTP load lease names it so another process can tell a live driver from an interrupted one. */
    final UUID instance=UUID.randomUUID();
    private final Map<UUID,InferenceLoadDriver.Handle> drivers=new ConcurrentHashMap<>();
    private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
    private final ExecutorService work=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler;

    public ModelProfilingReconciler(ModelProfilingRepository repository,ModelProfilingSettings settings,ObjectProvider<ProfilingGateway> gateway,
            ObjectProvider<InferenceLoadDriver> driver,ObjectProvider<WorkloadMetricsSource> metrics,ObjectProvider<NodeMetricsService> nodeMetrics,Clock clock,
            @Value("${edgeai.model-profiling.poll-ms:3000}") long pollMillis,@Value("${edgeai.runtime.worker-enabled:true}") boolean workerEnabled,
            @Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.repository=repository;this.settings=settings;this.gateway=gateway;this.driver=driver;this.metrics=metrics;this.nodeMetrics=nodeMetrics;this.clock=clock;
        if(settings.enabled() && gateway.getIfAvailable()!=null && workerEnabled && !inspection && pollMillis>0) {
            scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("model-profiling").daemon().factory());
            scheduler.scheduleWithFixedDelay(this::reconcile,pollMillis,pollMillis,TimeUnit.MILLISECONDS);
        } else scheduler=null;
    }
    @jakarta.annotation.PreDestroy void close() {
        if(scheduler!=null)scheduler.shutdownNow();
        drivers.values().forEach(InferenceLoadDriver.Handle::stop); // the lease expires; another process records the interruption
        work.shutdownNow();
    }

    // ---- scheduling ---------------------------------------------------------------------------------------------------

    public void reconcile() {
        try {
            var holding=repository.holding();
            for(var run:holding)wake(run.id());
            var k8s=gateway.getIfAvailable();
            if(k8s==null)return;
            // Kubernetes after the database: a Job listed here was created after its run row committed.
            for(var owner:k8s.ownedRuns()) {
                var run=repository.run(owner);
                if(run.isEmpty() || run.get().cleanedAt()!=null)wake(owner,()->release(owner));
            }
        } catch(RuntimeException e){LOG.warn("Model profiling reconciliation deferred ({})",e.getClass().getSimpleName());}
    }
    /** Schedules one step of the run unless one is already in flight. */
    public void wake(UUID id) { wake(id,()->advance(id)); }
    private void wake(UUID id,Runnable step) {
        if(!inFlight.add(id))return;
        try {
            work.execute(()->{
                try{step.run();}
                catch(RuntimeException e){LOG.warn("Model profiling step deferred ({})",e.getClass().getSimpleName());}
                finally{inFlight.remove(id);}
            });
        } catch(RejectedExecutionException e){inFlight.remove(id);}
    }
    /** Stops this process's HTTP load of the run, if any (cancel and failure paths). */
    public void stopDriver(UUID id) { var handle=drivers.remove(id);if(handle!=null)handle.stop(); }

    // ---- one step ----------------------------------------------------------------------------------------------------

    public void advance(UUID id) {
        var run=repository.run(id).orElse(null);
        if(run==null || run.cleanedAt()!=null)return;
        var now=clock.instant();
        if(run.finished()){stopDriver(id);cleanup(run);return;}
        var k8s=gateway.getIfAvailable();
        if(k8s==null)return; // Disabled after start: unobservable is not finished; the run waits for Kubernetes access.
        try {
            switch(run.state()) {
                case "PENDING" -> pending(run,k8s,now);
                case "STARTING" -> starting(run,k8s,now);
                case "WARMING_UP" -> warming(run,k8s,now);
                case "MEASURING" -> { if(http(run))measuringHttp(run,k8s,now);else measuringCommand(run,k8s,now); }
                case "COLLECTING" -> collecting(run,now);
                default -> {}
            }
        } catch(RuntimeGatewayException e) {
            if(e.reason()==RuntimeGatewayException.Reason.OWNERSHIP_CONFLICT)fail(run,"OWNERSHIP_CONFLICT","측정 Job 이름의 Kubernetes 객체가 이 측정의 의도한 Job과 다릅니다.");
            else if(now.isAfter(run.phaseStartedAt().plus(settings.kubernetesTimeout())))
                fail(run,"KUBERNETES_UNAVAILABLE","Kubernetes API 응답을 "+settings.kubernetesTimeout().toMinutes()+"분 동안 확인하지 못했습니다.");
            else LOG.warn("Model profiling run {} waits for Kubernetes ({})",id,e.reason());
        }
        var latest=repository.run(id).orElse(null);
        if(latest!=null && latest.finished() && latest.cleanedAt()==null){stopDriver(id);cleanup(latest);}
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> request(ProfilingRun run){return (Map<String,Object>)JSON.decode(run.requestJson());}
    @SuppressWarnings("unchecked")
    static Map<String,Object> part(ProfilingRun run,String key){return (Map<String,Object>)request(run).get(key);}
    static boolean http(ProfilingRun run){return "HTTP".equals(part(run,"load").get("type"));}
    static int duration(ProfilingRun run){return ((Number)request(run).get("durationSeconds")).intValue();}
    static int readiness(ProfilingRun run){return ((Number)part(run,"load").get("readinessTimeoutSeconds")).intValue();}

    @SuppressWarnings("unchecked")
    static ProfilingJob job(ProfilingRun run) {
        var r=request(run);var image=(Map<String,Object>)r.get("image");var exec=(Map<String,Object>)r.get("execution");
        var allocation=(Map<String,Object>)r.get("allocation");var accelerator=(Map<String,Object>)allocation.get("accelerator");var load=(Map<String,Object>)r.get("load");
        int duration=duration(run),ready=readiness(run);
        return new ProfilingJob(run.id(),run.modelId(),run.nodeName(),run.nodeArchitecture(),(String)image.get("reference"),
            (List<String>)exec.get("command"),(List<String>)exec.get("args"),((Number)allocation.get("cpuMillicores")).longValue(),
            ((Number)allocation.get("memoryBytes")).longValue(),accelerator==null?null:(String)accelerator.get("resourceName"),
            accelerator==null?0:((Number)accelerator.get("count")).intValue(),"HTTP".equals(load.get("type"))?((Number)load.get("port")).intValue():null,
            duration,2*ready+duration+ModelProfilingSettings.DEADLINE_SLACK_SECONDS);
    }
    @SuppressWarnings("unchecked")
    static ModelProfilingInput.Load load(ProfilingRun run) {
        var l=part(run,"load");
        return new ModelProfilingInput.Load("HTTP",((Number)l.get("port")).intValue(),(String)l.get("path"),(String)l.get("method"),(String)l.get("contentType"),
            (String)l.get("body"),(String)l.get("bodyBase64"),((Number)l.get("concurrency")).intValue(),((Number)l.get("requestTimeoutSeconds")).intValue(),
            readiness(run),null,null);
    }

    private boolean save(ProfilingRun current,ProfilingRun next){return repository.update(next,current.state(),current.updatedAt());}
    private void move(ProfilingRun current,ProfilingRun next){if(!save(current,next))LOG.debug("Model profiling run {} moved concurrently",current.id());}
    void fail(ProfilingRun run,String code,String message) {
        stopDriver(run.id());
        move(run,run.finish("FAILED",code,message,run.resultJson(),null,clock.instant()));
    }
    private void message(ProfilingRun run,String message) {
        if(!message.equals(run.message()))move(run,run.with(run.state(),message,clock.instant()));
    }

    private void pending(ProfilingRun run,ProfilingGateway k8s,Instant now) {
        UUID uid=k8s.ensureJob(job(run));
        var next=run.withJob(uid).with("STARTING","측정 Pod의 스케줄·이미지 준비를 기다립니다.",now);
        if(save(run,next))resume(next,k8s);
        else LOG.debug("Model profiling run {} moved before its suspended Job was claimed",run.id());
    }
    /** Starts the run's suspended Job only while the stored run is still STARTING with this Job UID. */
    private boolean resume(ProfilingRun run,ProfilingGateway k8s) {
        UUID uid=run.jobUid();
        if(uid==null)return false;
        return k8s.resume(run.id(),uid,()->repository.run(run.id()).map(r->r.state().equals("STARTING") && uid.equals(r.jobUid())).orElse(false));
    }

    /** A reason the measurement cannot continue, or null. Terminated containers are judged by the caller (COMMAND runs end so). */
    String problem(ProfilingRun run,Optional<ProfilingGateway.Observation> observation,PodState pod) {
        if(observation.isEmpty())return "JOB_MISSING";
        var o=observation.get();
        if(run.jobUid()!=null && !run.jobUid().equals(o.jobUid()))return "OWNERSHIP_CONFLICT";
        if("DeadlineExceeded".equals(o.jobFailedReason()))return "DEADLINE_EXCEEDED";
        if(o.pods().size()>1)return "RESTARTED";
        if(pod==null)return o.jobFailedReason()!=null?"CONTAINER_EXITED":null;
        if(run.podUid()!=null && !run.podUid().equals(pod.uid()))return "RESTARTED";
        if(pod.nodeName()!=null && !pod.nodeName().equals(run.nodeName()))return "OWNERSHIP_CONFLICT";
        if("OOMKilled".equals(pod.terminatedReason()))return "OOM_KILLED";
        if(pod.restarts()>0)return "RESTARTED";
        if(pod.waitingReason()!=null && PULL.contains(pod.waitingReason()))return "IMAGE_PULL_FAILED";
        if(pod.waitingReason()!=null && CONFIG.contains(pod.waitingReason()))return "CONTAINER_CONFIG_ERROR";
        if(pod.imageId()!=null && !imageMatches(run,pod.imageId()))return "IMAGE_MISMATCH";
        return null;
    }
    @SuppressWarnings("unchecked")
    static boolean imageMatches(ProfilingRun run,String imageId) {
        var image=(Map<String,Object>)request(run).get("image");
        return imageId.endsWith("@"+image.get("platformManifestDigest")) || imageId.endsWith("@"+image.get("digest"))
            || imageId.equals(image.get("platformManifestDigest")) || imageId.equals(image.get("digest"));
    }
    static String describe(String code,PodState pod) {
        return switch(code) {
            case "JOB_MISSING" -> "측정 Job이 Kubernetes에서 사라졌습니다.";
            case "OWNERSHIP_CONFLICT" -> "측정 Job·Pod의 신원(UID·노드)이 이 측정과 일치하지 않습니다.";
            case "DEADLINE_EXCEEDED" -> "측정 Job이 activeDeadlineSeconds에 도달해 종료되었습니다.";
            case "RESTARTED" -> "측정 Pod가 재시작되었거나 다른 Pod로 바뀌어 한 실행으로 측정할 수 없습니다.";
            case "OOM_KILLED" -> "모델 컨테이너가 시험 메모리 할당량을 넘어 OOMKilled로 종료되었습니다. 더 큰 메모리로 재측정하세요.";
            case "IMAGE_PULL_FAILED" -> "등록한 이미지를 노드가 받지 못했습니다 ("+(pod==null?"":pod.waitingReason())+").";
            case "CONTAINER_CONFIG_ERROR" -> "컨테이너를 만들 수 없습니다 ("+(pod==null?"":pod.waitingReason())+"). root 사용자 이미지는 측정할 수 없습니다.";
            case "IMAGE_MISMATCH" -> "실행된 이미지("+(pod==null?"":pod.imageId())+")가 등록한 digest와 다릅니다.";
            case "CONTAINER_EXITED" -> "모델 컨테이너가 측정 중 종료되었습니다"+(pod==null || pod.exitCode()==null?".":" (exit "+pod.exitCode()+", "+pod.terminatedReason()+").");
            default -> code;
        };
    }
    private static PodState pod(Optional<ProfilingGateway.Observation> o){return o.isEmpty() || o.get().pods().isEmpty()?null:o.get().pods().getFirst();}

    private void starting(ProfilingRun run,ProfilingGateway k8s,Instant now) {
        var observation=k8s.observe(run.id());
        if(observation.isPresent() && observation.get().suspended() && (run.jobUid()==null || run.jobUid().equals(observation.get().jobUid()))
            && resume(run,k8s))observation=k8s.observe(run.id()); // persisted, not yet resumed (e.g. the API restarted in between)
        var pod=pod(observation);
        String problem=problem(run,observation,pod);
        if(problem!=null){fail(run,problem,describe(problem,pod));return;}
        boolean started=pod!=null && pod.startedAt()!=null;
        if(started && pod.terminatedReason()!=null && http(run)){fail(run,"CONTAINER_EXITED",describe("CONTAINER_EXITED",pod));return;}
        if(!started || http(run) && pod.podIp()==null) {
            if(now.isAfter(run.phaseStartedAt().plusSeconds(readiness(run)))) {
                boolean unscheduled=pod==null || pod.nodeName()==null;
                fail(run,unscheduled?"UNSCHEDULABLE":"READINESS_TIMEOUT",unscheduled
                    ?"준비 대기 시간 안에 측정 Pod가 노드 "+run.nodeName()+"에 배치되지 않았습니다"+(pod==null || pod.waitingReason()==null?".":" ("+pod.waitingReason()+").")
                    :"준비 대기 시간 안에 모델 컨테이너가 시작되지 않았습니다"+(pod.waitingReason()==null?".":" ("+pod.waitingReason()+")."));
            } else message(run,pod==null?"측정 Pod 생성을 기다립니다.":"측정 Pod 준비 중 ("+(pod.waitingReason()==null?pod.phase():pod.waitingReason())+")");
            return;
        }
        var withPod=run.withPod(pod.name(),pod.uid(),pod.podIp());
        if(http(run))move(run,withPod.with("WARMING_UP","모델 서버가 샘플 요청에 응답하기를 기다립니다.",now));
        else move(run,withPod.withWindow(pod.startedAt(),null,null).with("MEASURING","이미지 내부 벤치마크 명령이 실행 중입니다.",now));
    }

    private void warming(ProfilingRun run,ProfilingGateway k8s,Instant now) {
        var observation=k8s.observe(run.id());var pod=pod(observation);
        String problem=problem(run,observation,pod);
        if(problem==null && (pod==null || pod.terminatedReason()!=null))problem="CONTAINER_EXITED";
        if(problem!=null){fail(run,problem,describe(problem,pod));return;}
        var load=driver.getIfAvailable();
        if(load==null)return;
        var spec=load(run).httpLoad();
        var probe=load.probe(spec,run.podIp());
        var fresh=repository.run(run.id()).orElseThrow();
        if(!fresh.state().equals("WARMING_UP"))return; // cancelled while the probe ran
        if(probe==InferenceLoadDriver.ProbeResult.READY) {
            var handle=load.start(spec,run.podIp(),Duration.ofSeconds(duration(run)));
            var started=clock.instant();
            var next=fresh.withDriver(instance,started.plus(LEASE)).withWindow(started,null,null)
                .with("MEASURING","샘플 추론 요청을 "+spec.concurrency()+"개 동시로 보내는 중입니다.",started);
            if(save(fresh,next))drivers.put(run.id(),handle);
            else handle.stop();
            return;
        }
        var lastProbe=ordered("probe",probe.name(),"at",now.toString());
        if(now.isAfter(fresh.phaseStartedAt().plusSeconds(readiness(run)))) {
            if(probe==InferenceLoadDriver.ProbeResult.CONNECTION_FAILED)
                fail(fresh,"DRIVER_UNREACHABLE","API에서 측정 Pod("+run.podIp()+":"+spec.port()+")로 연결할 수 없습니다. Pod 네트워크 도달성과 포트를 확인하세요.");
            else fail(fresh,"READINESS_TIMEOUT","준비 대기 시간 안에 샘플 요청이 2xx로 응답하지 않았습니다 (마지막: "+probe+").");
            return;
        }
        move(fresh,fresh.withProgress(JSON.canonical(lastProbe)).with("WARMING_UP","모델 서버 준비 대기 중 (마지막 시험 요청: "+probe+")",clock.instant()));
    }

    private void measuringHttp(ProfilingRun run,ProfilingGateway k8s,Instant now) {
        var handle=drivers.get(run.id());
        if(handle==null) {
            boolean mine=instance.equals(run.driverOwner());
            if(mine || run.driverLeaseUntil()==null || now.isAfter(run.driverLeaseUntil()))
                fail(run,"DRIVER_INTERRUPTED","부하를 보내던 API 프로세스가 중단되어 부하 구간이 끊겼습니다. 다시 측정하세요.");
            return; // another live process drives this run
        }
        var observation=k8s.observe(run.id());var pod=pod(observation);
        String problem=problem(run,observation,pod);
        if(problem==null && (pod==null || pod.terminatedReason()!=null))problem="CONTAINER_EXITED";
        if(problem!=null) {
            var partial=handle.stop();drivers.remove(run.id());
            var evidence=ModelProfilingDocuments.httpEvidence(partial,Duration.between(run.windowStart(),clock.instant()).toMillis()/1000.0);
            stopDriver(run.id());
            move(run,run.finish("FAILED",problem,describe(problem,pod),JSON.canonical(ordered("loadEvidence",evidence)),null,clock.instant()));
            return;
        }
        var end=run.windowStart().plusSeconds(duration(run));
        if(now.isBefore(end) && !handle.finished()) {
            var progress=handle.progress();
            var next=run.withProgress(JSON.canonical(ModelProfilingDocuments.progress(progress))).withDriver(instance,now.plus(LEASE))
                .with("MEASURING","샘플 추론 요청 "+progress.requests()+"건 (성공 "+progress.succeeded()+")",now);
            if(!save(run,next))stopDriver(run.id());
            return;
        }
        var finalProgress=handle.stop();drivers.remove(run.id());
        Instant ended=clock.instant(); // the server clock that also bounds the Prometheus window
        double seconds=Duration.between(run.windowStart(),ended).toMillis()/1000.0;
        var evidence=ModelProfilingDocuments.httpEvidence(finalProgress,seconds);
        String code=null,message=null;
        if(finalProgress.requests()>0 && finalProgress.connectionFailures()==finalProgress.requests()) {
            code="DRIVER_UNREACHABLE";message="부하 구간 동안 측정 Pod에 연결하지 못했습니다.";
        } else if(finalProgress.succeeded()<ModelProfilingSettings.MIN_SUCCESSFUL_REQUESTS
                || (double)finalProgress.succeeded()/Math.max(1,finalProgress.requests())<ModelProfilingSettings.MIN_SUCCESS_RATIO) {
            code="LOAD_FAILED";message="성공 응답 "+finalProgress.succeeded()+"/"+finalProgress.requests()+"건으로 기준(성공률 "
                +(int)(ModelProfilingSettings.MIN_SUCCESS_RATIO*100)+"%·최소 "+ModelProfilingSettings.MIN_SUCCESSFUL_REQUESTS+"건)에 못 미쳐 실제 추론 부하로 볼 수 없습니다.";
        } else if(seconds<duration(run)*0.98) {
            code="TOO_SHORT";message="부하 구간이 요청한 측정 시간보다 짧았습니다.";
        }
        var stored=ordered("loadEvidence",evidence);
        if(code!=null){move(run,run.finish("FAILED",code,message,JSON.canonical(stored),null,clock.instant()));return;}
        // The container must still be the same running Pod at the end of the window.
        var after=k8s.observe(run.id());var afterPod=pod(after);
        String late=problem(run,after,afterPod);
        if(late==null && (afterPod==null || afterPod.terminatedReason()!=null))late="CONTAINER_EXITED";
        if(late!=null){move(run,run.finish("FAILED",late,describe(late,afterPod),JSON.canonical(stored),null,clock.instant()));return;}
        var next=run.withWindow(run.windowStart(),ended,ended.plus(settings.scrapeGrace())).withProgress(JSON.canonical(evidence))
            .with("COLLECTING","부하를 마쳤습니다. Prometheus 수집 지연("+settings.scrapeGrace().toSeconds()+"초) 후 사용량을 조회합니다.",clock.instant());
        move(run,next);
    }

    private void measuringCommand(ProfilingRun run,ProfilingGateway k8s,Instant now) {
        var observation=k8s.observe(run.id());var pod=pod(observation);
        String problem=problem(run,observation,pod);
        if(problem!=null){fail(run,problem,describe(problem,pod));return;}
        if(pod==null){fail(run,"CONTAINER_EXITED","측정 Pod가 사라졌습니다.");return;}
        if(pod.terminatedReason()==null && pod.finishedAt()==null){message(run,"이미지 내부 벤치마크 명령이 실행 중입니다.");return;}
        var evidence=ordered("type","COMMAND","exitCode",pod.exitCode(),"reason",pod.terminatedReason());
        long seconds=pod.startedAt()==null || pod.finishedAt()==null?0:Duration.between(pod.startedAt(),pod.finishedAt()).getSeconds();
        evidence.put("runSeconds",seconds);
        evidence.put("note","부하 여부는 명령이 요청 시간 이상 실행 후 정상 종료(exit 0)했다는 사실로만 확인합니다.");
        var stored=JSON.canonical(ordered("loadEvidence",evidence));
        if(pod.exitCode()==null || pod.exitCode()!=0 || !"Completed".equals(pod.terminatedReason())) {
            move(run,run.finish("FAILED","COMMAND_FAILED","벤치마크 명령이 정상 종료하지 않았습니다 (exit "+pod.exitCode()+", "+pod.terminatedReason()+").",stored,null,clock.instant()));
            return;
        }
        if(seconds<duration(run)) {
            move(run,run.finish("FAILED","TOO_SHORT","벤치마크 명령이 "+seconds+"초 만에 끝나 요청한 측정 시간 "+duration(run)+"초보다 짧습니다.",stored,null,clock.instant()));
            return;
        }
        move(run,run.withWindow(pod.startedAt(),pod.finishedAt(),pod.finishedAt().plus(settings.scrapeGrace())).withProgress(JSON.canonical(evidence))
            .with("COLLECTING","벤치마크가 끝났습니다. Prometheus 수집 지연 후 사용량을 조회합니다.",clock.instant()));
    }

    @SuppressWarnings("unchecked")
    private void collecting(ProfilingRun run,Instant now) {
        if(now.isBefore(run.collectAfter()))return;
        var deadline=run.windowEnd().plus(settings.scrapeGrace()).plus(settings.collectTimeout());
        var source=metrics.getIfAvailable();
        var evidence=JSON.decode(run.progressJson());
        if(source==null){fail(run,"METRICS_UNAVAILABLE","Prometheus 연결이 구성되지 않았습니다.");return;}
        var target=new WorkloadTarget(settings.namespace(),WorkloadTarget.Kind.Job,run.jobName(),ProfilingJob.CONTAINER,run.podName(),run.podUid());
        WorkloadMeasurement m;
        try{m=source.measure(target,run.windowStart(),run.windowEnd());}
        catch(RuntimeException e) {
            LOG.warn("Model profiling metrics unavailable for {} ({})",run.id(),e.getClass().getSimpleName());
            if(now.isAfter(deadline))fail(run,"METRICS_UNAVAILABLE","Prometheus 조회에 계속 실패했습니다.");
            else retry(run,now,"Prometheus 조회 실패, 다시 시도합니다.");
            return;
        }
        boolean ok=ModelProfilingDocuments.available(m.cpu().status()) && ModelProfilingDocuments.available(m.memory().status())
            && m.cpu().samples()>=io.edgeai.adapters.profiling.PrometheusWorkloadMetrics.MIN_SAMPLES && m.memory().samples()>=io.edgeai.adapters.profiling.PrometheusWorkloadMetrics.MIN_SAMPLES;
        var allocation=part(run,"allocation");
        var stored=ordered("loadEvidence",evidence,"target",target(run),"measurement",ResourceProfileDocuments.measurement(m));
        if(!ok) {
            if(now.isBefore(deadline)){retry(run,now,"CPU "+m.cpu().status()+"·메모리 "+m.memory().status()+" 상태, 수집을 다시 확인합니다.");return;}
            boolean thin=Set.of(ObservationStatus.INSUFFICIENT,ObservationStatus.STALE,ObservationStatus.NO_DATA).contains(m.cpu().status())
                || Set.of(ObservationStatus.INSUFFICIENT,ObservationStatus.STALE,ObservationStatus.NO_DATA).contains(m.memory().status());
            move(run,run.finish("FAILED",thin?"INSUFFICIENT_SAMPLES":"METRICS_UNAVAILABLE","CPU("+m.cpu().status()+": "+m.cpu().reason()+")·메모리("
                +m.memory().status()+": "+m.memory().reason()+") 관측이 측정 기준을 만족하지 못했습니다.",JSON.canonical(stored),null,clock.instant()));
            return;
        }
        var devices=devices(run,source,m);
        stored.put("devices",devices);
        stored.put("deviceStatus",part(run,"allocation").get("accelerator")==null?ordered("status","NOT_REQUESTED","reason","시험에 가속기를 할당하지 않았습니다.")
            :devices.isEmpty()?ordered("status","UNSUPPORTED","reason","시험 노드에서 기존 exporter가 보고하는 가속기 장치를 찾지 못했습니다. 가속기 수량은 시험 할당값만 기록합니다.")
            :ordered("status","OBSERVED","reason","장치 전체 관측값이며 모델 단독 사용량이 아닐 수 있습니다."));
        stored.put("recommendation",ModelProfilingDocuments.recommendation(m,allocation));
        stored.put("notices",ModelProfilingDocuments.notices(m,allocation));
        stored.put("limitations",ModelProfilingDocuments.resultLimitations(http(run)));
        var fresh=repository.run(run.id()).orElseThrow();
        if(!fresh.state().equals("COLLECTING"))return;
        move(fresh,fresh.finish("SUCCEEDED",null,"측정을 마쳤습니다. 결과를 확인하고 요구량을 확정하세요.",JSON.canonical(stored),null,clock.instant()));
    }
    private void retry(ProfilingRun run,Instant now,String message) {
        move(run,run.withWindow(run.windowStart(),run.windowEnd(),now.plus(COLLECT_RETRY)).with("COLLECTING",message,clock.instant()));
    }
    private Map<String,Object> target(ProfilingRun run) {
        return ordered("namespace",settings.namespace(),"jobName",run.jobName(),"container",ProfilingJob.CONTAINER,"podName",run.podName(),
            "podUid",run.podUid().toString(),"window",ordered("start",run.windowStart().toString(),"end",run.windowEnd().toString()));
    }
    /** Devices of the allocated kind on the test node, from the existing exporters; attribution limits are kept as reported. */
    @SuppressWarnings("unchecked")
    private List<Object> devices(ProfilingRun run,WorkloadMetricsSource source,WorkloadMeasurement m) {
        var allocation=part(run,"allocation");
        if(allocation.get("accelerator")==null)return List.of();
        var service=nodeMetrics.getIfAvailable();
        var result=new ArrayList<Object>();
        List<io.edgeai.domain.node.NodeMetricsSource.Accelerator> accelerators=List.of();
        try {
            var snapshot=service==null?null:service.snapshot();
            if(snapshot!=null)accelerators=snapshot.items().stream().filter(n->n.nodeName().equals(run.nodeName())).flatMap(n->n.accelerators().stream()).toList();
        } catch(RuntimeException e){LOG.warn("Node accelerator inventory unavailable ({})",e.getClass().getSimpleName());}
        for(var a:accelerators) {
            DeviceRef ref;
            try{ref=new DeviceRef(run.nodeName(),AcceleratorRequirement.Kind.valueOf(a.kind()),a.device());}catch(RuntimeException e){continue;}
            try{result.add(ResourceProfileDocuments.device(source.device(ref,settings.namespace(),m.pods(),run.windowStart(),run.windowEnd())));}
            catch(RuntimeException e){result.add(ResourceProfileDocuments.device(new DeviceObservation(ref,null,null,DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER,
                ObservationStatus.UNAVAILABLE,null,null,null,"장치 지표 조회에 실패했습니다.")));}
        }
        return result;
    }

    // ---- cleanup ------------------------------------------------------------------------------------------------------

    private void cleanup(ProfilingRun run) {
        if(release(run.id()))repository.cleaned(run.id(),clock.instant());
    }
    private boolean release(UUID id) {
        var k8s=gateway.getIfAvailable();
        if(k8s==null)return false; // unobservable is not removed
        try{return k8s.delete(id);}
        catch(RuntimeException e){LOG.warn("Model profiling Job removal deferred ({})",e.getClass().getSimpleName());return false;}
    }
}
