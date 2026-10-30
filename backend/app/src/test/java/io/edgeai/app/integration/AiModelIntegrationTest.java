package io.edgeai.app.integration;

import io.edgeai.adapters.registry.FakeRegistry;
import io.edgeai.app.service.ProfileService;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.resourceprofile.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Public REST seam of the AI model catalog on an isolated PostgreSQL database with a real-HTTP OCI registry
 * (in-process). Resource profiles used as sources are produced through their own public API.
 */
@SpringBootTest(properties={"edgeai.ai-models.enabled=true","edgeai.resource-profiling.enabled=true","edgeai.resource-profiling.namespaces=edgeai"})
@AutoConfigureMockMvc
@Import(ResourceProfileIntegrationTest.Sources.class)
class AiModelIntegrationTest {
    static final FakeRegistry REGISTRY;
    static { try { REGISTRY=new FakeRegistry(); } catch(Exception e) { throw new IllegalStateException(e); } }
    @DynamicPropertySource static void registry(DynamicPropertyRegistry p) {
        p.add("edgeai.ai-models.registry.url",REGISTRY::url);
        p.add("edgeai.ai-models.registry.timeout-seconds",()->"2");
    }
    @AfterAll static void close() { REGISTRY.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Autowired ResourceProfileIntegrationTest.FakeMetrics metrics;
    @Autowired ResourceProfileIntegrationTest.FakeKrr krr;
    private final JsonMapper json=new JsonMapper();
    private String repo;

    @BeforeEach void reset() {
        REGISTRY.reset();
        repo="models/m"+UUID.randomUUID().toString().substring(0,8);
    }

    private String encode(Object value){return json.writeValueAsString(value);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int statusCode) throws Exception {
        String body=mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return json.readTree(body.isEmpty()?"{}":body);
    }
    private JsonNode register(String key,Object body,int statusCode) throws Exception {
        var request=post("/api/v1/ai-models").contentType("application/json").content(body instanceof String s?s:encode(body));
        if(key!=null)request.header("Idempotency-Key",key);
        return perform(request,statusCode);
    }
    private JsonNode register(Object body,int statusCode) throws Exception { return register(UUID.randomUUID().toString(),body,statusCode); }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM edgeai.ai_model_execution",Long.class); }

    static Map<String,Object> cpu(Object min,Object request,Object max,String basis){
        var m=new LinkedHashMap<String,Object>();m.put("minMillicores",min);m.put("requestMillicores",request);m.put("maxMillicores",max);m.put("basis",basis);return m;
    }
    static Map<String,Object> memory(Object min,Object request,Object max,String basis){
        var m=new LinkedHashMap<String,Object>();m.put("minBytes",min);m.put("requestBytes",request);m.put("maxBytes",max);m.put("basis",basis);return m;
    }
    static Map<String,Object> gpu(){
        return new LinkedHashMap<>(Map.of("kind","GPU","resourceName","nvidia.com/gpu","unit","COUNT","min",1,"request",1,"max",1,
            "deviceMemoryBytes",4294967296L,"vendor","NVIDIA","models",List.of("RTX 5080")));
    }
    private Map<String,Object> body(String key,String version,String digest,String tag,String arch) {
        var image=new LinkedHashMap<String,Object>();
        image.put("repository",repo);image.put("tag",tag);image.put("digest",digest);
        image.put("platform",Map.of("os","linux","architecture",arch));
        var b=new LinkedHashMap<String,Object>();
        b.put("modelKey",key);b.put("displayName","Detector "+version);b.put("modelVersion",version);b.put("description","객체 탐지");
        b.put("image",image);
        b.put("execution",Map.of("command",List.of("python3","/app/serve.py"),"args",List.of("--port","8080")));
        b.put("inputConditions",Map.of("batchSize","1","resolution","640x640"));
        b.put("runtime",Map.of("name","onnxruntime","version","1.20.1"));
        b.put("resources",new LinkedHashMap<>(Map.of("cpu",cpu(100,250,2000,"MANUAL"),"memory",memory(268435456L,536870912L,1073741824L,"MANUAL"),"accelerators",List.of(gpu()))));
        return b;
    }
    private static String key(){return "detector-"+UUID.randomUUID().toString().substring(0,8);}

    @Test void manualRegistrationIsVerifiedStoredListedAndImmutable() throws Exception {
        var image=REGISTRY.image(repo,"1.0","linux","amd64");
        String key=key();
        var created=register(body(key,"1.0.0",image.digest(),"1.0","amd64"),201);
        assertThat(created.path("id").asText()).isNotEmpty();
        assertThat(created.path("configName").asText()).isEqualTo("default");
        assertThat(created.path("measurementStatus").asText()).isEqualTo("UNMEASURED");
        assertThat(created.path("requirements").path("source").asText()).isEqualTo("MANUAL");
        assertThat(created.path("requirements").path("resources").path("cpu").path("basis").asText()).isEqualTo("MANUAL");
        var stored=created.path("image");
        assertThat(stored.path("registry").asText()).isEqualTo(REGISTRY.host());
        assertThat(stored.path("digest").asText()).isEqualTo(image.digest());
        assertThat(stored.path("reference").asText()).isEqualTo(REGISTRY.host()+"/"+repo+"@"+image.digest());
        assertThat(stored.path("kind").asText()).isEqualTo("MANIFEST");
        assertThat(stored.path("sourceTag").asText()).isEqualTo("1.0");
        assertThat(stored.path("platform").path("label").asText()).isEqualTo("linux/amd64");
        assertThat(stored.path("platformManifestDigest").asText()).isEqualTo(image.digest());
        assertThat(stored.path("configDigest").asText()).matches("sha256:[a-f0-9]{64}");
        assertThat(created.path("execution").path("command")).extracting(JsonNode::asText).containsExactly("python3","/app/serve.py");
        assertThat(created.path("resourceProfile").isNull()).isTrue();
        assertThat(created.path("evidence").isNull()).isTrue();
        String id=created.path("id").asText();
        assertThat(perform(get("/api/v1/ai-models/"+id),200)).isEqualTo(created);
        var listed=perform(get("/api/v1/ai-models").param("q",key.toUpperCase()),200);
        assertThat(listed.path("items")).hasSize(1);
        assertThat(listed.path("items").get(0)).isEqualTo(created);
        assertThat(perform(get("/api/v1/ai-models").param("q","%"),200).path("items")).noneMatch(n->n.path("id").asText().equals(id));
        perform(get("/api/v1/ai-models/"+UUID.randomUUID()),404);
        perform(get("/api/v1/ai-models").param("limit","0"),400);
        // Paging: newest first with nextOffset.
        var second=register(body(key,"1.0.1",image.digest(),null,"amd64"),201);
        var page=perform(get("/api/v1/ai-models").param("q",key).param("limit","1"),200);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(second.path("id").asText());
        assertThat(page.path("nextOffset").asInt()).isEqualTo(1);
        var next=perform(get("/api/v1/ai-models").param("q",key).param("limit","1").param("offset","1"),200);
        assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(id);
        assertThat(next.path("nextOffset").isNull()).isTrue();
        // Registration is immutable; a moved tag never changes the stored digest.
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.ai_model_execution SET display_name='x' WHERE id=?",UUID.fromString(id))).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.ai_model_execution WHERE id=?",UUID.fromString(id))).hasMessageContaining("immutable");
        REGISTRY.reset();
        REGISTRY.image(repo,"1.0","linux","arm64");
        assertThat(perform(get("/api/v1/ai-models/"+id),200).path("image").path("digest").asText()).isEqualTo(image.digest());
    }

    @Test void indexKeepsIndexDigestAndSelectedPlatformManifestAndDuplicatesConflict() throws Exception {
        var index=REGISTRY.multiPlatform(repo,"2.0");
        var resolved=perform(get("/api/v1/ai-models/registry/manifest").param("repository",repo).param("reference","2.0"),200);
        assertThat(resolved.path("kind").asText()).isEqualTo("INDEX");
        assertThat(resolved.path("digest").asText()).isEqualTo(index.digest());
        assertThat(resolved.path("platforms")).extracting(p->p.path("label").asText()).containsExactly("linux/amd64","linux/arm64/v8");
        String armDigest=resolved.path("platforms").get(1).path("manifestDigest").asText();
        String key=key();
        var arm=body(key,"2.0.0",index.digest(),"2.0","arm64");
        arm.put("configName","arm64-npu");
        var created=register(arm,201);
        assertThat(created.path("image").path("kind").asText()).isEqualTo("INDEX");
        assertThat(created.path("image").path("digest").asText()).isEqualTo(index.digest());
        assertThat(created.path("image").path("platformManifestDigest").asText()).isEqualTo(armDigest);
        assertThat(created.path("image").path("platform").path("variant").asText()).isEqualTo("v8");
        // Another configuration of the same model version is a separate entry; the same identity is a conflict.
        assertThat(register(body(key,"2.0.0",index.digest(),"2.0","amd64"),201).path("configName").asText()).isEqualTo("default");
        assertThat(register(arm,409).path("code").asText()).isEqualTo("AI_MODEL_VERSION_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.ai_model_execution WHERE model_key=?",Long.class,key)).isEqualTo(2);
    }

    @Test void idempotentReplayIgnoresLaterRegistryStateAndDifferentContentConflicts() throws Exception {
        var image=REGISTRY.image(repo,"1.0","linux","amd64");
        String key=key(),idempotency=UUID.randomUUID().toString();
        var created=register(idempotency,body(key,"1.0.0",image.digest(),"1.0","amd64"),201);
        var replay=register(idempotency,body(key,"1.0.0",image.digest(),"1.0","amd64"),200);
        assertThat(replay).isEqualTo(created);
        REGISTRY.reset(); // registry emptied: a replay of the stored request still answers from the database
        assertThat(register(idempotency,body(key,"1.0.0",image.digest(),"1.0","amd64"),200)).isEqualTo(created);
        var changed=body(key,"1.0.0",image.digest(),"1.0","amd64");changed.put("description","다른 설명");
        assertThat(register(idempotency,changed,409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        register(null,body(key,"1.0.1",image.digest(),"1.0","amd64"),400);
        register("not-a-uuid",body(key,"1.0.1",image.digest(),"1.0","amd64"),400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.ai_model_execution WHERE model_key=?",Long.class,key)).isEqualTo(1);
        // The tag moved after the user resolved it: the confirmed digest is stored and the tag kept as its source.
        var original=REGISTRY.image(repo,"live","linux","amd64");
        REGISTRY.manifests.put(repo+":live",REGISTRY.image(repo,null,"linux","arm64"));
        var pinned=register(body(key,"1.1.0",original.digest(),"live","amd64"),201);
        assertThat(pinned.path("image").path("digest").asText()).isEqualTo(original.digest());
        assertThat(pinned.path("image").path("sourceTag").asText()).isEqualTo("live");
    }

    @Test void concurrentRegistrationsOfOneIdentityStoreExactlyOneEntry() throws Exception {
        var image=REGISTRY.image(repo,"1.0","linux","amd64");
        String key=key();
        var pool=Executors.newFixedThreadPool(8);
        try {
            var start=new CountDownLatch(1);
            var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();
                return mvc.perform(post("/api/v1/ai-models").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
                    .content(encode(body(key,"1.0.0",image.digest(),"1.0","amd64"))).with(user("test")).with(csrf())).andReturn().getResponse().getStatus();}));
            String shared=UUID.randomUUID().toString();
            var replays=new ArrayList<Future<String>>();
            for(int i=0;i<6;i++)replays.add(pool.submit(()->{start.await();
                return mvc.perform(post("/api/v1/ai-models").header("Idempotency-Key",shared).contentType("application/json")
                    .content(encode(body(key,"9.9.9",image.digest(),"1.0","amd64"))).with(user("test")).with(csrf())).andReturn().getResponse().getContentAsString();}));
            start.countDown();
            var statuses=new ArrayList<Integer>();for(var f:results)statuses.add(f.get(60,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(201,409);
            assertThat(statuses.stream().filter(s->s==201)).hasSize(1);
            var ids=new HashSet<String>();for(var f:replays)ids.add(json.readTree(f.get(60,TimeUnit.SECONDS)).path("id").asText());
            assertThat(ids).hasSize(1).doesNotContain("");
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.ai_model_execution WHERE model_key=? AND model_version='1.0.0'",Long.class,key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.ai_model_execution WHERE model_key=? AND model_version='9.9.9'",Long.class,key)).isEqualTo(1);
    }

    @Test @SuppressWarnings("unchecked") void invalidInputsForgedBasisAndBadImagesAreRejectedWithoutSaving() throws Exception {
        var image=REGISTRY.image(repo,"1.0","linux","amd64");
        long before=count();
        var bad=new ArrayList<Map<String,Object>>();
        for(var cpu:List.of(cpu(300,250,2000,"MANUAL"),cpu(100,250,200,"MANUAL"),cpu(0,250,2000,"MANUAL"),cpu(1.5,250,2000,"MANUAL"),
                cpu("100m",250,2000,"MANUAL"),cpu(100,250,2000000,"MANUAL"),cpu(100,250,2000,"MEASURED"),cpu(100,250,2000,"RECOMMENDED"))) {
            var b=body(key(),"1.0.0",image.digest(),"1.0","amd64");((Map<String,Object>)b.get("resources")).put("cpu",cpu);bad.add(b);
        }
        var nan=body(key(),"1.0.0",image.digest(),"1.0","amd64");
        var badMemory=body(key(),"1.0.0",image.digest(),"1.0","amd64");((Map<String,Object>)badMemory.get("resources")).put("memory",memory(2,1,3,"MANUAL"));bad.add(badMemory);
        var gpu=body(key(),"1.0.0",image.digest(),"1.0","amd64");var g=gpu();g.put("resourceName","gpu");((Map<String,Object>)gpu.get("resources")).put("accelerators",List.of(g));bad.add(gpu);
        var freeImage=body(key(),"1.0.0",image.digest(),"1.0","amd64");freeImage.put("image",REGISTRY.host()+"/"+repo+":1.0");bad.add(freeImage);
        var urlRepo=body(key(),"1.0.0",image.digest(),"1.0","amd64");((Map<String,Object>)urlRepo.get("image")).put("repository","http://169.254.169.254/x");bad.add(urlRepo);
        var registryField=body(key(),"1.0.0",image.digest(),"1.0","amd64");((Map<String,Object>)registryField.get("image")).put("registry","evil.example:5000");bad.add(registryField);
        var shortDigest=body(key(),"1.0.0","sha256:abc","1.0","amd64");bad.add(shortDigest);
        var windows=body(key(),"1.0.0",image.digest(),"1.0","amd64");((Map<String,Object>)windows.get("image")).put("platform",Map.of("os","windows","architecture","amd64"));bad.add(windows);
        var upper=body("Detector","1.0.0",image.digest(),"1.0","amd64");bad.add(upper);
        var config=body(key(),"1.0.0",image.digest(),"1.0","amd64");config.put("configName","GPU Config");bad.add(config);
        var unknown=body(key(),"1.0.0",image.digest(),"1.0","amd64");unknown.put("measurementStatus","MEASURED");bad.add(unknown);
        for(var b:bad)assertThat(register(b,400).path("code").asText()).as(encode(b)).isIn("INVALID_AI_MODEL");
        register(encode(nan).replace("\"minMillicores\":100","\"minMillicores\":NaN"),400);
        assertThat(count()).isEqualTo(before);
        // Registry-side failures are reported and nothing is stored.
        String key=key();
        assertThat(register(body(key,"1.0.0","sha256:"+"9".repeat(64),null,"amd64"),404).path("code").asText()).isEqualTo("IMAGE_NOT_FOUND");
        assertThat(register(body(key,"1.0.0",image.digest(),"1.0","arm64"),422).path("code").asText()).isEqualTo("IMAGE_PLATFORM_NOT_FOUND");
        var helm=REGISTRY.manifest(repo,"chart",FakeRegistry.OCI_MANIFEST,"{\"schemaVersion\":2,\"mediaType\":\""+FakeRegistry.OCI_MANIFEST
            +"\",\"config\":{\"mediaType\":\"application/vnd.cncf.helm.config.v1+json\",\"digest\":\"sha256:"+"3".repeat(64)+"\",\"size\":2},\"layers\":[]}");
        assertThat(register(body(key,"1.0.0",helm.digest(),"chart","amd64"),422).path("code").asText()).isEqualTo("UNSUPPORTED_IMAGE_ARTIFACT");
        var tampered=new FakeRegistry.Content(image.mediaType(),(new String(image.bytes())+"\n").getBytes(),image.digest());
        REGISTRY.manifests.put(repo+"@"+image.digest(),tampered);
        assertThat(register(body(key,"1.0.0",image.digest(),"1.0","amd64"),502).path("code").asText()).isEqualTo("IMAGE_DIGEST_MISMATCH");
        REGISTRY.override=e->{e.getResponseHeaders().add("Location","http://169.254.169.254/");FakeRegistry.send(e,302,"text/plain","",null);return true;};
        assertThat(register(body(key,"1.0.0",image.digest(),"1.0","amd64"),502).path("code").asText()).isEqualTo("REGISTRY_REDIRECT_BLOCKED");
        REGISTRY.override=e->{Thread.sleep(3500);FakeRegistry.send(e,200,"application/json","{}",null);return true;};
        assertThat(register(body(key,"1.0.0",image.digest(),"1.0","amd64"),504).path("code").asText()).isEqualTo("REGISTRY_TIMEOUT");
        assertThat(count()).isEqualTo(before);
    }

    @Test void registryBrowsingPagesExplicitlyAndValidatesInput() throws Exception {
        for(String tag:List.of("a","b","c"))REGISTRY.image(repo,tag,"linux","amd64");
        REGISTRY.image(repo+"-other","1","linux","amd64");
        var status=perform(get("/api/v1/ai-models/registry"),200);
        assertThat(status.path("configured").asBoolean()).isTrue();
        assertThat(status.path("host").asText()).isEqualTo(REGISTRY.host());
        var repos=perform(get("/api/v1/ai-models/registry/repositories").param("limit","1"),200);
        assertThat(repos.path("items")).hasSize(1);
        assertThat(repos.path("next").asText()).isEqualTo(repos.path("items").get(0).asText());
        var tags=perform(get("/api/v1/ai-models/registry/tags").param("repository",repo).param("limit","2"),200);
        assertThat(tags.path("items")).extracting(JsonNode::asText).containsExactly("a","b");
        assertThat(tags.path("next").asText()).isEqualTo("b");
        var more=perform(get("/api/v1/ai-models/registry/tags").param("repository",repo).param("limit","2").param("last","b"),200);
        assertThat(more.path("items")).extracting(JsonNode::asText).containsExactly("c");
        assertThat(more.path("next").isNull()).isTrue();
        var manifest=perform(get("/api/v1/ai-models/registry/manifest").param("repository",repo).param("reference","a"),200);
        assertThat(manifest.path("kind").asText()).isEqualTo("MANIFEST");
        assertThat(manifest.path("platforms").get(0).path("runnable").asBoolean()).isTrue();
        perform(get("/api/v1/ai-models/registry/tags").param("repository","http://evil/x"),400);
        perform(get("/api/v1/ai-models/registry/manifest").param("repository",repo).param("reference","../../_catalog"),400);
        perform(get("/api/v1/ai-models/registry/repositories").param("limit","101"),400);
        assertThat(perform(get("/api/v1/ai-models/registry/tags").param("repository","missing/repo"),404).path("code").asText()).isEqualTo("IMAGE_NOT_FOUND");
        REGISTRY.override=e->{FakeRegistry.send(e,503,"text/plain","down",null);return true;};
        var down=perform(get("/api/v1/ai-models/registry/repositories"),502);
        assertThat(down.path("code").asText()).isEqualTo("REGISTRY_UNAVAILABLE");
        assertThat(down.toString()).doesNotContain("down");
    }

    // ---- source resource profiles -----------------------------------------------------------------------------------

    private record Source(String profileId,String profileDigest,String serviceId){}
    @SuppressWarnings("unchecked")
    private Source measuredProfile(String image,List<String> architectures) throws Exception {
        var spec=(Map<String,Object>)json.readValue(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")),Map.class);
        spec.put("image",image);
        spec.put("command",List.of("python3","/app/serve.py"));spec.put("args",List.of("--port","8080"));
        spec.put("platform",Map.of("os","linux","architectures",architectures));
        String key="svc-"+UUID.randomUUID().toString().substring(0,8);
        var service=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key",key,"version","1.0.0","spec",spec))).version();
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),start=now.minus(Duration.ofHours(1));
        metrics.measurement=new WorkloadMeasurement(start,now,15,List.of(new ObservedPod("detector-1","0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b","gpu-node-1")),
            new UsageSummary(ObservationStatus.AVAILABLE,"millicores",12.0,80.5,150.0,190.0,240,start,now,null),
            new UsageSummary(ObservationStatus.AVAILABLE,"bytes",3.0e8,4.0e8,4.5e8,4.6e8,240,start,now,null),"cadvisor",List.of());
        krr.next=new KrrRecommendation(KrrRecommendation.Status.AVAILABLE,null,177L,null,530579456L,530579456L,null,null,now.minus(Duration.ofHours(24)),now,24,1,List.of(),Map.of("tool","krr"));
        var analysis=perform(post("/api/v1/resource-profiles/analyses").contentType("application/json").content(encode(Map.of(
            "serviceProfileVersionId",service.id().toString(),"target",Map.of("namespace","edgeai","kind","Deployment","name","detector","container","model"),
            "window",Map.of("start",start.toString(),"end",now.toString()),"recommendation",Map.of("historyHours",24)))),201);
        var save=new LinkedHashMap<String,Object>();
        save.put("serviceProfileVersionId",service.id().toString());save.put("analysisId",analysis.path("id").asText());
        save.put("inputConditions",Map.of("batchSize","1","resolution","640x640"));
        save.put("compatibility",Map.of("architectures",architectures,"runtime",Map.of("name","onnxruntime","version","1.20.1")));
        save.put("resources",Map.of("cpu",cpu(100,177,2000,"RECOMMENDED"),"memory",memory(268435456L,450000000L,1073741824L,"MEASURED"),"accelerators",List.of(gpu())));
        var profile=perform(post("/api/v1/resource-profiles").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(encode(save)),201);
        assertThat(profile.path("measurementStatus").asText()).isEqualTo("MEASURED");
        return new Source(profile.path("id").asText(),profile.path("digest").asText(),service.id().toString());
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> fromProfile(String key,String digest,String arch,Source source) {
        var b=body(key,"1.0.0",digest,null,arch);
        b.put("resourceProfileId",source.profileId());
        b.put("resources",new LinkedHashMap<>(Map.of("cpu",cpu(100,177,2000,"RECOMMENDED"),"memory",memory(268435456L,450000000L,1073741824L,"MEASURED"),"accelerators",List.of(gpu()))));
        return b;
    }

    @Test @SuppressWarnings("unchecked") void sourceResourceProfileEvidenceIsKeptOnlyForTheSameImageCommandAndRequirements() throws Exception {
        var index=REGISTRY.multiPlatform(repo,"1.0");
        var source=measuredProfile(REGISTRY.host()+"/"+repo+"@"+index.digest(),List.of("amd64"));
        String key=key();
        var reused=register(fromProfile(key,index.digest(),"amd64",source),201);
        assertThat(reused.path("requirements").path("source").asText()).isEqualTo("RESOURCE_PROFILE");
        assertThat(reused.path("measurementStatus").asText()).isEqualTo("MEASURED");
        assertThat(reused.path("resourceProfileId").asText()).isEqualTo(source.profileId());
        assertThat(reused.path("resourceProfile").path("digest").asText()).isEqualTo(source.profileDigest());
        assertThat(reused.path("evidence").path("recommendation").path("cpu").path("requestMillicores").asLong()).isEqualTo(177);
        assertThat(jdbc.queryForObject("SELECT resource_profile_id::text FROM edgeai.ai_model_execution WHERE id=?::uuid",String.class,reused.path("id").asText())).isEqualTo(source.profileId());

        // Edited value still labelled measured/recommended: forged evidence.
        var forged=fromProfile(key,index.digest(),"amd64",source);forged.put("modelVersion","1.0.1");
        ((Map<String,Object>)forged.get("resources")).put("cpu",cpu(100,999,2000,"RECOMMENDED"));
        register(forged,400);
        // A different command with the cited bases cannot keep the evidence either.
        var command=fromProfile(key,index.digest(),"amd64",source);command.put("modelVersion","1.0.2");
        command.put("execution",Map.of("command",List.of("python3","/app/other.py")));
        register(command,400);
        var conditions=fromProfile(key,index.digest(),"amd64",source);conditions.put("modelVersion","1.0.3");
        conditions.put("inputConditions",Map.of("batchSize","8"));
        register(conditions,400);
        // The same edit marked as the user's own values is stored as a manual entry that still names its source profile.
        var manual=fromProfile(key,index.digest(),"amd64",source);manual.put("modelVersion","1.0.4");
        ((Map<String,Object>)manual.get("resources")).put("cpu",cpu(100,999,2000,"MANUAL"));
        ((Map<String,Object>)manual.get("resources")).put("memory",memory(268435456L,450000000L,1073741824L,"MANUAL"));
        var edited=register(manual,201);
        assertThat(edited.path("requirements").path("source").asText()).isEqualTo("MANUAL_FROM_RESOURCE_PROFILE");
        assertThat(edited.path("measurementStatus").asText()).isEqualTo("UNMEASURED");
        assertThat(edited.path("evidence").isNull()).isTrue();
        assertThat(edited.path("resourceProfileId").asText()).isEqualTo(source.profileId());

        // Platform outside the profile's architectures, a different image, or a missing profile are rejected.
        var arm=fromProfile(key,index.digest(),"arm64",source);arm.put("modelVersion","1.0.5");
        assertThat(register(arm,409).path("code").asText()).isEqualTo("RESOURCE_PROFILE_PLATFORM_MISMATCH");
        var other=REGISTRY.image(repo,"other","linux","amd64");
        var mismatch=fromProfile(key,other.digest(),"amd64",source);mismatch.put("modelVersion","1.0.6");
        assertThat(register(mismatch,409).path("code").asText()).isEqualTo("RESOURCE_PROFILE_IMAGE_MISMATCH");
        var missing=fromProfile(key,index.digest(),"amd64",source);missing.put("modelVersion","1.0.7");missing.put("resourceProfileId",UUID.randomUUID().toString());
        assertThat(register(missing,404).path("code").asText()).isEqualTo("RESOURCE_PROFILE_NOT_FOUND");
        // A profile whose SERVICE image is the selected platform manifest digest is the same immutable image.
        var amdDigest=perform(get("/api/v1/ai-models/registry/manifest").param("repository",repo).param("reference","1.0"),200).path("platforms").get(0).path("manifestDigest").asText();
        var platformSource=measuredProfile(REGISTRY.host()+"/"+repo+"@"+amdDigest,List.of("amd64","arm64"));
        var platformBody=fromProfile(key,index.digest(),"amd64",platformSource);platformBody.put("modelVersion","1.0.8");
        assertThat(register(platformBody,201).path("requirements").path("source").asText()).isEqualTo("RESOURCE_PROFILE");
        // The source profile itself is unchanged.
        assertThat(perform(get("/api/v1/resource-profiles/"+source.profileId()),200).path("digest").asText()).isEqualTo(source.profileDigest());
    }
}
