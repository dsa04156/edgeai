package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.ResourceProfileInput;
import io.edgeai.app.support.ResourceProfileInput.Basis;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.profile.ProfileVersion;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.repository.ProfileRepository;
import io.edgeai.domain.repository.ResourceProfileRepository;
import io.edgeai.domain.resourceprofile.ResourceProfile;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.edgeai.app.support.ResourceProfileDocuments.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Saves user-confirmed resource requirements as immutable revisions of one SERVICE profile version and serves them
 * to 2-1. Measured/recommended bases are accepted only when the cited server-side analysis actually contains them.
 */
@Service
@ConditionalOnProperty(name="edgeai.resource-profiling.enabled",havingValue="true")
public class ResourceProfileService {
    private final ResourceProfileRepository repository;
    private final ProfileRepository profiles;
    private final Clock clock;
    public ResourceProfileService(ResourceProfileRepository repository,ProfileRepository profiles,Clock clock) {
        this.repository=repository;this.profiles=profiles;this.clock=clock;
    }

    @Transactional
    public Creation<ResourceProfile> save(String key,String body) {
        UUID idempotency=idempotencyKey(key);
        ResourceProfileInput.Save input;
        try { input=ResourceProfileInput.save(body); }
        catch(IllegalArgumentException|java.time.DateTimeException e){throw invalid(e.getMessage());}
        var service=serviceVersion(input.serviceProfileVersionId());
        validateArchitectures(service,input.architectures());
        Map<String,Object> evidence=null;
        if(input.analysisId()!=null) {
            var analysis=repository.analysis(input.analysisId()).orElseThrow(()->invalid("인용한 분석 결과를 찾을 수 없습니다."));
            if(!analysis.serviceProfileVersionId().equals(service.id()))throw invalid("분석 결과의 서비스 버전이 저장할 서비스 버전과 다릅니다.");
            evidence=evidence(analysis.id(),JSON.decode(analysis.documentJson()));
        }
        requireBasis("CPU",input.cpu().basis(),input.cpu().range().request(),evidence,"cpu","requestMillicores");
        requireBasis("메모리",input.memory().basis(),input.memory().range().request(),evidence,"memory","requestBytes");
        var document=new LinkedHashMap<String,Object>();
        document.put("serviceProfileVersionId",service.id().toString());
        document.put("service",service(service));
        document.put("inputConditions",input.inputConditions());
        var compatibility=ordered("os","linux","architectures",input.architectures().isEmpty()?serviceArchitectures(service):input.architectures());
        compatibility.put("runtime",input.runtime());
        document.put("compatibility",compatibility);
        var cpu=input.cpu().range();var memory=input.memory().range();
        var accelerators=input.accelerators().stream().map(a->{
            var map=ordered("kind",a.kind().name(),"resourceName",a.resourceName(),"unit",a.unit().name(),
                "min",a.range().min(),"request",a.range().request(),"max",a.range().max());
            map.put("deviceMemoryBytes",a.deviceMemoryBytes());map.put("vendor",a.vendor());map.put("models",a.models());
            return (Object)map;
        }).toList();
        document.put("resources",ordered(
            "cpu",ordered("minMillicores",cpu.min(),"requestMillicores",cpu.request(),"maxMillicores",cpu.max(),"basis",input.cpu().basis().name()),
            "memory",ordered("minBytes",memory.min(),"requestBytes",memory.request(),"maxBytes",memory.max(),"basis",input.memory().basis().name()),
            "accelerators",accelerators));
        document.put("note",input.note());
        document.put("evidence",evidence);
        String digest=JSON.digest(PROFILE_SCOPE,document);
        repository.lockService(service.id());
        var existing=repository.byIdempotencyKey(idempotency);
        if(existing.isPresent()) {
            if(!existing.get().digest().equals(digest))
                throw new ControlPlaneException(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key에 다른 자원 프로파일이 있습니다.");
            return new Creation<>(existing.get(),false);
        }
        var profile=new ResourceProfile(UUID.randomUUID(),service.id(),repository.nextRevision(service.id()),idempotency,input.analysisId(),
            measurementStatus(evidence),digest,JSON.canonical(document),clock.instant());
        repository.create(profile);
        return new Creation<>(profile,true);
    }

    @Transactional(readOnly=true)
    public ResourceProfile profile(UUID id) {
        return repository.profile(id).orElseThrow(()->new ControlPlaneException(404,"RESOURCE_PROFILE_NOT_FOUND","자원 프로파일을 찾을 수 없습니다."));
    }
    @Transactional(readOnly=true)
    public ResourceProfile revision(String serviceKey,String serviceVersion,int revision) {
        ProfileIdentity identity;
        try { identity=new ProfileIdentity(ProfileIdentity.Kind.SERVICE,serviceKey,serviceVersion); }
        catch(IllegalArgumentException e){throw invalid("서비스 키·버전 형식을 확인하세요.");}
        var service=profiles.find(identity).orElseThrow(()->new ControlPlaneException(404,"SERVICE_VERSION_NOT_FOUND","서비스 버전을 찾을 수 없습니다."));
        return repository.revision(service.id(),revision)
            .orElseThrow(()->new ControlPlaneException(404,"RESOURCE_PROFILE_NOT_FOUND","해당 서비스 버전의 자원 프로파일 revision을 찾을 수 없습니다."));
    }
    @Transactional(readOnly=true)
    public List<ResourceProfile> list(UUID serviceProfileVersionId,int limit,int offset) {
        if(limit<1||limit>100||offset<0||offset>1000000)throw invalid("limit은 1–100, offset은 0–1000000입니다.");
        return repository.list(serviceProfileVersionId,limit+1,offset);
    }

    ProfileVersion serviceVersion(UUID id) {
        var version=profiles.find(id).filter(v->v.identity().kind()==ProfileIdentity.Kind.SERVICE)
            .orElseThrow(()->new ControlPlaneException(404,"SERVICE_VERSION_NOT_FOUND","등록된 SERVICE 실행 모듈 버전을 찾을 수 없습니다."));
        return version;
    }
    @SuppressWarnings("unchecked")
    static List<String> serviceArchitectures(ProfileVersion service) {
        var spec=(Map<String,Object>)JSON.decode(service.specJson());
        if(spec.get("platform") instanceof Map<?,?> platform && platform.get("architectures") instanceof List<?> list)
            return list.stream().map(Object::toString).sorted().toList();
        return List.of();
    }
    private static void validateArchitectures(ProfileVersion service,List<String> architectures) {
        var supported=serviceArchitectures(service);
        if(!supported.isEmpty()&&!supported.containsAll(architectures))
            throw invalid("호환 아키텍처는 서비스 버전의 platform.architectures 범위 안에서 지정하세요.");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> evidence(UUID analysisId,Object decoded) {
        var analysis=(Map<String,Object>)decoded;
        var evidence=ordered("analysisId",analysisId.toString(),"createdAt",analysis.get("createdAt"),"target",analysis.get("target"),
            "executionRef",analysis.get("executionRef"),"window",analysis.get("window"));
        evidence.put("measurement",analysis.get("measurement"));
        evidence.put("devices",analysis.get("devices"));
        evidence.put("recommendation",analysis.get("recommendation"));
        return evidence;
    }
    @SuppressWarnings("unchecked")
    /**
     * A measured/recommended basis is a claim about the confirmed request itself: it must equal the cited KRR request,
     * or the cited AVAILABLE p95 rounded up to whole millicores/bytes. Any edited value is the user's own (MANUAL).
     */
    private static void requireBasis(String label,Basis basis,long request,Map<String,Object> evidence,String resource,String requestField) {
        if(basis==Basis.MANUAL)return;
        if(evidence==null)throw invalid(label+" 근거가 측정·추천이면 분석 결과(analysisId)를 인용해야 합니다.");
        if(basis==Basis.MEASURED) {
            var usage=(Map<String,Object>)((Map<String,Object>)evidence.get("measurement")).get(resource);
            if(!"AVAILABLE".equals(usage.get("status"))||usage.get("p95")==null)throw invalid(label+" 실측값이 유효하지 않아(미측정·만료·부족) 실측 근거로 저장할 수 없습니다.");
            long p95=new java.math.BigDecimal(usage.get("p95").toString()).setScale(0,java.math.RoundingMode.CEILING).longValueExact();
            if(request!=p95)throw invalid(label+" 요청값이 인용한 실측 p95("+p95+")와 달라 실측 근거로 저장할 수 없습니다. 수정한 값은 사용자 지정(MANUAL)입니다.");
        } else {
            var recommendation=(Map<String,Object>)evidence.get("recommendation");
            var values=(Map<String,Object>)recommendation.get(resource);
            if(!Set.of("AVAILABLE","PARTIAL").contains(recommendation.get("status"))||values.get(requestField)==null)
                throw invalid(label+" KRR 추천값이 없어 추천 근거로 저장할 수 없습니다.");
            long recommended=new java.math.BigDecimal(values.get(requestField).toString()).longValueExact();
            if(request!=recommended)throw invalid(label+" 요청값이 인용한 KRR 추천("+recommended+")과 달라 추천 근거로 저장할 수 없습니다. 수정한 값은 사용자 지정(MANUAL)입니다.");
        }
    }
    @SuppressWarnings("unchecked")
    private static String measurementStatus(Map<String,Object> evidence) {
        if(evidence==null)return "UNMEASURED";
        var measurement=(Map<String,Object>)evidence.get("measurement");
        long available=java.util.stream.Stream.of("cpu","memory").filter(r->"AVAILABLE".equals(((Map<String,Object>)measurement.get(r)).get("status"))).count();
        return available==2?"MEASURED":available==1?"PARTIAL":"UNMEASURED";
    }
    private static UUID idempotencyKey(String key) {
        try { return UUID.fromString(key); }
        catch(RuntimeException e){throw invalid("Idempotency-Key는 UUID여야 합니다.");}
    }
    static ControlPlaneException invalid(String detail) {
        return new ControlPlaneException(400,"INVALID_RESOURCE_PROFILE","자원 프로파일 입력을 확인하세요."+(detail==null?"":" "+detail));
    }
}
