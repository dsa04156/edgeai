package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.AiModelDocuments;
import io.edgeai.app.support.AiModelInput;
import io.edgeai.app.support.ResourceProfileInput.Basis;
import io.edgeai.domain.aimodel.*;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.AiModelRepository;
import io.edgeai.domain.repository.Creation;
import io.edgeai.domain.repository.ProfileRepository;
import io.edgeai.domain.repository.ResourceProfileRepository;
import io.edgeai.domain.resourceprofile.ResourceProfile;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static io.edgeai.app.support.AiModelDocuments.ordered;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * Registers immutable AI model execution entries. Resource requirements are optional: an entry registered without them is
 * measured afterwards ({@link ModelProfilingService}) and becomes usable only through a confirmed profile. The image is verified against the configured registry outside
 * any DB transaction; the insert runs in one transaction under advisory locks so replays, conflicting keys and
 * concurrent registrations of the same (model, version, configuration) resolve consistently. A replay of an
 * already stored request is answered from the stored fingerprint without contacting the registry.
 */
@Service
@ConditionalOnProperty(name="edgeai.ai-models.enabled",havingValue="true")
public class AiModelService {
    public enum Source { MANUAL, RESOURCE_PROFILE, MANUAL_FROM_RESOURCE_PROFILE }
    private final AiModelRepository repository;
    private final ObjectProvider<ImageRegistry> registry;
    private final ObjectProvider<ResourceProfileRepository> resourceProfiles;
    private final ProfileRepository profiles;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public AiModelService(AiModelRepository repository,ObjectProvider<ImageRegistry> registry,ObjectProvider<ResourceProfileRepository> resourceProfiles,
                          ProfileRepository profiles,PlatformTransactionManager transactions,Clock clock) {
        this.repository=repository;this.registry=registry;this.resourceProfiles=resourceProfiles;this.profiles=profiles;
        this.transaction=new TransactionTemplate(transactions);this.clock=clock;
    }

    // ---- registry browsing ----------------------------------------------------------------------------------------

    public Map<String,Object> registryStatus() {
        var configured=registry.getIfAvailable();
        var map=ordered("configured",configured!=null);
        map.put("host",configured==null?null:configured.host());
        return map;
    }
    public Map<String,Object> repositories(String last,int limit) {
        var page=call(()->registry().repositories(blank(last),pageSize(limit)));
        return ordered("items",page.items(),"next",page.next());
    }
    public Map<String,Object> tags(String repository,String last,int limit) {
        var page=call(()->registry().tags(repository,blank(last),pageSize(limit)));
        return ordered("repository",repository,"items",page.items(),"next",page.next());
    }
    public Map<String,Object> manifest(String repository,String reference) {
        var image=registry();
        return AiModelDocuments.resolved(image.host(),call(()->image.resolve(repository,reference)));
    }

    // ---- catalog --------------------------------------------------------------------------------------------------

    public Creation<AiModelExecution> register(String key,String body) {
        UUID idempotency=idempotencyKey(key);
        AiModelInput.Register input;
        try { input=AiModelInput.register(body); }
        catch(IllegalArgumentException e){throw invalid(e.getMessage());}
        String fingerprint=input.fingerprint();
        // A stored request is answered from the database: a later registry outage or tag move never changes it.
        var replay=repository.byIdempotencyKey(idempotency);
        if(replay.isPresent())return replayed(replay.get(),fingerprint);
        if(repository.byIdentity(input.modelKey(),input.modelVersion(),input.configName()).isPresent())throw exists(input);
        var image=registry();
        var verified=call(()->image.verify(input.image().repository(),input.image().digest(),input.image().platform()));
        if(!verified.platform().runnable())throw invalid("등록할 수 있는 플랫폼은 linux/amd64·linux/arm64입니다.");
        var requirement=requirement(input,image.host(),verified);
        var document=ordered("modelKey",input.modelKey(),"displayName",input.displayName(),"modelVersion",input.modelVersion(),
            "configName",input.configName(),"description",input.description());
        document.put("image",AiModelDocuments.image(image.host(),verified,input.image().tag(),clock.instant().toString()));
        document.put("execution",ordered("command",input.command(),"args",input.args()));
        document.put("inputConditions",input.inputConditions());
        document.put("runtime",input.runtime());
        // Without declared requirements the entry is registered first and measured later (requirements: null).
        Map<String,Object> requirements=null;
        if(input.declared()) {
            requirements=ordered("source",requirement.source().name(),"measurementStatus",requirement.status());
            requirements.put("resources",AiModelDocuments.resources(input.cpu(),input.memory(),input.accelerators()));
        }
        document.put("requirements",requirements);
        document.put("resourceProfile",requirement.profile());
        document.put("evidence",requirement.evidence());
        document.put("limitations",AiModelDocuments.LIMITATIONS);
        String digest=JSON.digest(AiModelDocuments.DOCUMENT_SCOPE,document);
        var entry=new AiModelExecution(UUID.randomUUID(),input.modelKey(),input.modelVersion(),input.configName(),input.displayName(),
            input.image().repository(),verified.digest(),input.resourceProfileId(),requirement.status(),idempotency,fingerprint,digest,
            JSON.canonical(document),clock.instant());
        return transaction.execute(status->{
            repository.lock(idempotency,input.modelKey(),input.modelVersion(),input.configName());
            var existing=repository.byIdempotencyKey(idempotency);
            if(existing.isPresent())return replayed(existing.get(),fingerprint);
            if(repository.byIdentity(input.modelKey(),input.modelVersion(),input.configName()).isPresent())throw exists(input);
            repository.create(entry);
            return new Creation<>(entry,true);
        });
    }

    public AiModelExecution get(UUID id) {
        return repository.find(id).orElseThrow(()->new ControlPlaneException(404,"AI_MODEL_NOT_FOUND","AI 모델 실행 구성을 찾을 수 없습니다."));
    }
    public List<AiModelExecution> list(String query,int limit,int offset) {
        if(limit<1||limit>100||offset<0||offset>1000000)throw invalid("limit은 1–100, offset은 0–1000000입니다.");
        String q=query==null?null:query.strip();
        if(q!=null&&(q.length()>100||q.chars().anyMatch(Character::isISOControl)))throw invalid("검색어는 100자 이하입니다.");
        return repository.list(q==null||q.isEmpty()?null:q,limit+1,offset);
    }

    // ---- requirements and source resource profile -----------------------------------------------------------------

    private record Requirement(Source source,String status,Map<String,Object> profile,Object evidence) {}

    /**
     * Without a source profile every basis must be MANUAL (UNMEASURED). With one, the profile's SERVICE image must be
     * this exact registry repository@digest and its architectures must include the verified platform. Its evidence is
     * kept only when execution command/args, input conditions, runtime and resources are all unchanged; any edit makes
     * the entry a manual one, and a measured/recommended label on an edited value is rejected.
     */
    @SuppressWarnings("unchecked")
    private Requirement requirement(AiModelInput.Register input,String host,VerifiedImage verified) {
        if(!input.declared())return new Requirement(Source.MANUAL,"UNMEASURED",null,null);
        if(input.resourceProfileId()==null) {
            requireManual(input,"분석·프로파일 근거 없이 직접 입력한 요구량은 사용자 지정(MANUAL)이어야 합니다.");
            return new Requirement(Source.MANUAL,"UNMEASURED",null,null);
        }
        var store=resourceProfiles.getIfAvailable();
        ResourceProfile source=store==null?null:store.profile(input.resourceProfileId()).orElse(null);
        if(source==null)throw new ControlPlaneException(404,"RESOURCE_PROFILE_NOT_FOUND","선택한 자원 프로파일을 찾을 수 없습니다.");
        var service=profiles.find(source.serviceProfileVersionId()).filter(v->v.identity().kind()==ProfileIdentity.Kind.SERVICE)
            .orElseThrow(()->new ControlPlaneException(404,"SERVICE_VERSION_NOT_FOUND","자원 프로파일의 SERVICE 버전을 찾을 수 없습니다."));
        var spec=(Map<String,Object>)JSON.decode(service.specJson());
        String specImage=String.valueOf(spec.get("image"));
        String base=host+"/"+verified.repository()+"@";
        if(!specImage.equals(base+verified.digest())&&!specImage.equals(base+verified.platformManifestDigest()))
            throw new ControlPlaneException(409,"RESOURCE_PROFILE_IMAGE_MISMATCH","자원 프로파일의 서비스 이미지("+specImage+")가 선택한 Registry 이미지("
                +base+verified.digest()+")와 같은 불변 이미지가 아닙니다.");
        var document=(Map<String,Object>)JSON.decode(source.documentJson());
        var compatibility=(Map<String,Object>)document.get("compatibility");
        var architectures=compatibility==null?List.of():(List<Object>)compatibility.getOrDefault("architectures",List.of());
        if(!architectures.contains(verified.platform().architecture()))
            throw new ControlPlaneException(409,"RESOURCE_PROFILE_PLATFORM_MISMATCH","자원 프로파일의 호환 아키텍처("+architectures+")에 선택한 플랫폼("
                +verified.platform().label()+")이 없습니다.");
        var profile=ordered("id",source.id().toString(),"revision",source.revision(),"digest",source.digest(),
            "measurementStatus",source.evidenceStatus(),"service",document.get("service"),"createdAt",source.createdAt().toString());
        boolean unchanged=Objects.equals(nonEmpty((List<Object>)spec.get("command")),input.command())
            &&Objects.equals(nonEmpty((List<Object>)spec.get("args")),input.args())
            &&Objects.equals(document.get("inputConditions"),new TreeMap<>(input.inputConditions()))
            &&Objects.equals(compatibility==null?null:compatibility.get("runtime"),input.runtime())
            &&sameResources((Map<String,Object>)document.get("resources"),input);
        if(unchanged)return new Requirement(Source.RESOURCE_PROFILE,source.evidenceStatus(),profile,document.get("evidence"));
        requireManual(input,"원본 자원 프로파일과 실행 명령·입력 조건·런타임·요구량이 달라 실측·추천 근거를 유지할 수 없습니다. 수정한 값은 사용자 지정(MANUAL)입니다.");
        return new Requirement(Source.MANUAL_FROM_RESOURCE_PROFILE,"UNMEASURED",profile,null);
    }
    @SuppressWarnings("unchecked")
    private static boolean sameResources(Map<String,Object> stored,AiModelInput.Register input) {
        var wanted=JSON.decode(JSON.canonical(AiModelDocuments.resources(input.cpu(),input.memory(),input.accelerators())));
        return JSON.canonical(stored).equals(JSON.canonical(wanted));
    }
    private static List<Object> nonEmpty(List<Object> values) { return values==null||values.isEmpty()?null:values; }
    private static void requireManual(AiModelInput.Register input,String message) {
        if(input.cpu().basis()!=Basis.MANUAL||input.memory().basis()!=Basis.MANUAL)throw invalid(message);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Creation<AiModelExecution> replayed(AiModelExecution existing,String fingerprint) {
        if(!existing.requestFingerprint().equals(fingerprint))
            throw new ControlPlaneException(409,"IDEMPOTENCY_CONFLICT","같은 Idempotency-Key로 다른 내용의 모델 등록이 이미 있습니다.");
        return new Creation<>(existing,false);
    }
    private static ControlPlaneException exists(AiModelInput.Register input) {
        return new ControlPlaneException(409,"AI_MODEL_VERSION_EXISTS","모델 "+input.modelKey()+" 버전 "+input.modelVersion()+"의 실행 구성 '"+input.configName()
            +"'이 이미 등록되어 있습니다. 등록은 불변이므로 새 버전이나 다른 실행 구성명으로 등록하세요.");
    }
    private ImageRegistry registry() {
        var configured=registry.getIfAvailable();
        if(configured==null)throw new ControlPlaneException(503,"REGISTRY_NOT_CONFIGURED","서버에 컨테이너 Registry가 설정되지 않아 이미지를 조회·등록할 수 없습니다.");
        return configured;
    }
    private static <T> T call(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch(IllegalArgumentException e){throw new ControlPlaneException(400,"INVALID_IMAGE_REFERENCE","저장소·태그·digest·페이지 형식을 확인하세요.");}
        catch(RegistryException e){throw registryError(e);}
    }
    static ControlPlaneException registryError(RegistryException e) {
        return switch(e.reason()) {
            case NOT_CONFIGURED -> new ControlPlaneException(503,"REGISTRY_NOT_CONFIGURED",e.getMessage());
            case NOT_FOUND -> new ControlPlaneException(404,"IMAGE_NOT_FOUND",e.getMessage());
            case TIMEOUT -> new ControlPlaneException(504,"REGISTRY_TIMEOUT",e.getMessage());
            case UNSUPPORTED_ARTIFACT -> new ControlPlaneException(422,"UNSUPPORTED_IMAGE_ARTIFACT",e.getMessage());
            case PLATFORM_NOT_FOUND -> new ControlPlaneException(422,"IMAGE_PLATFORM_NOT_FOUND",e.getMessage());
            case DIGEST_MISMATCH -> new ControlPlaneException(502,"IMAGE_DIGEST_MISMATCH",e.getMessage());
            case UNAUTHORIZED -> new ControlPlaneException(502,"REGISTRY_UNAUTHORIZED",e.getMessage());
            case REDIRECT_BLOCKED -> new ControlPlaneException(502,"REGISTRY_REDIRECT_BLOCKED",e.getMessage());
            case TOO_LARGE -> new ControlPlaneException(502,"REGISTRY_RESPONSE_TOO_LARGE",e.getMessage());
            case INVALID_RESPONSE -> new ControlPlaneException(502,"REGISTRY_INVALID_RESPONSE",e.getMessage());
            case UNAVAILABLE -> new ControlPlaneException(502,"REGISTRY_UNAVAILABLE",e.getMessage());
        };
    }
    private static int pageSize(int limit) {
        if(limit<1||limit>100)throw invalid("limit은 1–100입니다.");
        return limit;
    }
    private static String blank(String value) { return value==null||value.isEmpty()?null:value; }
    private static UUID idempotencyKey(String key) {
        try { return UUID.fromString(key); }
        catch(RuntimeException e){throw invalid("Idempotency-Key는 UUID여야 합니다.");}
    }
    static ControlPlaneException invalid(String detail) {
        return new ControlPlaneException(400,"INVALID_AI_MODEL","AI 모델 등록 입력을 확인하세요."+(detail==null?"":" "+detail));
    }
}
