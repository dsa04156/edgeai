package io.edgeai.app.integration;

import io.edgeai.app.service.ProfileService;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.resourceprofile.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Public REST seams of the 3-3 resource profile feature on an isolated PostgreSQL database. Prometheus and KRR are
 * replaced only at the domain ports; their adapters have their own parsing tests against real response shapes.
 */
@SpringBootTest(properties={"edgeai.resource-profiling.enabled=true","edgeai.resource-profiling.namespaces=edgeai,lab"})
@AutoConfigureMockMvc
@Import(ResourceProfileIntegrationTest.Sources.class)
class ResourceProfileIntegrationTest {
    static final Instant NOW=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    static final class FakeMetrics implements WorkloadMetricsSource {
        volatile WorkloadMeasurement measurement;
        volatile DeviceObservation.Attribution attribution=DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER;
        final List<WorkloadTarget> measured=Collections.synchronizedList(new ArrayList<>());
        final List<Duration> discovered=Collections.synchronizedList(new ArrayList<>());
        public List<WorkloadCandidate> workloads(String namespace,Instant start,Instant end) {
            discovered.add(Duration.between(start,end));
            return List.of(new WorkloadCandidate(namespace,WorkloadTarget.Kind.Deployment,"detector",List.of("model","sidecar")));
        }
        public List<ObservedPod> pods(WorkloadTarget target,Instant start,Instant end) {
            return List.of(new ObservedPod("detector-7d9-abc","0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b","gpu-node-1"));
        }
        public WorkloadMeasurement measure(WorkloadTarget target,Instant start,Instant end) { measured.add(target);return measurement; }
        public DeviceObservation device(DeviceRef device,String namespace,List<ObservedPod> pods,Instant start,Instant end) {
            var util=new UsageSummary(ObservationStatus.AVAILABLE,"percent",3.0,41.5,88.0,97.0,120,start,end,null);
            var memory=new UsageSummary(ObservationStatus.AVAILABLE,"bytes",1.0e9,2.0e9,3.5e9,3.9e9,120,start,end,null);
            return new DeviceObservation(device,"dcgm-exporter","NVIDIA GeForce RTX 5080",attribution,ObservationStatus.AVAILABLE,util,memory,1.7e10,
                "device-level telemetry");
        }
    }
    static final class FakeKrr implements RecommendationSource {
        volatile KrrRecommendation next;
        final List<String> calls=Collections.synchronizedList(new ArrayList<>());
        public KrrRecommendation recommend(WorkloadTarget target,int historyHours) { calls.add(target.namespace()+"/"+target.kind()+"/"+target.name()+"/"+target.container()+"@"+historyHours);return next; }
    }
    @TestConfiguration static class Sources {
        @Bean FakeMetrics resourceMetrics(){return new FakeMetrics();}
        @Bean FakeKrr resourceKrr(){return new FakeKrr();}
    }
    @Autowired FakeMetrics metrics;
    @Autowired FakeKrr krr;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final JsonMapper json=new JsonMapper();

    @BeforeEach void reset() {
        metrics.attribution=DeviceObservation.Attribution.NODE_DEVICE_UNKNOWN_OWNER;metrics.measured.clear();krr.calls.clear();
        metrics.measurement=measured(ObservationStatus.AVAILABLE,ObservationStatus.AVAILABLE);
        krr.next=new KrrRecommendation(KrrRecommendation.Status.AVAILABLE,null,177L,null,530579456L,530579456L,null,null,
            NOW.minus(Duration.ofHours(24)),NOW,24,1,List.of(),Map.of("tool","krr","version","v1.31.1","strategy","simple"));
    }
    private WorkloadMeasurement measured(ObservationStatus cpu,ObservationStatus memory) {
        Instant start=NOW.minus(Duration.ofHours(1));
        boolean cpuValued=cpu==ObservationStatus.AVAILABLE||cpu==ObservationStatus.STALE;
        var c=cpuValued?new UsageSummary(cpu,"millicores",12.0,80.5,150.0,190.0,240,start,NOW,null):UsageSummary.missing(cpu,"millicores",0,"no cAdvisor series");
        var m=memory==ObservationStatus.AVAILABLE?new UsageSummary(memory,"bytes",3.0e8,4.0e8,4.5e8,4.6e8,240,start,NOW,null):UsageSummary.missing(memory,"bytes",0,"no cAdvisor series");
        return new WorkloadMeasurement(start,NOW,15,List.of(new ObservedPod("detector-7d9-abc","0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b","gpu-node-1")),c,m,"cadvisor",List.of(new PodUsage("detector-7d9-abc",150.0,190.0,4.6e8,240)));
    }
    private String encode(Object value){return json.writeValueAsString(value);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int statusCode) throws Exception {
        String body=mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString();
        return json.readTree(body.isEmpty()?"{}":body);
    }
    private JsonNode save(String key,String body,int statusCode) throws Exception {
        var request=post("/api/v1/resource-profiles").contentType("application/json").content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        return perform(request,statusCode);
    }
    private JsonNode save(Object body,int statusCode) throws Exception {return save(UUID.randomUUID().toString(),encode(body),statusCode);}
    private JsonNode analyze(Object body,int statusCode) throws Exception {
        return perform(post("/api/v1/resource-profiles/analyses").contentType("application/json").content(encode(body)),statusCode);
    }
    @SuppressWarnings("unchecked") private record Service(UUID id,String key,String version){}
    @SuppressWarnings("unchecked") private Service service() throws Exception {
        var spec=(Map<String,Object>)json.readValue(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json")),Map.class);
        String key="rp-"+UUID.randomUUID().toString().substring(0,8);
        var version=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key",key,"version","1.2.3","spec",spec))).version();
        return new Service(version.id(),key,"1.2.3");
    }
    private static Map<String,Object> cpu(Object min,Object request,Object max,String basis){
        var map=new LinkedHashMap<String,Object>();map.put("minMillicores",min);map.put("requestMillicores",request);map.put("maxMillicores",max);map.put("basis",basis);return map;
    }
    private static Map<String,Object> memory(Object min,Object request,Object max,String basis){
        var map=new LinkedHashMap<String,Object>();map.put("minBytes",min);map.put("requestBytes",request);map.put("maxBytes",max);map.put("basis",basis);return map;
    }
    private static Map<String,Object> gpu(){
        return new LinkedHashMap<>(Map.of("kind","GPU","resourceName","nvidia.com/gpu","unit","COUNT","min",1,"request",1,"max",1,
            "deviceMemoryBytes",4294967296L,"vendor","NVIDIA","models",List.of("NVIDIA GeForce RTX 5080")));
    }
    private static Map<String,Object> manual(Service service) {
        var body=new LinkedHashMap<String,Object>();
        body.put("serviceProfileVersionId",service.id().toString());
        body.put("inputConditions",Map.of("batchSize","1","resolution","640x640"));
        body.put("compatibility",Map.of("architectures",List.of("amd64"),"runtime",Map.of("name","onnxruntime","version","1.20.1")));
        body.put("resources",new LinkedHashMap<>(Map.of("cpu",cpu(100,250,2000,"MANUAL"),"memory",memory(268435456L,536870912L,1073741824L,"MANUAL"),"accelerators",List.of(gpu()))));
        return body;
    }
    private Map<String,Object> analysis(Service service,boolean recommend) {
        var body=new LinkedHashMap<String,Object>();
        body.put("serviceProfileVersionId",service.id().toString());
        body.put("target",Map.of("namespace","edgeai","kind","Deployment","name","detector","container","model"));
        body.put("window",Map.of("start",NOW.minus(Duration.ofHours(1)).toString(),"end",NOW.toString()));
        body.put("devices",List.of(Map.of("node","gpu-node-1","kind","GPU","device","GPU-4c6e69de-8d09-e2cf-547a-234ac6e0e51a")));
        if(recommend)body.put("recommendation",Map.of("historyHours",24));
        return body;
    }

    @Test void manuallyEnteredUnmeasuredProfileIsSavedAsAnImmutableRevisionAndHandedOffExactly() throws Exception {
        var service=service();
        var first=save(manual(service),201);
        assertThat(first.path("revision").asInt()).isEqualTo(1);
        assertThat(first.path("measurementStatus").asText()).isEqualTo("UNMEASURED");
        assertThat(first.path("analysisId").isNull()).isTrue();
        assertThat(first.path("service").path("key").asText()).isEqualTo(service.key());
        assertThat(first.path("resources").path("cpu").path("requestMillicores").asLong()).isEqualTo(250);
        assertThat(first.path("resources").path("memory").path("basis").asText()).isEqualTo("MANUAL");
        assertThat(first.path("digest").asText()).matches("sha256:[a-f0-9]{64}");

        var second=save(manual(service),201);
        assertThat(second.path("revision").asInt()).isEqualTo(2);
        assertThat(second.path("id").asText()).isNotEqualTo(first.path("id").asText());

        var read=perform(get("/api/v1/resource-profiles/"+first.path("id").asText()),200);
        assertThat(read).isEqualTo(first);
        var list=perform(get("/api/v1/resource-profiles").param("serviceProfileVersionId",service.id().toString()),200);
        assertThat(list.path("items")).hasSize(2);
        assertThat(list.path("items").get(0).path("revision").asInt()).isEqualTo(2);

        var handoff=perform(get("/api/v1/resource-profiles/services/"+service.key()+"/versions/1.2.3/revisions/1/handoff"),200);
        assertThat(handoff.path("apiVersion").asText()).isEqualTo("edgeai.io/resource-profile-handoff/v1");
        assertThat(handoff.path("profile").path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(handoff.path("profile").path("revision").asInt()).isEqualTo(1);
        assertThat(handoff.path("profile").path("digest").asText()).isEqualTo(first.path("digest").asText());
        assertThat(handoff.path("service").path("profileVersionId").asText()).isEqualTo(service.id().toString());
        var cpu=handoff.path("resources").path("cpu");
        assertThat(cpu.path("min").asLong()).isEqualTo(100);assertThat(cpu.path("request").asLong()).isEqualTo(250);assertThat(cpu.path("max").asLong()).isEqualTo(2000);
        assertThat(cpu.path("unit").asText()).isEqualTo("millicores");
        assertThat(cpu.path("kubernetes").path("request").asText()).isEqualTo("250m");
        assertThat(handoff.path("resources").path("memory").path("kubernetes").path("max").asText()).isEqualTo("1Gi");
        var vpa=handoff.path("mappings").path("verticalPodAutoscaler");
        assertThat(vpa.path("controlledResources")).extracting(JsonNode::asText).containsExactly("cpu","memory");
        assertThat(vpa.path("minAllowed").path("cpu").asText()).isEqualTo("100m");
        assertThat(vpa.path("maxAllowed").path("memory").asText()).isEqualTo("1Gi");
        assertThat(vpa.path("recommendedUpdateMode").asText()).isEqualTo("Off");
        var accelerator=handoff.path("resources").path("accelerators").get(0);
        assertThat(accelerator.path("resourceName").asText()).isEqualTo("nvidia.com/gpu");
        assertThat(accelerator.path("deviceMemoryBytes").asLong()).isEqualTo(4294967296L);
        assertThat(accelerator.path("deviceMemoryReservable").asBoolean()).isFalse();
        assertThat(accelerator.path("verticalScaling").asText()).isEqualTo("NOT_SUPPORTED");
        assertThat(handoff.path("evidence").path("measurementStatus").asText()).isEqualTo("UNMEASURED");
        assertThat(perform(get("/api/v1/resource-profiles/"+first.path("id").asText()+"/handoff"),200)).isEqualTo(handoff);
        perform(get("/api/v1/resource-profiles/services/"+service.key()+"/versions/1.2.3/revisions/3/handoff"),404);

        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.resource_profile SET revision=9 WHERE id=?",UUID.fromString(first.path("id").asText())))
            .hasMessageContaining("immutable");
        assertThat(perform(get("/api/v1/resource-profiles/"+first.path("id").asText()),200)).isEqualTo(first);
        perform(delete("/api/v1/profiles/SERVICE/"+service.key()+"/versions/1.2.3"),409);
    }

    @Test void idempotencyKeyReplaysTheSameRevisionAndRejectsDifferentContent() throws Exception {
        var service=service();String key=UUID.randomUUID().toString();
        var created=save(key,encode(manual(service)),201);
        var replay=save(key,encode(manual(service)),200);
        assertThat(replay.path("id").asText()).isEqualTo(created.path("id").asText());
        var changed=manual(service);((Map<String,Object>)changed.get("resources")).put("cpu",cpu(100,300,2000,"MANUAL"));
        assertThat(save(key,encode(changed),409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        save(null,encode(manual(service)),400);
        assertThat(perform(get("/api/v1/resource-profiles").param("serviceProfileVersionId",service.id().toString()),200).path("items")).hasSize(1);
    }

    @Test void invalidQuantitiesRangesAndNumbersAreRejectedWithoutSaving() throws Exception {
        var service=service();
        List<Map<String,Object>> invalid=new ArrayList<>();
        for(var cpu:List.of(cpu(300,250,2000,"MANUAL"),cpu(100,250,200,"MANUAL"),cpu(0,250,2000,"MANUAL"),cpu(-1,250,2000,"MANUAL"),
                cpu(1.5,250,2000,"MANUAL"),cpu("100m",250,2000,"MANUAL"),cpu(100,250,2000000,"MANUAL"),cpu(100,250,2000,"GUESSED"),cpu(100,null,2000,"MANUAL"))) {
            var body=manual(service);((Map<String,Object>)body.get("resources")).put("cpu",cpu);invalid.add(body);
        }
        var noMemory=manual(service);((Map<String,Object>)noMemory.get("resources")).remove("memory");invalid.add(noMemory);
        var percent=manual(service);var share=gpu();share.put("resourceName","nvidia.com/gpucores");share.put("unit","PERCENT");share.put("max",150);
        ((Map<String,Object>)percent.get("resources")).put("accelerators",List.of(share));invalid.add(percent);
        var core=manual(service);var bad=gpu();bad.put("resourceName","memory");((Map<String,Object>)core.get("resources")).put("accelerators",List.of(bad));invalid.add(core);
        var vram=manual(service);var zero=gpu();zero.put("deviceMemoryBytes",0);((Map<String,Object>)vram.get("resources")).put("accelerators",List.of(zero));invalid.add(vram);
        var arch=manual(service);arch.put("compatibility",Map.of("architectures",List.of("riscv64")));invalid.add(arch);
        var unknown=manual(service);unknown.put("measured",true);invalid.add(unknown);
        var recommendedWithoutEvidence=manual(service);((Map<String,Object>)recommendedWithoutEvidence.get("resources")).put("cpu",cpu(100,250,2000,"RECOMMENDED"));invalid.add(recommendedWithoutEvidence);
        for(var body:invalid) assertThat(save(body,400).path("code").asText()).isEqualTo("INVALID_RESOURCE_PROFILE");
        String nanToken=encode(manual(service)).replace("\"requestMillicores\":250","\"requestMillicores\":NaN");
        assertThat(nanToken).contains("NaN");save(UUID.randomUUID().toString(),nanToken,400);
        String nanString=encode(manual(service)).replace("\"requestMillicores\":250","\"requestMillicores\":\"NaN\"");
        save(UUID.randomUUID().toString(),nanString,400);
        var missingService=manual(service);missingService.put("serviceProfileVersionId",UUID.randomUUID().toString());
        assertThat(save(missingService,404).path("code").asText()).isEqualTo("SERVICE_VERSION_NOT_FOUND");
        assertThat(perform(get("/api/v1/resource-profiles").param("serviceProfileVersionId",service.id().toString()),200).path("items")).isEmpty();
    }

    @Test void measuredAndKrrRecommendedProfileCitesServerEvidenceAndKeepsDeviceAttributionSeparate() throws Exception {
        var service=service();
        var analysis=analyze(analysis(service,true),201);
        assertThat(krr.calls).containsExactly("edgeai/Deployment/detector/model@24");
        assertThat(analysis.path("measurement").path("cpu").path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(analysis.path("measurement").path("cpu").path("unit").asText()).isEqualTo("millicores");
        assertThat(analysis.path("measurement").path("cpu").path("max").asDouble()).isEqualTo(190.0);
        assertThat(analysis.path("measurement").path("memory").path("p95").asDouble()).isEqualTo(4.5e8);
        assertThat(analysis.path("measurement").path("aggregation").asText()).isEqualTo("PER_POD_CONTAINER");
        assertThat(analysis.path("measurement").path("perPod").get(0).path("cpuMaxMillicores").asDouble()).isEqualTo(190.0);
        assertThat(analysis.path("recommendation").path("engine").asText()).isEqualTo("KRR");
        assertThat(analysis.path("recommendation").path("cpu").path("requestMillicores").asLong()).isEqualTo(177);
        assertThat(analysis.path("recommendation").path("cpu").path("limitMillicores").isNull()).isTrue();
        assertThat(analysis.path("recommendation").path("window").path("historyHours").asInt()).isEqualTo(24);
        var device=analysis.path("devices").get(0);
        assertThat(device.path("attribution").asText()).isEqualTo("NODE_DEVICE_UNKNOWN_OWNER");
        assertThat(device.path("perWorkload").asBoolean()).isFalse();
        assertThat(device.path("utilization").path("unit").asText()).isEqualTo("percent");
        assertThat(analysis.path("measurement").has("accelerators")).isFalse();
        assertThat(perform(get("/api/v1/resource-profiles/analyses/"+analysis.path("id").asText()),200)).isEqualTo(analysis);

        var body=manual(service);body.put("analysisId",analysis.path("id").asText());
        var resources=(Map<String,Object>)body.get("resources");
        resources.put("cpu",cpu(100,177,1000,"RECOMMENDED"));
        // A basis is a claim about the confirmed request itself: edited values cannot keep it.
        resources.put("cpu",cpu(100,999,1000,"RECOMMENDED"));resources.put("memory",memory(400000000L,450000000L,1073741824L,"MEASURED"));
        assertThat(save(body,400).path("message").asText()).contains("KRR 추천(177)");
        resources.put("cpu",cpu(100,177,1000,"RECOMMENDED"));resources.put("memory",memory(400000000L,999000000L,1073741824L,"MEASURED"));
        assertThat(save(body,400).path("message").asText()).contains("실측 p95(450000000)");
        resources.put("memory",memory(400000000L,450000000L,1073741824L,"MEASURED"));
        var saved=save(body,201);
        assertThat(saved.path("measurementStatus").asText()).isEqualTo("MEASURED");
        assertThat(saved.path("evidence").path("recommendation").path("cpu").path("requestMillicores").asLong()).isEqualTo(177);
        assertThat(saved.path("evidence").path("measurement").path("memory").path("max").asDouble()).isEqualTo(4.6e8);
        var handoff=perform(get("/api/v1/resource-profiles/"+saved.path("id").asText()+"/handoff"),200);
        assertThat(handoff.path("evidence").path("analysisId").asText()).isEqualTo(analysis.path("id").asText());
        assertThat(handoff.path("evidence").path("recommendation").path("provenance").path("version").asText()).isEqualTo("v1.31.1");
        assertThat(handoff.path("resources").path("cpu").path("basis").asText()).isEqualTo("RECOMMENDED");
        assertThat(handoff.path("resources").path("cpu").path("request").asLong()).isEqualTo(177);
        assertThat(handoff.path("resources").path("memory").path("basis").asText()).isEqualTo("MEASURED");
        assertThat(handoff.path("resources").path("memory").path("request").asLong()).isEqualTo(450000000L);

        var otherService=service();var foreign=manual(otherService);foreign.put("analysisId",analysis.path("id").asText());
        assertThat(save(foreign,400).path("code").asText()).isEqualTo("INVALID_RESOURCE_PROFILE");
    }

    @Test void missingStaleAndUnrecommendedEvidenceCannotBeConfirmedAsMeasuredOrRecommended() throws Exception {
        var service=service();
        metrics.measurement=measured(ObservationStatus.NO_DATA,ObservationStatus.INSUFFICIENT);
        krr.next=KrrRecommendation.failed(KrrRecommendation.Status.NO_DATA,"KRR returned ? (No data)",24,Map.of("tool","krr"));
        var analysis=analyze(analysis(service,true),201);
        assertThat(analysis.path("measurement").path("cpu").path("status").asText()).isEqualTo("NO_DATA");
        assertThat(analysis.path("measurement").path("cpu").path("max").isNull()).isTrue();
        assertThat(analysis.path("measurement").path("memory").path("status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(analysis.path("recommendation").path("status").asText()).isEqualTo("NO_DATA");
        assertThat(analysis.path("recommendation").path("cpu").path("requestMillicores").isNull()).isTrue();
        for(String basis:List.of("MEASURED","RECOMMENDED")) {
            var body=manual(service);body.put("analysisId",analysis.path("id").asText());
            ((Map<String,Object>)body.get("resources")).put("cpu",cpu(100,250,2000,basis));
            assertThat(save(body,400).path("code").asText()).isEqualTo("INVALID_RESOURCE_PROFILE");
        }
        metrics.measurement=measured(ObservationStatus.STALE,ObservationStatus.AVAILABLE);
        var stale=analyze(analysis(service,false),201);
        assertThat(stale.path("measurement").path("cpu").path("status").asText()).isEqualTo("STALE");
        assertThat(stale.path("measurement").path("cpu").path("max").asDouble()).isEqualTo(190.0);
        var staleBody=manual(service);staleBody.put("analysisId",stale.path("id").asText());
        ((Map<String,Object>)staleBody.get("resources")).put("cpu",cpu(100,250,2000,"MEASURED"));
        save(staleBody,400);
        var manualWithAnalysis=manual(service);manualWithAnalysis.put("analysisId",analysis.path("id").asText());
        assertThat(save(manualWithAnalysis,201).path("measurementStatus").asText()).isEqualTo("UNMEASURED");

        metrics.attribution=DeviceObservation.Attribution.NODE_DEVICE_SHARED;
        var noKrr=analyze(analysis(service,false),201);
        assertThat(krr.calls).hasSize(1);
        assertThat(noKrr.path("recommendation").path("status").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(noKrr.path("devices").get(0).path("attribution").asText()).isEqualTo("NODE_DEVICE_SHARED");
        assertThat(noKrr.path("devices").get(0).path("perWorkload").asBoolean()).isFalse();
    }

    @Test void analysisScopeIsLimitedToConfiguredNamespacesAndBoundedWindows() throws Exception {
        var service=service();
        var outside=analysis(service,true);outside.put("target",Map.of("namespace","kube-system","kind","Deployment","name","coredns","container","coredns"));
        assertThat(analyze(outside,400).path("code").asText()).isEqualTo("INVALID_RESOURCE_ANALYSIS");
        var tooLong=analysis(service,true);tooLong.put("window",Map.of("start",NOW.minus(Duration.ofDays(15)).toString(),"end",NOW.toString()));analyze(tooLong,400);
        var reversed=analysis(service,true);reversed.put("window",Map.of("start",NOW.toString(),"end",NOW.minus(Duration.ofHours(1)).toString()));analyze(reversed,400);
        var history=analysis(service,true);history.put("recommendation",Map.of("historyHours",10000));analyze(history,400);
        var query=analysis(service,true);query.put("target",Map.of("namespace","edgeai","kind","Deployment","name","x\"} or vector(1)","container","model"));analyze(query,400);
        var url=analysis(service,true);url.put("prometheusUrl","http://attacker.example");analyze(url,400);
        assertThat(metrics.measured).isEmpty();assertThat(krr.calls).isEmpty();

        var capabilities=perform(get("/api/v1/resource-profiles/capabilities"),200);
        assertThat(capabilities.path("namespaces")).extracting(JsonNode::asText).containsExactly("edgeai","lab");
        var workloads=perform(get("/api/v1/resource-profiles/workloads").param("namespace","edgeai"),200);
        perform(get("/api/v1/resource-profiles/workloads").param("namespace","edgeai").param("start",NOW.minus(Duration.ofDays(7)).toString()).param("end",NOW.toString()),200);
        assertThat(metrics.discovered).last().isEqualTo(Duration.ofDays(7));
        perform(get("/api/v1/resource-profiles/workloads").param("namespace","edgeai").param("start",NOW.minus(Duration.ofDays(20)).toString()).param("end",NOW.toString()),400);
        perform(get("/api/v1/resource-profiles/workloads").param("namespace","edgeai").param("start",NOW.minus(Duration.ofDays(1)).toString()),400);
        assertThat(workloads.path("items").get(0).path("containers")).extracting(JsonNode::asText).containsExactly("model","sidecar");
        perform(get("/api/v1/resource-profiles/workloads").param("namespace","kube-system"),400);
        var pods=perform(get("/api/v1/resource-profiles/pods").param("namespace","edgeai").param("kind","Deployment").param("name","detector")
            .param("container","model").param("start",NOW.minus(Duration.ofHours(1)).toString()).param("end",NOW.toString()),200);
        assertThat(pods.path("items").get(0).path("uid").asText()).isEqualTo("0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b");
    }
}
