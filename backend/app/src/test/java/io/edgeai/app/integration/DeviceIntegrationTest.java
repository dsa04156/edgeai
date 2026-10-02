package io.edgeai.app.integration;

import io.edgeai.app.service.*;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.device.*;
import io.edgeai.domain.node.ExecutionNode;
import io.edgeai.domain.profile.ProfileIdentity;
import io.edgeai.domain.repository.DeviceRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceIntegrationTest {
    @Autowired DeviceService devices;
    @Autowired NodeService nodes;
    @Autowired ProfileService profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final JsonMapper json=new JsonMapper();
    private String encode(Object o) { return json.writeValueAsString(o); }
    private String key() { return "device-"+UUID.randomUUID(); }
    private Map<String,Object> createBody(String key) {
        var profile=profiles.publish(ProfileIdentity.Kind.DEVICE,encode(Map.of("key",key,"version","1.0.0","spec",Map.of("protocol","mqtt")))).version();
        return Map.of("key",key,"displayName","시험 센서","profileVersionId",profile.id().toString(),"sourceMode","SYNTHETIC");
    }
    private Device device() { return devices.create(encode(createBody(key()))).value(); }
    private DeviceSession session(Device d) { return devices.openSession(d.id(),encode(Map.of("bootId",UUID.randomUUID()))).value(); }
    private String observation(DeviceSession s,long sequence,String status) {
        return encode(Map.of("sessionId",s.id(),"sequence",sequence,"observedAt",Instant.now().toString(),"status",status,"attributes",Map.of("temperature",23)));
    }
    private String perform(MockHttpServletRequestBuilder request,int expected) throws Exception {
        return mvc.perform(request.with(user("test")).with(csrf())).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }
    @Test void httpLifecycleCoversRegistrationRenameSessionObservationAndRelease() throws Exception {
        var body=createBody(key());String input=encode(body);
        var created=json.readTree(perform(post("/api/v1/devices").contentType("application/json").content(input),201));
        String id=created.get("id").asText(),path="/api/v1/devices/"+id;
        assertThat(created.get("connectionStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(created.get("sourceMode").asText()).isEqualTo("SYNTHETIC");
        assertThat(json.readTree(perform(post("/api/v1/devices").contentType("application/json").content(input),200)).get("id").asText()).isEqualTo(id);
        perform(post("/api/v1/devices").contentType("application/json").content(input.replace("시험 센서","다른 센서")),409);
        perform(get("/api/v1/devices?limit=1"),200);
        String renamed=perform(patch(path).contentType("application/json").content("{\"revision\":0,\"displayName\":\"새 이름\"}"),200);
        assertThat(json.readTree(renamed).get("revision").asLong()).isEqualTo(1);
        perform(patch(path).contentType("application/json").content("{\"revision\":0,\"displayName\":\"경쟁 요청\"}"),409);
        String boot=encode(Map.of("bootId",UUID.randomUUID()));
        var first=json.readTree(perform(post(path+"/sessions").contentType("application/json").content(boot),201));
        assertThat(json.readTree(perform(post(path+"/sessions").contentType("application/json").content(boot),200)).get("id").asText()).isEqualTo(first.get("id").asText());
        String report=encode(Map.of("sessionId",first.get("id").asText(),"sequence",0,"observedAt",Instant.now().toString(),"status","ONLINE","attributes",Map.of("value",23)));
        var seen=json.readTree(perform(post(path+"/observations").contentType("application/json").content(report),201));
        assertThat(json.readTree(perform(post(path+"/observations").contentType("application/json").content(report),200)).get("id").asText()).isEqualTo(seen.get("id").asText());
        assertThat(json.readTree(perform(get(path),200)).path("device").path("connectionStatus").asText()).isEqualTo("ONLINE");
        perform(post(path+"/observations").contentType("application/json").content(report.replace("ONLINE","OFFLINE")),409);
        assertThat(json.readTree(perform(delete(path),200)).get("state").asText()).isEqualTo("RELEASED");
        perform(delete(path),200);
        perform(post(path+"/observations").contentType("application/json").content(report),409);
        perform(post(path+"/sessions").contentType("application/json").content(boot),409);
        assertThat(json.readTree(perform(get(path),200)).path("observations").size()).isEqualTo(1);
    }
    @Test void reconnectionFencesPreviousSessionAndOutOfOrderReportsCannotOverwriteLatest() {
        var d=device();var first=session(d);String original=observation(first,5,"ONLINE");
        devices.observe(d.id(),original);
        assertThatThrownBy(()->devices.observe(d.id(),observation(first,3,"OFFLINE"))).isInstanceOf(ControlPlaneException.class);
        assertThat(devices.detail(d.id()).device().lastStatus()).isEqualTo("ONLINE");
        var second=session(d);
        assertThat(second.epoch()).isEqualTo(2);
        assertThat(devices.detail(d.id()).device().connectionStatus(Instant.now())).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->devices.observe(d.id(),original)).isInstanceOf(ControlPlaneException.class).hasMessageContaining("이전 세션");
        assertThatThrownBy(()->devices.openSession(d.id(),encode(Map.of("bootId",first.bootId())))).isInstanceOf(ControlPlaneException.class);
        devices.observe(d.id(),observation(second,0,"OFFLINE"));
        assertThat(devices.detail(d.id()).device().lastStatus()).isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.device_session WHERE device_id=? AND closed_at IS NULL",Integer.class,d.id())).isEqualTo(1);
    }
    @Test void concurrentRegistrationAndIdenticalReportsCreateExactlyOneRow() throws Exception {
        String body=encode(createBody(key()));var registrations=parallel(()->devices.create(body));
        assertThat(registrations.stream().filter(DeviceRepository.Creation::created).count()).isEqualTo(1);
        assertThat(registrations.stream().map(r->r.value().id()).distinct().count()).isEqualTo(1);
        var d=registrations.getFirst().value();var session=session(d);String report=observation(session,0,"ONLINE");
        var reports=parallel(()->devices.observe(d.id(),report));
        assertThat(reports.stream().filter(DeviceRepository.Creation::created).count()).isEqualTo(1);
        assertThat(devices.detail(d.id()).observations()).hasSize(1);
    }
    @Test void concurrentReconnectionLeavesOneActiveSessionAndRejectsOtherProducers() throws Exception {
        var d=device();var sessions=parallel(()->session(d));
        assertThat(sessions.stream().map(DeviceSession::epoch).distinct().count()).isEqualTo(8);
        var latest=devices.detail(d.id()).sessions().getFirst();
        assertThat(latest.epoch()).isEqualTo(8);
        for (var s:sessions) {
            if (s.id().equals(latest.id())) devices.observe(d.id(),observation(s,0,"ONLINE"));
            else assertThatThrownBy(()->devices.observe(d.id(),observation(s,0,"ONLINE"))).isInstanceOf(ControlPlaneException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.device_session WHERE device_id=? AND closed_at IS NULL",Integer.class,d.id())).isEqualTo(1);
    }
    @Test void actualNodeSnapshotAttachmentHistoryAndStalenessAreEnforced() throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var now=Instant.now();
        nodes.recordSnapshot(List.of(node(a,"fixture-a",now),node(b,"fixture-b",now)),now);
        var d=device();String path="/api/v1/devices/"+d.id()+"/attachments/";
        String one=perform(put(path+a).contentType("application/json").content("{\"port\":\"sensor-0\"}"),200);
        assertThat(perform(put(path+a).contentType("application/json").content("{\"port\":\"sensor-0\"}"),200)).isEqualTo(one);
        perform(put(path+b).contentType("application/json").content("{\"port\":\"sensor-1\"}"),200);
        assertThat(devices.detail(d.id()).attachments()).hasSize(2).filteredOn(x->x.detachedAt()==null).hasSize(1);
        perform(get("/api/v1/nodes?limit=1"),200);perform(get("/api/v1/nodes/"+a),200);
        nodes.recordSnapshot(List.of(node(a,"fixture-a",now)),now);
        assertThat(nodes.find(b).status(now)).isEqualTo("REMOVED");
        perform(put(path+b).contentType("application/json").content("{\"port\":\"sensor-1\"}"),409);
        jdbc.update("UPDATE edgeai.execution_node SET observed_at=clock_timestamp()-interval '61 seconds' WHERE id=?",a);
        perform(put(path+a).contentType("application/json").content("{\"port\":\"sensor-0\"}"),409);
        devices.release(d.id());assertThat(devices.detail(d.id()).attachments()).allMatch(x->x.detachedAt()!=null);
    }
    @Test void rejectsWrongProfileMalformedInputUnknownFieldsOversizeAndUnavailableStorageDetails() throws Exception {
        var body=new HashMap<>(createBody(key()));
        var wrong=profiles.publish(ProfileIdentity.Kind.SERVICE,encode(Map.of("key",key(),"version","1.0.0","spec",Map.of("image","sample")))).version();
        body.put("profileVersionId",wrong.id().toString());
        perform(post("/api/v1/devices").contentType("application/json").content(encode(body)),400);
        for(String invalid:List.of("{}","[]","{\"key\":\"a\",\"key\":\"b\"}",encode(createBody(key())).replace("SYNTHETIC","INVALID")))
            perform(post("/api/v1/devices").contentType("application/json").content(invalid),400);
        perform(post("/api/v1/devices").contentType("application/json").content("{\"data\":\""+"a".repeat(17000)+"\"}"),413);
        perform(get("/api/v1/devices?limit=101"),400);perform(get("/api/v1/nodes?offset=-1"),400);
        perform(get("/api/v1/devices/"+UUID.randomUUID()),404);perform(get("/api/v1/nodes/"+UUID.randomUUID()),404);
        var d=device();var s=session(d);
        for(Object invalid:List.of(-1,1.5,"1",9007199254740992L)) {
            var bad=Map.of("sessionId",s.id(),"sequence",invalid,"observedAt",Instant.now().toString(),"status","ONLINE","attributes",Map.of());
            perform(post("/api/v1/devices/"+d.id()+"/observations").contentType("application/json").content(encode(bad)),400);
        }
        String future=encode(Map.of("sessionId",s.id(),"sequence",0,"observedAt",Instant.now().plusSeconds(60).toString(),"status","ONLINE","attributes",Map.of()));
        perform(post("/api/v1/devices/"+d.id()+"/observations").contentType("application/json").content(future),400);
    }
    @Test void databaseRejectsWrongProfileKindAndParallelActiveRelationships() {
        var d=device();var s=session(d);
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.device_session(id,device_id,boot_id,epoch,opened_at) VALUES (?,?,?,?,clock_timestamp())",UUID.randomUUID(),d.id(),UUID.randomUUID(),99))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var wrong=profiles.publish(ProfileIdentity.Kind.VD,encode(Map.of("key",key(),"version","1.0.0","spec",Map.of("type","sensorMirror")))).version();
        assertThatThrownBy(()->jdbc.update("UPDATE edgeai.device SET profile_version_id=? WHERE id=?",wrong.id(),d.id()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO edgeai.device_observation(id,device_id,session_id,sequence,observed_at,received_at,status,attributes,digest) VALUES (?,?,?,0,now(),now(),'ONLINE','{}',?)",UUID.randomUUID(),device().id(),s.id(),"sha256:"+"0".repeat(64)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private ExecutionNode node(UUID id,String name,Instant now) { return new ExecutionNode(id,name,"amd64","linux","READY","4","8Gi","{\"source\":\"test-fixture\"}",now); }
    private <T> List<T> parallel(Supplier<T> call) throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);var results=new ArrayList<Future<T>>();
            for(int i=0;i<8;i++) results.add(executor.submit(()->{start.await();return call.get();}));
            start.countDown();var values=new ArrayList<T>();for(var r:results)values.add(r.get(20,TimeUnit.SECONDS));return values;
        }
    }
}
