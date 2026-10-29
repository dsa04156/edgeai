package io.edgeai.app.service;

import io.edgeai.app.config.ResourceProfilingSettings;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ResourceProfileInput;
import io.edgeai.domain.repository.ResourceProfileRepository;
import io.edgeai.domain.resourceprofile.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.ResourceProfileDocuments.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Reads observed usage for an explicit workload target and runs KRR through the configured bridge. Nothing here
 * accepts URLs, commands or PromQL from callers; failures become explicit statuses instead of values.
 */
@Service
@ConditionalOnProperty(name="edgeai.resource-profiling.enabled",havingValue="true")
public class ResourceAnalysisService {
    private static final Logger LOG=LoggerFactory.getLogger(ResourceAnalysisService.class);
    private final ResourceProfileRepository repository;
    private final ResourceProfileService profiles;
    private final ObjectProvider<WorkloadMetricsSource> metrics;
    private final ObjectProvider<RecommendationSource> recommendations;
    private final ResourceProfilingSettings settings;
    private final Clock clock;
    static final Duration OBSERVATION_BUDGET=Duration.ofSeconds(40);
    public ResourceAnalysisService(ResourceProfileRepository repository,ResourceProfileService profiles,ObjectProvider<WorkloadMetricsSource> metrics,
                                   ObjectProvider<RecommendationSource> recommendations,ResourceProfilingSettings settings,Clock clock) {
        this.repository=repository;this.profiles=profiles;this.metrics=metrics;this.recommendations=recommendations;this.settings=settings;this.clock=clock;
    }

    public Map<String,Object> capabilities() {
        var result=ordered("namespaces",settings.namespaces(),"prometheus",metrics.getIfAvailable()!=null,"krr",recommendations.getIfAvailable()!=null);
        result.put("krrVersion",settings.krrVersion());
        result.put("maxWindowHours",ResourceProfilingSettings.MAX_WINDOW.toHours());
        result.put("minWindowMinutes",ResourceProfilingSettings.MIN_WINDOW.toMinutes());
        result.put("maxHistoryHours",336);
        result.put("workloadKinds",Arrays.stream(WorkloadTarget.Kind.values()).map(Enum::name).toList());
        return result;
    }

    public List<WorkloadCandidate> workloads(String namespace,Integer hours) {
        allowed(namespace);
        int span=hours==null?24:hours;
        if(span<1||span>336)throw invalid("조회 구간은 1–336시간입니다.");
        Instant end=clock.instant();
        var source=source();
        return observed(()->source.workloads(namespace,end.minus(Duration.ofHours(span)),end));
    }

    public List<ObservedPod> pods(String namespace,String kind,String name,String container,String start,String end) {
        WorkloadTarget target;Instant from,to;
        try {
            target=new WorkloadTarget(namespace,WorkloadTarget.Kind.valueOf(kind),name,container,kind.equals("Pod")?name:null,null);
            from=Instant.parse(start);to=Instant.parse(end);
        } catch(IllegalArgumentException|DateTimeException|NullPointerException e){throw invalid(e.getMessage());}
        allowed(target.namespace());window(from,to);
        var source=source();
        return observed(()->source.pods(target,from,to));
    }

    /**
     * Not transactional: Prometheus and the KRR bridge are called before the single INSERT, so no DB connection is held
     * while waiting. Device queries stop after {@link #OBSERVATION_BUDGET}; the KRR call is bounded by its client timeout
     * (bridge 120 s < API 130 s < console proxy 210 s < browser 220 s).
     */
    public Map<String,Object> analyze(String body) {
        ResourceProfileInput.Analyze input;
        try { input=ResourceProfileInput.analyze(body); }
        catch(IllegalArgumentException|DateTimeException e){throw invalid(e.getMessage());}
        allowed(input.target().namespace());window(input.start(),input.end());
        var service=profiles.serviceVersion(input.serviceProfileVersionId());
        var target=input.target();
        var source=metrics.getIfAvailable();
        WorkloadMeasurement measurement;
        if(source==null) measurement=unavailable(input,"Prometheus 연결이 구성되지 않았습니다.");
        else try { measurement=source.measure(target,input.start(),input.end()); }
        catch(RuntimeException failure){LOG.warn("Resource measurement unavailable: {}",failure.getClass().getSimpleName());measurement=unavailable(input,"Prometheus 조회에 실패했습니다.");}
        var devices=new ArrayList<Object>();
        long deadline=System.nanoTime()+OBSERVATION_BUDGET.toNanos();
        for(var device:input.devices()) {
            DeviceObservation observation;
            if(System.nanoTime()>deadline) observation=deviceUnavailable(device,"분석 시간 한도(40초) 안에 장치 지표를 조회하지 못했습니다.");
            else if(source==null) observation=deviceUnavailable(device,"Prometheus 연결이 구성되지 않았습니다.");
            else try { observation=source.device(device,target.namespace(),measurement.pods(),input.start(),input.end()); }
            catch(RuntimeException failure){observation=deviceUnavailable(device,"장치 지표 조회에 실패했습니다.");}
            devices.add(device(observation));
        }
        Map<String,Object> recommendation;
        if(input.historyHours()==null) recommendation=notRequested();
        else if(!target.kind().recommendable())
            recommendation=recommendation(KrrRecommendation.failed(KrrRecommendation.Status.UNSUPPORTED,"KRR은 Deployment·StatefulSet·DaemonSet·Job·CronJob 컨트롤러 단위로만 추천합니다.",input.historyHours(),Map.of()));
        else {
            var krr=recommendations.getIfAvailable();
            KrrRecommendation result;
            if(krr==null) result=KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE,"KRR 실행 bridge가 구성되지 않았습니다.",input.historyHours(),Map.of());
            else try { result=krr.recommend(target,input.historyHours()); }
            catch(RuntimeException failure){
                LOG.warn("KRR recommendation unavailable: {}",failure.getClass().getSimpleName());
                result=KrrRecommendation.failed(KrrRecommendation.Status.UNAVAILABLE,"KRR 실행에 실패했습니다.",input.historyHours(),Map.of());
            }
            recommendation=recommendation(result);
        }
        Instant now=clock.instant();
        UUID id=UUID.randomUUID();
        var document=new LinkedHashMap<String,Object>();
        document.put("id",id.toString());
        document.put("serviceProfileVersionId",service.id().toString());
        document.put("service",service(service));
        document.put("createdAt",now.toString());
        document.put("target",target(target));
        document.put("executionRef",input.executionRef());
        document.put("window",ordered("start",input.start().toString(),"end",input.end().toString()));
        document.put("measurement",measurement(measurement));
        document.put("devices",devices);
        document.put("recommendation",recommendation);
        document.put("limitations",ANALYSIS_LIMITATIONS);
        String canonical=JSON.canonical(document);
        repository.createAnalysis(new ResourceAnalysis(id,service.id(),canonical,now));
        return decoded(canonical);
    }

    @Transactional(readOnly=true)
    public Map<String,Object> analysis(UUID id) {
        return decoded(repository.analysis(id).orElseThrow(()->new ControlPlaneException(404,"RESOURCE_ANALYSIS_NOT_FOUND","분석 결과를 찾을 수 없습니다.")).documentJson());
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> decoded(String json){return (Map<String,Object>)JSON.decode(json);}
    private WorkloadMetricsSource source() {
        var source=metrics.getIfAvailable();
        if(source==null)throw new ControlPlaneException(503,"RESOURCE_METRICS_UNAVAILABLE","Prometheus 연결이 구성되지 않았습니다.");
        return source;
    }
    private static <T> T observed(java.util.function.Supplier<T> query) {
        try { return query.get(); }
        catch(RuntimeException e){throw new ControlPlaneException(503,"RESOURCE_METRICS_UNAVAILABLE","Prometheus 조회에 실패했습니다.");}
    }
    private void allowed(String namespace) {
        try { WorkloadTarget.namespace(namespace); }
        catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        if(!settings.namespaces().contains(namespace))throw invalid("허용된 네임스페이스만 분석할 수 있습니다.");
    }
    private void window(Instant start,Instant end) {
        Instant now=clock.instant();
        if(!start.isBefore(end)||end.isAfter(now.plusSeconds(60)))throw invalid("관측 구간은 시작<종료이고 종료가 현재 이전이어야 합니다.");
        var span=Duration.between(start,end);
        if(span.compareTo(ResourceProfilingSettings.MIN_WINDOW)<0||span.compareTo(ResourceProfilingSettings.MAX_WINDOW)>0)
            throw invalid("관측 구간은 5분–14일입니다.");
    }
    private static WorkloadMeasurement unavailable(ResourceProfileInput.Analyze input,String reason) {
        return new WorkloadMeasurement(input.start(),input.end(),0,List.of(),UsageSummary.missing(ObservationStatus.UNAVAILABLE,"millicores",0,reason),
            UsageSummary.missing(ObservationStatus.UNAVAILABLE,"bytes",0,reason),"cadvisor",List.of());
    }
    private static DeviceObservation deviceUnavailable(DeviceRef device,String reason) {
        return new DeviceObservation(device,null,null,DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER,ObservationStatus.UNAVAILABLE,null,null,null,reason);
    }
    private static ControlPlaneException invalid(String detail) {
        return new ControlPlaneException(400,"INVALID_RESOURCE_ANALYSIS","분석 대상·구간 입력을 확인하세요."+(detail==null?"":" "+detail));
    }
}
