package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.config.RuntimeSettings;
import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import io.edgeai.domain.profile.ProfileIdentity;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ManagedVDIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ProfileService profiles;
    @Autowired VirtualDeviceService vds;
    @Autowired DeviceService archivedDevices;
    @Autowired VDLifecycleService lifecycle;
    @Autowired ManagedDeviceService managed;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoBean DeviceResourceGateway gateway;
    private final JsonDocuments json=new JsonDocuments();
    private UUID sensorUid=UUID.randomUUID();
    private String model="edgeai-model-temperature";
    private volatile CountDownLatch sensorRead,finishRead;
    private final AtomicInteger sensorReads=new AtomicInteger();
    @BeforeEach void kubeBoundary() {
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{
            String path=call.getArgument(1);
            if(path.matches(".*/devices/sensor-[ab]")) {
                sensorReads.incrementAndGet();
                if(sensorRead!=null){sensorRead.countDown();if(!finishRead.await(10,TimeUnit.SECONDS))throw new AssertionError("Sensor read was not released");}
                String name=path.substring(path.lastIndexOf('/')+1);
                return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("metadata",Map.of("name",name,"uid",sensorUid.toString(),"resourceVersion","1",
                    "labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",json.canonical(Map.of("kind","physical")))),
                    "spec",Map.of("nodeName","edge-node","deviceModelRef",Map.of("name",model),"properties",List.of()))));
            }
            return new DeviceResourceGateway.Reply(404,"{}");
        });
    }
    private UUID profile(ProfileIdentity.Kind kind,Object spec) {
        return profiles.publish(kind,json.canonical(Map.of("key","managed-vd-"+UUID.randomUUID(),"version","1.0.0","spec",spec))).version().id();
    }
    private UUID service() throws Exception {
        return profile(ProfileIdentity.Kind.SERVICE,json.decode(Files.readString(Path.of("../../contracts/profiles/service-execution.example.json"))));
    }
    private UUID template(UUID service) {
        return profile(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v2","type","sensorMirror","serviceProfileVersionId",service.toString(),
            "sources",Map.of("input",Map.of("deviceModel",model,"required",true)),"state",Map.of("mode","STATELESS"),
            "runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60)));
    }
    private Map<String,Object> configuration(String device) {
        return Map.of("displayName","Sensor VD","sources",List.of(Map.of("sourceKey","input","deviceId",device)),"placement",Map.of("mode","AUTO"));
    }
    private UUID create(UUID template) throws Exception {
        var request=new HashMap<>(configuration("sensor-a"));request.put("key","managed-"+UUID.randomUUID());request.put("profileVersionId",template.toString());
        var result=mvc.perform(post("/api/v1/virtual-devices").with(csrf()).contentType("application/json").content(json.canonical(request)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString((String)((Map<?,?>)json.decode(result)).get("id"));
    }
    @Test void sensorCanBeBoundAndReplacedWithoutDuplicateAppRegistration() throws Exception {
        var template=template(service());var id=create(template);String path="/api/v1/virtual-devices/"+id;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.activeSources[0].deviceId").value("sensor-a"))
            .andExpect(jsonPath("$.activeSources[0].deviceUid").value(sensorUid.toString()));
        var update=new HashMap<>(configuration("sensor-b"));update.put("revision",0);
        mvc.perform(patch(path).with(csrf()).contentType("application/json").content(json.canonical(update))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get(path)).andExpect(jsonPath("$.sourceHistory.length()").value(2)).andExpect(jsonPath("$.activeSources[0].deviceId").value("sensor-b"));
        model="edgeai-model-incompatible";update.put("revision",1);
        mvc.perform(patch(path).with(csrf()).contentType("application/json").content(json.canonical(update))).andExpect(status().isConflict());
        mvc.perform(get(path)).andExpect(jsonPath("$.vd.revision").value(1)).andExpect(jsonPath("$.activeSources[0].deviceModel").value("edgeai-model-temperature"));
    }
    @Test void recreatedSensorNameCannotSilentlyChangeTheRuntimeSource() throws Exception {
        var id=create(template(service()));sensorUid=UUID.randomUUID();
        assertThatThrownBy(()->lifecycle.provision(id,0,"test-recreated",new RuntimeSettings("edgeai","default",URI.create("http://localhost:18080"),120,""),false))
            .isInstanceOf(io.edgeai.app.exception.ControlPlaneException.class).hasMessageContaining("UID");
    }
    @Test void explicitlySavingRecreatedSensorRefreshesPinnedUid() throws Exception {
        var id=create(template(service()));var originalUid=sensorUid;sensorUid=UUID.randomUUID();
        var update=new HashMap<>(configuration("sensor-a"));update.put("revision",0);
        String path="/api/v1/virtual-devices/"+id;
        mvc.perform(patch(path).with(csrf()).contentType("application/json").content(json.canonical(update)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(path)).andExpect(jsonPath("$.activeSources[0].deviceUid").value(sensorUid.toString()))
            .andExpect(jsonPath("$.sourceHistory.length()").value(2))
            .andExpect(jsonPath("$.sourceHistory[1].deviceUid").value(originalUid.toString()));
        update.put("revision",1);
        mvc.perform(patch(path).with(csrf()).contentType("application/json").content(json.canonical(update)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
    }
    @Test void sensorDeletionWaitsForPendingVdBindingAndThenRejectsIt() throws Exception {
        var request=new HashMap<>(configuration("sensor-a"));request.put("key","managed-"+UUID.randomUUID());request.put("profileVersionId",template(service()).toString());
        sensorRead=new CountDownLatch(1);finishRead=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var creation=workers.submit(()->vds.create(json.canonical(request)));
            assertThat(sensorRead.await(5,TimeUnit.SECONDS)).isTrue();
            var deletion=workers.submit(()->managed.delete("sensor-a"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)",Boolean.class)) && System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)",Boolean.class)).isTrue();
            assertThat(sensorReads.get()).isEqualTo(1);
            finishRead.countDown();creation.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->deletion.get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(io.edgeai.app.exception.ControlPlaneException.class)
                .hasRootCauseMessage("VD 원본으로 사용 중인 센서입니다. VD 원본을 교체하고 이전 실행의 종료를 확인하세요.");
            verify(gateway,never()).request(eq("DELETE"),anyString(),any());
        } finally {finishRead.countDown();sensorRead=null;}
    }
    @Test void databaseRejectsRetainedSourceWhenTemplateModelChanges() throws Exception {
        var sp=service();var id=create(template(sp));model="edgeai-model-incompatible";var incompatible=template(sp);
        assertThatThrownBy(()->transactions.executeWithoutResult(status->{
            jdbc.update("UPDATE edgeai.virtual_device SET profile_version_id=?,revision=revision+1,updated_at=clock_timestamp() WHERE id=?",incompatible,id);
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("current template");
    }
    @Test void oldVdCanMoveToSensorTemplateWhileKeepingIdentityAndHistory() throws Exception {
        UUID sp=service(),dp=profile(ProfileIdentity.Kind.DEVICE,Map.of("protocol","mqtt"));
        var device=archivedDevices.create(json.canonical(Map.of("key","archived-"+UUID.randomUUID(),"displayName","old","profileVersionId",dp.toString(),"sourceMode","LIVE"))).value();
        UUID vp=profile(ProfileIdentity.Kind.VD,Map.of("apiVersion","edgeai.vd/v1","type","processing","serviceProfileVersionId",sp.toString(),
            "sources",Map.of("input",Map.of("deviceProfileVersionId",dp.toString(),"required",true,"sourceModes",List.of("LIVE"))),
            "state",Map.of("mode","STATELESS"),"runtime",Map.of("maxConcurrentTasks",1,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60)));
        var old=new HashMap<>(configuration(device.id().toString()));old.put("key","archived-vd-"+UUID.randomUUID());old.put("profileVersionId",vp.toString());
        var id=vds.create(json.canonical(old)).value().id();var update=new HashMap<>(configuration("sensor-a"));update.put("revision",0);update.put("profileVersionId",template(sp).toString());
        mvc.perform(patch("/api/v1/virtual-devices/"+id).with(csrf()).contentType("application/json").content(json.canonical(update)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/virtual-devices/"+id)).andExpect(jsonPath("$.activeSources[0].deviceId").value("sensor-a"))
            .andExpect(jsonPath("$.sourceHistory.length()").value(2)).andExpect(jsonPath("$.sourceHistory[1].sourceKind").value("legacy"));
    }
}
