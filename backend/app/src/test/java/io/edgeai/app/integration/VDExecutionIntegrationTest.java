package io.edgeai.app.integration;

import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.vd.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public Spring HTTP/security + real PostgreSQL; readiness/physical removal are explicit lifecycle fixtures. */
@SpringBootTest(properties={"edgeai.runtime.enabled=false","edgeai.vd.enabled=true"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(VDExecutionIntegrationTest.Configuration.class)
class VDExecutionIntegrationTest {
    static class TestClock extends Clock {
        private Instant now=Instant.now(); public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
    }
    @TestConfiguration static class Configuration {
        @Bean RuntimeSettings vdExecutionTestSettings(){return new RuntimeSettings("vd-public-test","edgeai-runner",URI.create("http://fixture.invalid:18080"),120);}
        @Bean @Primary TestClock vdExecutionTestClock(){return new TestClock();}
    }
    @Autowired MockMvc mvc;@Autowired ProfileService profiles;@Autowired VirtualDeviceService vds;
    @Autowired VDExecutionService execution;@Autowired VDLifecycleService lifecycle;
    @Autowired VDRuntimeRepository runtimes;@Autowired VirtualDeviceRepository repository;
    @Autowired RuntimeSettings settings;@Autowired JdbcTemplate jdbc;@Autowired TestClock clock;
    private final JsonDocuments json=new JsonDocuments();
    @BeforeEach void reset(){clock.now=Instant.now().plusSeconds(1);}
    private String encode(Object v){return json.canonical(v);}
    private UUID publish(ProfileIdentity.Kind kind,Object spec){return profiles.publish(kind,encode(Map.of("key","public-vd-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();}
    private VirtualDevice fixture()throws Exception {
        var sp=publish(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        var vp=publish(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","emulation","serviceProfileVersionId",sp.toString(),"sources",Map.of(),
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",5)));
        return vds.create(encode(Map.of("key","public-vd-"+UUID.randomUUID(),"displayName","실행 테스트","profileVersionId",vp.toString(),"sources",List.of(),"placement",Map.of("mode","AUTO")))).value();
    }
    private Map<?,?> command(VirtualDevice vd,String action,String key,String body,int expected)throws Exception {
        String text=mvc.perform(post("/api/v1/virtual-devices/"+vd.id()+"/"+action).with(user("fixture")).with(csrf())
            .header("Idempotency-Key",key).contentType("application/json").content(body)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        assertThat(text).doesNotContain("claimNonce","sessionId","configurationJson","requestDigest","public:");return (Map<?,?>)json.decode(text);
    }
    private Map<?,?> command(VirtualDevice vd,String action,String key,int status)throws Exception {return command(vd,action,key,"{\"revision\":0}",status);}
    private Map<?,?> snapshot(VirtualDevice vd)throws Exception {
        String text=mvc.perform(get("/api/v1/virtual-devices/"+vd.id()+"/execution").with(user("fixture"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(text).doesNotContain("claimNonce","sessionId","configurationJson","requestDigest","public:");return (Map<?,?>)json.decode(text);
    }
    private UUID id(Object value){return UUID.fromString(value.toString());}
    private VDRuntime ready(UUID runtimeId) {
        // Completion here is a Pod-removal/CREATE fixture, not a Kubernetes acceptance claim.
        jdbc.update("UPDATE edgeai.vd_runtime_command SET completed=true WHERE runtime_id=? AND kind='CREATE'",runtimeId);
        UUID pod=UUID.randomUUID();lifecycle.submitted(runtimeId,pod);
        return lifecycle.attest(runtimeId,pod,UUID.randomUUID(),"fixture-node",UUID.randomUUID(),true,20);
    }
    @Test void publicStartReplaceDrainTracksGenericOperationAndRetainsIdentityAndHistory()throws Exception {
        var vd=fixture();assertThat(snapshot(vd).get("current")).isNull();String key=UUID.randomUUID().toString();
        var start=command(vd,"provision",key,202);assertThat(command(vd,"provision",key,200).get("id")).isEqualTo(start.get("id"));
        assertThat(((Map<?,?>)snapshot(vd).get("current")).get("ready")).isEqualTo(false);
        mvc.perform(get("/api/v1/operations/"+start.get("id")).with(user("fixture"))).andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("VD_PROVISION"));
        var first=ready(id(start.get("targetRuntimeId")));assertThat(((Map<?,?>)snapshot(vd).get("current")).get("ready")).isEqualTo(true);
        var replace=command(vd,"replace",UUID.randomUUID().toString(),202);assertThat(replace.get("targetRuntimeId")).isNull();
        assertThat(runtimes.history(vd.id(),10)).hasSize(1);lifecycle.drained(first.id(),first.sessionId());
        assertThat(execution.operation(id(replace.get("id"))).orElseThrow().state()).isEqualTo("RUNNING");
        lifecycle.confirmStopped(first.id());var next=ready(runtimes.operation(id(replace.get("id"))).orElseThrow().targetRuntimeId());
        assertThat(next.generation()).isEqualTo(2);assertThat(next.vdId()).isEqualTo(vd.id());
        var drain=command(vd,"drain",UUID.randomUUID().toString(),202);lifecycle.drained(next.id(),next.sessionId());
        assertThat(execution.operation(id(drain.get("id"))).orElseThrow().state()).isEqualTo("RUNNING");lifecycle.confirmStopped(next.id());
        var status=snapshot(vd);assertThat(status.get("current")).isNull();assertThat((List<?>)status.get("runtimeHistory")).hasSize(2);
        assertThat((List<?>)status.get("bindings")).hasSize(2);assertThat((List<?>)status.get("operations")).hasSize(3);
        assertThat(vds.detail(vd.id()).vd().state()).isEqualTo(VirtualDevice.State.REGISTERED);
        mvc.perform(get("/api/v1/operations/"+drain.get("id")).with(user("fixture"))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }
    @Test void invalidRequestsConflictAndReplayAfterRegistryChangeCannotCreateAnotherGeneration()throws Exception {
        var vd=fixture();String key=UUID.randomUUID().toString();
        for(String body:List.of("{}","{\"revision\":-1}","{\"revision\":0.5}","{\"revision\":9007199254740992}","{\"revision\":0,\"revision\":0}","{\"revision\":0,\"unknown\":true}"))command(vd,"provision",key,body,400);
        command(vd,"provision","registry:0",400);command(vd,"provision",key,"{\"revision\":1}",409);
        command(vd,"provision",key," ".repeat(65537),413);assertThat(runtimes.history(vd.id(),10)).isEmpty();
        var start=command(vd,"provision",key,202);command(vd,"replace",key,409);vds.release(vd.id());
        assertThat(command(vd,"provision",key,200).get("id")).isEqualTo(start.get("id"));
        command(vd,"provision",UUID.randomUUID().toString(),409);assertThat(runtimes.history(vd.id(),10)).hasSize(1);
        mvc.perform(get("/api/v1/virtual-devices/"+UUID.randomUUID()+"/execution").with(user("fixture"))).andExpect(status().isNotFound());
    }
    @Test void concurrentHttpRetransmissionsReturnOneAcceptedOperationAndSevenReplays()throws Exception {
        var vd=fixture();String key=UUID.randomUUID().toString();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++)calls.add(pool.submit(()->{start.await();return mvc.perform(post("/api/v1/virtual-devices/"+vd.id()+"/provision").with(user("fixture")).with(csrf())
                .header("Idempotency-Key",key).contentType("application/json").content("{\"revision\":0}")).andReturn().getResponse().getStatus();}));
            start.countDown();var statuses=new ArrayList<Integer>();for(var call:calls)statuses.add(call.get(30,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200,202).filteredOn(s->s==202).hasSize(1);
        }
        assertThat(runtimes.history(vd.id(),10)).hasSize(1);assertThat(runtimes.operations(vd.id(),10)).hasSize(1);
    }
    @Test void statusExpiresReadinessWithoutWorkerAndDisabledFeaturePreservesReadHistory()throws Exception {
        var vd=fixture();var start=command(vd,"provision",UUID.randomUUID().toString(),202);ready(id(start.get("targetRuntimeId")));
        clock.now=clock.now.plusSeconds(21);var status=snapshot(vd);assertThat(((Map<?,?>)status.get("current")).get("ready")).isEqualTo(false);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("settings",settings);
        var disabled=new VDExecutionService(repository,runtimes,lifecycle,beans.getBeanProvider(RuntimeSettings.class),false,clock);
        assertThat(disabled.status(vd.id()).enabled()).isFalse();assertThat(disabled.status(vd.id()).runtimeHistory()).hasSize(1);
        assertThatThrownBy(()->disabled.request(vd.id(),UUID.randomUUID().toString(),"{\"revision\":0}",VDExecutionService.Action.PROVISION))
            .isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("VD_EXECUTION_DISABLED"));
    }
    @Test void boundedHistoryIndicatesTruncationAndKeysAreScopedToVd()throws Exception {
        var vd=fixture();String key=UUID.randomUUID().toString();command(vd,"drain",key,202);var other=fixture();command(other,"drain",key,202);
        for(int i=0;i<100;i++)execution.request(vd.id(),UUID.randomUUID().toString(),"{\"revision\":0}",VDExecutionService.Action.DRAIN);
        var status=snapshot(vd);assertThat((List<?>)status.get("operations")).hasSize(100);assertThat(status.get("operationsTruncated")).isEqualTo(true);
        assertThat(status.get("pendingOperation")).isNull();assertThat(runtimes.operations(other.id(),101)).hasSize(1);
    }
}
