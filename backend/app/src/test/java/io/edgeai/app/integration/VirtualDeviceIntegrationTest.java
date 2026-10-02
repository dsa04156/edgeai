package io.edgeai.app.integration;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.VirtualDeviceRepository;
import io.edgeai.domain.vd.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VirtualDeviceIntegrationTest {
    @Autowired VirtualDeviceService vds;
    @Autowired DeviceService devices;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    private final JsonDocuments documents=new JsonDocuments();
    private final JsonMapper json=new JsonMapper();
    private String encode(Object value) { return json.writeValueAsString(value); }
    private String key() { return "vd-"+UUID.randomUUID(); }
    private UUID publish(ProfileIdentity.Kind kind,Object spec) {
        return profiles.publish(kind,encode(Map.of("key",key(),"version","1.0.0","spec",spec))).version().id();
    }
    private Device device(UUID profile,String mode) {
        return devices.create(encode(Map.of("key",key(),"displayName","원본","profileVersionId",profile.toString(),"sourceMode",mode))).value();
    }
    private record Fixture(UUID deviceProfile,UUID serviceProfile,UUID vdProfile,Device a,Device b,Map<String,Object> spec) {}
    private Fixture fixture() throws Exception {
        UUID dp=publish(ProfileIdentity.Kind.DEVICE,Map.of("protocol","fixture"));
        UUID sp=publish(ProfileIdentity.Kind.SERVICE,documents.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
        var source=Map.of("deviceProfileVersionId",dp.toString(),"required",true,"sourceModes",List.of("SYNTHETIC"));
        var spec=new HashMap<String,Object>(Map.of("apiVersion","edgeai.vd/v1","type","processing","serviceProfileVersionId",sp.toString(),
            "sources",Map.of("input",source,"optional",Map.of("deviceProfileVersionId",dp.toString(),"required",false,"sourceModes",List.of("SYNTHETIC"))),
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60)));
        return new Fixture(dp,sp,publish(ProfileIdentity.Kind.VD,spec),device(dp,"SYNTHETIC"),device(dp,"SYNTHETIC"),spec);
    }
    private List<Map<String,Object>> sources(Device device) { return List.of(Map.of("sourceKey","input","deviceId",device.id().toString())); }
    private Map<String,Object> request(Fixture f) {
        return new HashMap<>(Map.of("key",key(),"displayName","가상 장치","profileVersionId",f.vdProfile().toString(),"sources",sources(f.a()),"placement",Map.of("mode","AUTO")));
    }
    private String updateBody(VirtualDevice vd,List<Map<String,Object>> sources) {
        return encode(Map.of("revision",vd.revision(),"displayName",vd.displayName(),"sources",sources,"placement",Map.of("mode","AUTO")));
    }
    private String perform(MockHttpServletRequestBuilder request,int expected) throws Exception {
        return mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    private void error(Supplier<?> call,String code) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    @Test void httpLifecyclePreservesVdIdentityAndSourceHistoryWithoutClaimingReadiness() throws Exception {
        var f=fixture();String body=encode(request(f));
        var created=json.readTree(perform(post("/api/v1/virtual-devices").contentType("application/json").content(body),201));
        UUID id=UUID.fromString(created.get("id").asText());String path="/api/v1/virtual-devices/"+id;
        assertThat(created.get("state").asText()).isEqualTo("REGISTERED");assertThat(created.has("creationDigest")).isFalse();
        assertThat(created.get("serviceProfileVersionId").asText()).isEqualTo(f.serviceProfile().toString());
        assertThat(json.readTree(perform(post("/api/v1/virtual-devices").contentType("application/json").content(body),200)).get("id").asText()).isEqualTo(id.toString());
        var first=vds.detail(id);var initialBinding=first.activeSources().getFirst();
        assertThat(json.readTree(perform(delete("/api/v1/devices/"+f.a().id()),409)).get("code").asText()).isEqualTo("DEVICE_IN_USE");
        perform(patch(path).contentType("application/json").content(updateBody(first.vd(),sources(f.b()))),200);
        var changed=vds.detail(id);assertThat(changed.vd().id()).isEqualTo(id);assertThat(changed.vd().revision()).isEqualTo(1);
        assertThat(changed.sourceHistory()).hasSize(2);assertThat(changed.activeSources()).singleElement().satisfies(b->{
            assertThat(b.id()).isNotEqualTo(initialBinding.id());assertThat(b.deviceId()).isEqualTo(f.b().id());assertThat(b.openedRevision()).isEqualTo(1);
        });
        assertThat(changed.sourceHistory()).filteredOn(b->b.id().equals(initialBinding.id())).singleElement().satisfies(b->assertThat(b.closedRevision()).isEqualTo(1));
        perform(delete("/api/v1/devices/"+f.a().id()),200);
        perform(patch(path).contentType("application/json").content(updateBody(first.vd(),sources(f.b()))),409);
        perform(get(path),200);perform(get("/api/v1/virtual-devices?limit=1"),200);
        perform(delete(path),200);perform(delete(path),200);
        var released=vds.detail(id);assertThat(released.activeSources()).isEmpty();assertThat(released.sourceHistory()).hasSize(2).allMatch(b->b.closedAt()!=null);
        assertThat(released.vd().state()).isEqualTo(VirtualDevice.State.RELEASED);assertThat(released.vd().revision()).isEqualTo(2);
        perform(delete("/api/v1/devices/"+f.b().id()),200);
        var replay=vds.create(body);assertThat(replay.created()).isFalse();assertThat(replay.value()).isEqualTo(released.vd());
        perform(patch(path).contentType("application/json").content(updateBody(released.vd(),sources(f.b()))),409);
    }
    @Test void concurrentCreationAndSourceRevisionConflictsAreAtomic() throws Exception {
        var f=fixture();String body=encode(request(f));
        var creates=parallel(()->vds.create(body));assertThat(creates.stream().filter(VirtualDeviceRepository.Creation::created).count()).isEqualTo(1);
        assertThat(creates.stream().map(c->c.value().id()).distinct().count()).isEqualTo(1);
        var vd=creates.getFirst().value();assertThat(vds.detail(vd.id()).activeSources()).hasSize(1);
        var updates=parallel(()->{
            try { vds.update(vd.id(),updateBody(vd,sources(f.b())));return "OK"; }
            catch(ControlPlaneException e){return e.code();}
        });
        assertThat(updates).filteredOn("OK"::equals).hasSize(1);assertThat(updates).filteredOn("VD_CONFLICT"::equals).hasSize(7);
        assertThat(vds.detail(vd.id()).sourceHistory()).hasSize(2);
    }
    @Test void unchangedBindingsSurviveOtherSourceChangesAndNoopDoesNotIncrementRevision() throws Exception {
        var f=fixture();var request=request(f);request.put("sources",List.of(sources(f.a()).getFirst(),Map.of("sourceKey","optional","deviceId",f.b().id().toString())));
        var vd=vds.create(encode(request)).value();var initial=vds.detail(vd.id());
        var unchanged=initial.activeSources().stream().filter(b->b.sourceKey().equals("input")).findFirst().orElseThrow();
        var result=vds.update(vd.id(),updateBody(vd,sources(f.a())));assertThat(result.revision()).isEqualTo(1);
        assertThat(vds.detail(vd.id()).activeSources()).containsExactly(unchanged);
        assertThat(vds.update(vd.id(),updateBody(result,sources(f.a())))).isEqualTo(result);
        var different=new HashMap<>(request);different.put("displayName","다른 생성 입력");
        error(()->vds.create(encode(different)),"VD_CONFLICT");
    }
    @Test void incompatibleMissingReleasedAndUnknownSourcesRollbackEntireCreation() throws Exception {
        var f=fixture();
        var candidates=new ArrayList<List<Map<String,Object>>>();candidates.add(List.of());
        candidates.add(List.of(Map.of("sourceKey","unknown","deviceId",f.a().id().toString())));
        candidates.add(sources(device(f.deviceProfile(),"LIVE")));
        candidates.add(sources(device(publish(ProfileIdentity.Kind.DEVICE,Map.of("different",true)),"SYNTHETIC")));
        devices.release(f.b().id());candidates.add(sources(f.b()));
        for(var source:candidates) {
            var request=request(f);request.put("sources",source);
            error(()->vds.create(encode(request)),"VD_SOURCE_INCOMPATIBLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.virtual_device WHERE vd_key=?",Integer.class,request.get("key"))).isZero();
        }
        var missing=request(f);missing.put("sources",List.of(Map.of("sourceKey","input","deviceId",UUID.randomUUID().toString())));
        error(()->vds.create(encode(missing)),"VD_NOT_FOUND");
        var node=request(f);node.put("placement",Map.of("mode","NODE","nodeId",UUID.randomUUID().toString()));
        error(()->vds.create(encode(node)),"VD_NOT_FOUND");
    }
    @Test void profileKindsExecutableServiceAndAllDeclaredSourceProfilesAreValidated() throws Exception {
        var f=fixture();
        for(UUID wrong:List.of(f.deviceProfile(),f.serviceProfile(),publish(ProfileIdentity.Kind.VD,Map.of("type","processing")))) {
            var request=request(f);request.put("profileVersionId",wrong.toString());
            assertThatThrownBy(()->vds.create(encode(request))).isInstanceOf(IllegalArgumentException.class);
        }
        var spec=new HashMap<>(f.spec());spec.put("serviceProfileVersionId",f.deviceProfile().toString());
        var wrongService=request(f);wrongService.put("profileVersionId",publish(ProfileIdentity.Kind.VD,spec).toString());
        assertThatThrownBy(()->vds.create(encode(wrongService))).isInstanceOf(IllegalArgumentException.class);
        spec.put("serviceProfileVersionId",publish(ProfileIdentity.Kind.SERVICE,Map.of("image","not-executable")).toString());
        var invalidService=request(f);invalidService.put("profileVersionId",publish(ProfileIdentity.Kind.VD,spec).toString());
        assertThatThrownBy(()->vds.create(encode(invalidService))).isInstanceOf(IllegalArgumentException.class);
        spec=new HashMap<>(f.spec());spec.put("type","emulation");spec.put("sources",Map.of());
        var emulation=request(f);emulation.put("profileVersionId",publish(ProfileIdentity.Kind.VD,spec).toString());emulation.put("sources",List.of());
        assertThat(vds.detail(vds.create(encode(emulation)).value().id()).activeSources()).isEmpty();
    }
    @Test void directDatabaseWritesCannotDestroyHistoryBypassCompatibilityOrReleaseBoundDevices() throws Exception {
        var f=fixture();var vd=vds.create(encode(request(f))).value();var b=vds.detail(vd.id()).activeSources().getFirst();
        for(String sql:List.of("DELETE FROM edgeai.virtual_device WHERE id=?","UPDATE edgeai.virtual_device SET vd_key='rewritten',revision=revision+1 WHERE id=?",
            "UPDATE edgeai.virtual_device SET revision=revision+2 WHERE id=?","UPDATE edgeai.virtual_device SET state='RELEASED',revision=revision+1 WHERE id=?"))
            assertThatThrownBy(()->jdbc.update(sql,vd.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.device SET state='RELEASED' WHERE id=?",f.a().id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM edgeai.vd_source_binding WHERE id=?",b.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_source_binding SET device_id=? WHERE id=?",f.b().id(),b.id())).isInstanceOf(DataIntegrityViolationException.class);
        for(String slot:List.of("input","undeclared"))assertThatThrownBy(()->jdbc.update("""
            INSERT INTO edgeai.vd_source_binding(id,vd_id,source_key,device_id,device_profile_version_id,source_mode,opened_revision,opened_at)
            VALUES (?,?,?,?,?,'SYNTHETIC',0,clock_timestamp())
            """,UUID.randomUUID(),vd.id(),slot,f.b().id(),f.deviceProfile())).isInstanceOf(DataIntegrityViolationException.class);
        // Even a transaction that advances revision cannot commit without its required source.
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            jdbc.update("UPDATE edgeai.virtual_device SET revision=revision+1 WHERE id=?",vd.id());
            jdbc.update("UPDATE edgeai.vd_source_binding SET closed_revision=1,closed_at=clock_timestamp() WHERE id=?",b.id());return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(vds.detail(vd.id()).activeSources()).containsExactly(b);assertThat(vds.detail(vd.id()).vd().revision()).isZero();
        vds.release(vd.id());
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_source_binding SET closed_at=NULL,closed_revision=NULL WHERE id=?",b.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.virtual_device SET state='REGISTERED',revision=revision+1 WHERE id=?",vd.id())).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void deviceReleaseRacingVdBindingNeverLeavesAnActiveBindingToReleasedDevice() throws Exception {
        var f=fixture();
        for(int i=0;i<8;i++) {
            var source=device(f.deviceProfile(),"SYNTHETIC");var request=request(f);request.put("sources",sources(source));
            try(var executor=Executors.newFixedThreadPool(2)) {
                var start=new CountDownLatch(1);
                var create=executor.submit(()->{start.await();try{return vds.create(encode(request)).value().id();}catch(ControlPlaneException e){assertThat(e.code()).isEqualTo("VD_SOURCE_INCOMPATIBLE");return null;}});
                var release=executor.submit(()->{start.await();try{devices.release(source.id());return true;}catch(ControlPlaneException e){assertThat(e.code()).isEqualTo("DEVICE_IN_USE");return false;}});
                start.countDown();UUID id=create.get(20,TimeUnit.SECONDS);boolean released=release.get(20,TimeUnit.SECONDS);
                assertThat(id==null).isEqualTo(released);
                assertThat(devices.detail(source.id()).device().state()).isEqualTo(released?Device.State.RELEASED:Device.State.ACTIVE);
                if(id!=null)assertThat(vds.detail(id).activeSources()).hasSize(1);
            }
        }
    }
    @Test void boundedHistoryDoesNotHideAnOldStillActiveSource() throws Exception {
        var f=fixture();var request=request(f);request.put("sources",List.of(sources(f.a()).getFirst(),Map.of("sourceKey","optional","deviceId",f.a().id().toString())));
        var vd=vds.create(encode(request)).value();UUID stable=vds.detail(vd.id()).activeSources().getFirst().id();
        for(int i=0;i<101;i++)vd=vds.update(vd.id(),updateBody(vd,List.of(sources(f.a()).getFirst(),Map.of("sourceKey","optional","deviceId",(i%2==0?f.b():f.a()).id().toString()))));
        var detail=vds.detail(vd.id());assertThat(detail.sourceHistoryTruncated()).isTrue();assertThat(detail.sourceHistory()).hasSize(100);
        assertThat(detail.activeSources()).hasSize(2).anyMatch(b->b.id().equals(stable));
        assertThat(detail.sourceHistory()).noneMatch(b->b.id().equals(stable));
    }
    @Test void invalidRequestsHaveDocumentedStatusesAndNoPartialWrites() throws Exception {
        String path="/api/v1/virtual-devices";
        for(String invalid:List.of("{}","[]","{\"key\":\"a\",\"key\":\"b\"}"))perform(post(path).contentType("application/json").content(invalid),400);
        perform(post(path).contentType("application/json").content("{\"large\":\""+"x".repeat(65536)+"\"}"),413);
        perform(get(path+"?limit=101"),400);perform(get(path+"?offset=-1"),400);perform(get(path+"/"+UUID.randomUUID()),404);
    }
    private <T> List<T> parallel(Supplier<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<T>>();
            for(int i=0;i<8;i++)results.add(executor.submit(()->{start.await();return call.get();}));
            start.countDown();var values=new ArrayList<T>();for(var result:results)values.add(result.get(20,TimeUnit.SECONDS));return values;
        }
    }
}
