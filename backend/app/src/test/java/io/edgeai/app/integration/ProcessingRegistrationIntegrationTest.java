package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.support.JsonDocuments;
import io.edgeai.domain.device.DeviceResourceGateway;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"edgeai.vd.processing-image=registry.test/runner@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "edgeai.vd.processing-architectures=amd64"})
@AutoConfigureMockMvc
class ProcessingRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VirtualDeviceService vds;
    @Autowired ProfileService profiles;
    @Autowired ManagedDeviceService sensors;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired io.edgeai.domain.repository.VirtualDeviceRepository registrations;
    @MockitoBean DeviceResourceGateway gateway;
    final JsonDocuments json=new JsonDocuments();
    final UUID uid=UUID.randomUUID();
    @BeforeEach void sensor() {
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{
            String path=call.getArgument(1);
            if(path.endsWith("/devices/selection-sensor"))return reply(Map.of("metadata",Map.of("name","selection-sensor","uid",uid.toString(),"resourceVersion","1",
                "labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",json.canonical(Map.of("properties",List.of(Map.of("name","temperature","dataType","float64")))))),
                "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature")))));
            if(path.contains("/deployments/"))return reply(Map.of("status",Map.of("readyReplicas",1)));
            return new DeviceResourceGateway.Reply(404,"{}");
        });
    }
    DeviceResourceGateway.Reply reply(Object body){return new DeviceResourceGateway.Reply(200,json.canonical(body));}
    Map<String,Object> request(String key,String property,Object steps) {
        return Map.of("key",key,"displayName","온도 처리","deviceId","selection-sensor","maxAgeSeconds",120,"placement",Map.of("mode","AUTO"),
            "processing",Map.of("apiVersion","edgeai.processing/v1","sampleCount",3,"outputPort","output","pipelines",List.of(Map.of("name","signal","input",Map.of("sourceKey","input","property",property),"steps",steps))));
    }
    @Test void sensorAndProcessingSelectionCreatesReusableProfilesAndReplaysWithoutDuplicatingBindings() throws Exception {
        mvc.perform(get("/api/v1/virtual-devices/processing-catalog")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        var body=json.canonical(request("selection-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","movingAverage","window",3))));
        var response=mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse();
        var created=(Map<?,?>)json.decode(response.getContentAsString());UUID id=UUID.fromString((String)created.get("id"));
        assertThat(response.getHeader("Location")).endsWith(id.toString());
        assertThat(vds.detail(id).activeSources()).singleElement().satisfies(source->{assertThat(source.sourceId()).isEqualTo("selection-sensor");assertThat(source.properties()).containsExactly("temperature");});
        mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        var second=request("selection-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","movingAverage","window",3)));
        var next=mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(json.canonical(second))).andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(((Map<?,?>)json.decode(next.getContentAsString())).get("profileVersionId")).isEqualTo(created.get("profileVersionId"));
        assertThat(jdbc.queryForObject("SELECT spec::text FROM edgeai.profile_version WHERE id=?",String.class,UUID.fromString((String)created.get("serviceProfileVersionId")))).contains("python3", "edgeai_runner.processing", "registry.test/runner@");
        verify(gateway,never()).request(eq("POST"),anyString(),any());
    }
    @Test void invalidMeasurementAndWindowRollBackProfilesAndRegistration() throws Exception {
        int before=jdbc.queryForObject("SELECT count(*) FROM edgeai.profile_version",Integer.class);
        for(var body:List.of(request("bad-"+UUID.randomUUID(),"unknown",List.of(Map.of("op","threshold","operator","gt","value",0))),
            request("bad-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","movingAverage","window",4))))) {
            mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(json.canonical(body))).andExpect(status().is4xxClientError());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.virtual_device WHERE vd_key=?",Integer.class,body.get("key"))).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.profile_version",Integer.class)).isEqualTo(before);
    }
    @Test void writeRequiresCsrf() throws Exception {
        mvc.perform(post("/api/v1/virtual-devices/processing").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void successfulRequestReplaysAfterReleaseSensorLossAndRuntimeReplacement() throws Exception {
        var body=json.canonical(request("replay-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","threshold","operator","gt","value",0))));
        var created=mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse();
        UUID id=UUID.fromString((String)((Map<?,?>)json.decode(created.getContentAsString())).get("id"));vds.release(id);
        when(gateway.request(eq("GET"),anyString(),isNull())).thenReturn(new DeviceResourceGateway.Reply(404,"{}"));
        mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RELEASED"));
        for(String image:List.of("", "registry.test/new-runner@sha256:"+"c".repeat(64))) {
            var replacement=new ProcessingRegistrationService(profiles,vds,sensors,registrations,image,"amd64");
            assertThat(replacement.create(body).value().id()).isEqualTo(id);
        }
        var changed=new HashMap<>((Map<String,Object>)json.decode(body));changed.put("displayName","다른 이름");
        mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(json.canonical(changed))).andExpect(status().isConflict());
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.vd_processing_registration SET request_digest='sha256:'||repeat('a',64) WHERE vd_id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        vds.deleteRegistration(id);assertThat(registrations.processingCreationDigest(id)).isEmpty();
    }
    @Test void concurrentRegistrationAcrossRuntimeReplacementReplaysOneCreation() throws Exception {
        var body=json.canonical(request("concurrent-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","threshold","operator","gt","value",0))));
        var services=List.of(new ProcessingRegistrationService(profiles,vds,sensors,registrations,"registry.test/old@sha256:"+"d".repeat(64),"amd64"),
            new ProcessingRegistrationService(profiles,vds,sensors,registrations,"registry.test/new@sha256:"+"e".repeat(64),"amd64"));
        var response=gateway.request("GET","/devices/selection-sensor",null);
        when(gateway.request(eq("GET"),contains("/devices/selection-sensor"),isNull())).thenAnswer(call->{Thread.sleep(150);return response;});
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=services.stream().map(service->executor.submit(()->{start.await();return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status->service.create(body));})).toList();
            start.countDown();
            var first=futures.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);var second=futures.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.value().id()).isEqualTo(second.value().id());assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(vds.detail(first.value().id()).activeSources()).hasSize(1);
        }
    }
    @Test void nullRegistrationFieldsAreInputErrors() throws Exception {
        var valid=request("null-"+UUID.randomUUID(),"temperature",List.of(Map.of("op","threshold","operator","gt","value",0)));
        for(String field:List.of("key","displayName","deviceId","maxAgeSeconds","processing","placement")) {
            var invalid=new HashMap<>(valid);invalid.put(field,null);
            mvc.perform(post("/api/v1/virtual-devices/processing").with(csrf()).contentType("application/json").content(json.canonical(invalid))).andExpect(status().isBadRequest());
        }
    }
}
