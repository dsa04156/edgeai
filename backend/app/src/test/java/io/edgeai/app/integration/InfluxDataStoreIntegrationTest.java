package io.edgeai.app.integration;

import io.edgeai.app.support.*;
import io.edgeai.domain.device.DeviceResourceGateway;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"edgeai.data-store.raw.enabled=true","edgeai.data-store.raw.interval-ms=100"})
@AutoConfigureMockMvc
class InfluxDataStoreIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired io.edgeai.app.service.SensorArchiveCollector collector;
    @Autowired io.edgeai.domain.repository.SensorTelemetryRepository archive;
    @MockitoBean DeviceResourceGateway gateway;
    static final JsonDocuments json=new JsonDocuments();
    final UUID uid=UUID.randomUUID();
    static final UUID generation=UUID.randomUUID(),nodeUid=UUID.randomUUID();
    static final Path run=Path.of("../../output/sensor-storage").toAbsolutePath().normalize();
    static final Map<?,?> connection=(Map<?,?>)json.decode(read(run.resolve("influx-test/connection.json")));
    static final int brokerPort=freePort();
    static int freePort(){try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static final class OwnedPublisher extends MqttClient implements AutoCloseable {
        OwnedPublisher(String address,String id)throws org.eclipse.paho.mqttv5.common.MqttException{super(address,id);}
        @Override public void close()throws org.eclipse.paho.mqttv5.common.MqttException{if(isConnected())disconnect();super.close();}
    }
    static Process broker;
    static String read(Path path){try{return Files.readString(path);}catch(Exception e){throw new IllegalStateException("Real owned InfluxDB must be running; see test-data-store.sh",e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("edgeai.data-store.influx.url",()->connection.get("url"));r.add("edgeai.data-store.influx.org",()->connection.get("org"));r.add("edgeai.data-store.influx.token-file",()->connection.get("tokenFile"));
    }
    @BeforeAll static void startBroker() throws Exception {
        Files.createDirectories(run);Path config=run.resolve("mqtt-test.conf");Files.writeString(config,"listener "+brokerPort+" 127.0.0.1\nallow_anonymous true\npersistence false\n");
        String binary=System.getenv().getOrDefault("MAPPER_TEST_MOSQUITTO",Files.isExecutable(Path.of("../../.tools/mosquitto/usr/sbin/mosquitto"))?"../../.tools/mosquitto/usr/sbin/mosquitto":"mosquitto");
        var builder=new ProcessBuilder(binary,"-c",config.toString()).redirectErrorStream(true).redirectOutput(run.resolve("mqtt-test.log").toFile());
        builder.environment().put("LD_LIBRARY_PATH",Path.of("../../.tools/mosquitto/usr/lib/x86_64-linux-gnu").toAbsolutePath()+":"+System.getenv().getOrDefault("LD_LIBRARY_PATH",""));broker=builder.start();
        for(int n=0;n<100;n++){if(!broker.isAlive())throw new IllegalStateException("Owned test broker exited");try(var socket=new java.net.Socket("127.0.0.1",brokerPort)){return;}catch(Exception e){Thread.sleep(20);}}
        throw new IllegalStateException("Owned test broker unavailable");
    }
    @AfterAll static void stopBroker() throws Exception {if(broker!=null){broker.destroy();if(!broker.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))broker.destroyForcibly();}}
    @BeforeEach void source(){
        var profile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        var device=Map.of("metadata",Map.of("name","archive-sensor","namespace","edgeai-devices","uid",uid.toString(),"labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",json.canonical(profile),ObservationIdentity.ANNOTATION,generation.toString())),
            "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature","visitors",Map.of("configData",Map.of("dataType","float64","unit","°C","sourceGeneration",generation.toString(),"configurationSha256",DeviceProfileCompiler.configuration(profile))),"pushMethod",Map.of("mqtt",Map.of("address","tcp://127.0.0.1:"+brokerPort,"topic","edgeai/devices/archive-sensor/temperature","qos",1))))));
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{String path=call.getArgument(1);if(path.contains("/devices?"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("items",List.of(device))));if(path.endsWith("/nodes/edge-a"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("metadata",Map.of("uid",nodeUid.toString()),"status",Map.of("addresses",List.of(Map.of("type","InternalIP","address","127.0.0.1"))))));return new DeviceResourceGateway.Reply(404,"{}");});
    }
    String qualified(long at,double value,int sequence) {
        var profile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        return json.canonical(Map.ofEntries(Map.entry("apiVersion","edgeai.observation/v1"),Map.entry("namespace","edgeai-devices"),Map.entry("deviceName","archive-sensor"),Map.entry("property","temperature"),Map.entry("sourceGeneration",generation.toString()),Map.entry("configurationSha256",DeviceProfileCompiler.configuration(profile)),Map.entry("observedAt",at),Map.entry("receivedAt",at),Map.entry("timeBasis","SOURCE"),Map.entry("sessionId",Long.toString(Instant.now().toEpochMilli(),36)),Map.entry("sequence",sequence),Map.entry("type","float64"),Map.entry("unit","°C"),Map.entry("value",value)));
    }
    void publish(MqttClient publisher,String topic,String payload) throws Exception {var message=new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));message.setQos(1);publisher.publish(topic,message);}
    String history(){return "/api/v1/data-store/sensors/"+uid+"/temperature/history";}
    void awaitRows(int count) throws Exception {for(int n=0;n<80;n++){try{mvc.perform(get(history())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(count));return;}catch(AssertionError e){if(n==79)throw e;Thread.sleep(100);}}}
    @Test void sensorChartReturnsEachOriginalTimestampAndValueWithoutAveraging() throws Exception {
        long at=Instant.now().minusSeconds(30).toEpochMilli();
        try(var publisher=new OwnedPublisher("tcp://127.0.0.1:"+brokerPort,"chart-originals-"+UUID.randomUUID())) {
            publisher.connect();Thread.sleep(700);
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",qualified(at,12.125,1));
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",qualified(at+1,99.875,2));
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",qualified(at+2,0.03125,3));
            awaitRows(3);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart")
                .param("from",Instant.ofEpochMilli(at-3600000).toString()).param("to",Instant.ofEpochMilli(at+1000).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayMode").value("ORIGINALS"))
                .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.aggregation").doesNotExist())
                .andExpect(jsonPath("$.items[0].observedAt").value(Instant.ofEpochMilli(at).toString())).andExpect(jsonPath("$.items[0].value").value(12.125))
                .andExpect(jsonPath("$.items[1].observedAt").value(Instant.ofEpochMilli(at+1).toString())).andExpect(jsonPath("$.items[1].value").value(99.875))
                .andExpect(jsonPath("$.items[2].observedAt").value(Instant.ofEpochMilli(at+2).toString())).andExpect(jsonPath("$.items[2].value").value(0.03125));
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart")
                .param("from",Instant.ofEpochMilli(at).plusNanos(1).toString()).param("to",Instant.ofEpochMilli(at+1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        }
    }
    @Test void booleanChartHidesReconciledLegacyWithoutChangingFalseOrTrue() throws Exception {
        Instant at=Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        var base=new TreeMap<String,Object>(Map.of("deviceUid",uid.toString(),"deviceId","archive-sensor","namespace","edgeai-devices","property","temperature","unit","","type","bool","configurationSha256","sha256:boolean-test"));
        for(int index=0;index<2;index++) {
            Instant time=at.plusMillis(index);var legacy=new TreeMap<String,Object>(base);
            legacy.putAll(Map.of("observedAt",time.toString(),"quality","UNVERIFIED","value",index!=0));
            archive.append(new io.edgeai.domain.repository.SensorTelemetryRepository.Observation(uid,"temperature",time,json.canonical(legacy)));
            var qualified=new TreeMap<String,Object>(legacy);
            qualified.putAll(Map.of("quality","QUALIFIED","sourceGeneration",generation.toString(),"sessionId","abc","sequence",index+1));
            archive.append(new io.edgeai.domain.repository.SensorTelemetryRepository.Observation(uid,"temperature",time,json.canonical(qualified)));
            // A legacy retry must not resurrect the reconciled point.
            archive.append(new io.edgeai.domain.repository.SensorTelemetryRepository.Observation(uid,"temperature",time,json.canonical(legacy)));
        }
        mvc.perform(get(history())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].value").value(false)).andExpect(jsonPath("$.items[1].value").value(true));
        mvc.perform(get("/api/v1/data-store")).andExpect(status().isOk());
    }
    @Test void slowArchiveWritesDoNotSerializeIndependentMeasurements() throws Exception {
        var complete=new java.util.concurrent.CountDownLatch(40);
        var received=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var topics=Set.of("archive-load/a","archive-load/b","archive-load/c","archive-load/d");
        try(var subscriber=new io.edgeai.adapters.metrics.MqttObservationSubscriber("archive-load-"+UUID.randomUUID().toString().substring(0,8));
            var publisher=new OwnedPublisher("tcp://127.0.0.1:"+brokerPort,"archive-load-publisher-"+UUID.randomUUID())) {
            subscriber.configure(Map.of("tcp://127.0.0.1:"+brokerPort,topics),delivery->{
                Thread.sleep(150);
                if(received.add(new String(delivery.payload(),java.nio.charset.StandardCharsets.UTF_8)))complete.countDown();
            });
            for(int n=0;n<100&&!subscriber.connected();n++)Thread.sleep(20);
            org.junit.jupiter.api.Assertions.assertTrue(subscriber.connected());
            publisher.connect();
            for(int n=0;n<40;n++)publish(publisher,"archive-load/"+(char)('a'+n%4),Integer.toString(n));
            org.junit.jupiter.api.Assertions.assertTrue(complete.await(3,java.util.concurrent.TimeUnit.SECONDS),"All 40 independent measurements must complete bounded slow archive writes");
            org.junit.jupiter.api.Assertions.assertEquals(40,received.size());
        }
    }
    @Test void qualifiedMetadataStillArchivesLegacyOriginalsAndReconcilesDualPublication() throws Exception {
        long at=Instant.now().toEpochMilli();String legacy=json.canonical(Map.of("DeviceName","archive-sensor","PropertyName","temperature","Namespace","edgeai-devices","Value","23.5","Type","float64","TimeStamp",Long.toString(at)));
        try(var publisher=new OwnedPublisher("tcp://127.0.0.1:"+brokerPort,"legacy-archive-"+UUID.randomUUID())) {
            publisher.connect();Thread.sleep(700);
            publish(publisher,"edgeai/devices/archive-sensor/temperature",legacy);awaitRows(1);
            mvc.perform(get(history())).andExpect(jsonPath("$.items[0].quality").value("UNVERIFIED"));
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",qualified(at,23.5,1));Thread.sleep(400);awaitRows(1);
            mvc.perform(get(history())).andExpect(jsonPath("$.items[0].quality").value("QUALIFIED"));
            publish(publisher,"edgeai/devices/archive-sensor/temperature",legacy);Thread.sleep(400);awaitRows(1);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalObservations").value(1));
            // A distinct qualified sequence at the same timestamp/value remains a distinct observation.
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",qualified(at,23.5,2));awaitRows(2);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
            // Qualified publishing may stop while the original route continues; do not suppress that original.
            publish(publisher,"edgeai/devices/archive-sensor/temperature",legacy.replace(Long.toString(at),Long.toString(at+1)));awaitRows(3);
            // A genuinely different original at a previously reconciled timestamp remains visible.
            publish(publisher,"edgeai/devices/archive-sensor/temperature",legacy.replace("23.5","24.5"));awaitRows(4);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/chart"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
            publisher.disconnect();
        }
    }
    @Test void influxeOutageWithholdsRawCompletionAndRecoversTheOriginalOnce() throws Exception {
        try(var publisher=new OwnedPublisher("tcp://127.0.0.1:"+brokerPort,"outage-archive-"+UUID.randomUUID())) {
            publisher.connect();Thread.sleep(700);long at=Instant.now().toEpochMilli();String payload=qualified(at,34.5,1);
            ContinuousRegistrationIntegrationTest.influx("stop");
            long completed=((Number)collector.state().get("storedDeliveries")).longValue();
            try {
                publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",payload);
                mvc.perform(get(history())).andExpect(status().isServiceUnavailable());
                for(int n=0;n<40;n++){if(mvc.perform(get("/api/v1/data-store")).andReturn().getResponse().getStatus()==503)break;Thread.sleep(100);}
                Thread.sleep(700);
                org.junit.jupiter.api.Assertions.assertEquals("STORE_UNAVAILABLE",collector.state().get("status"));
                org.junit.jupiter.api.Assertions.assertEquals(completed,((Number)collector.state().get("storedDeliveries")).longValue());
            }finally{ContinuousRegistrationIntegrationTest.influx("start");}
            awaitRows(1);publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",payload);Thread.sleep(300);awaitRows(1);
            mvc.perform(get(history())).andExpect(jsonPath("$.items[0].rawPayload").value(payload));publisher.disconnect();
        }
    }
    @Test void invalidTimeBasisAndFractionalSourceTimestampAreRejected() throws Exception {
        try(var publisher=new OwnedPublisher("tcp://127.0.0.1:"+brokerPort,"invalid-archive-"+UUID.randomUUID())) {
            publisher.connect();Thread.sleep(700);long at=Instant.now().toEpochMilli();String payload=qualified(at,34.5,1);
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",payload.replace("\"timeBasis\":\"SOURCE\"","\"timeBasis\":3"));
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",payload.replace("\"observedAt\":"+at,"\"observedAt\":"+at+".5"));
            var invalidSession=new TreeMap<String,Object>((Map<String,Object>)json.decode(payload));invalidSession.put("sessionId","01");
            publish(publisher,"edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",json.canonical(invalidSession));
            Thread.sleep(400);awaitRows(0);publisher.disconnect();
        }
        mvc.perform(get(history()).param("from","invalid-date")).andExpect(status().isBadRequest());
    }
    @Test void originalMqttObservationsAreRetainedAndRepeatedDeliveryDoesNotDuplicateHistory() throws Exception {
        long at=Instant.now().toEpochMilli();String session=Long.toString(Instant.now().toEpochMilli(),36);
        var profile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        String original=json.canonical(Map.ofEntries(Map.entry("apiVersion","edgeai.observation/v1"),Map.entry("namespace","edgeai-devices"),Map.entry("deviceName","archive-sensor"),Map.entry("property","temperature"),Map.entry("sourceGeneration",generation.toString()),Map.entry("configurationSha256",DeviceProfileCompiler.configuration(profile)),Map.entry("observedAt",at),Map.entry("receivedAt",at),Map.entry("timeBasis","SOURCE"),Map.entry("sessionId",session),Map.entry("sequence",1),Map.entry("type","float64"),Map.entry("unit","°C"),Map.entry("value",23.5)));
        var publisher=new MqttClient("tcp://127.0.0.1:"+brokerPort,"archive-test-publisher-"+UUID.randomUUID());
        try {
            publisher.connect();Thread.sleep(700);
            var message=new MqttMessage(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));message.setQos(1);
            publisher.publish("edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",message);
            for(int n=0;n<40;n++){
                try{mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));break;}catch(AssertionError e){if(n==39)throw e;Thread.sleep(100);}
            }
            publisher.publish("edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",message);Thread.sleep(300);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].value").value(23.5)).andExpect(jsonPath("$.items[0].rawPayload").value(original)).andExpect(jsonPath("$.items[0].sourceGeneration").value(generation.toString()));
            var separate = new MqttMessage(original.replace("\"sequence\":1", "\"sequence\":2").replace("23.5", "24.5").getBytes(java.nio.charset.StandardCharsets.UTF_8));separate.setQos(1);
            publisher.publish("edgeai/observations/v1/edgeai-devices/archive-sensor/temperature",separate);Thread.sleep(300);
            mvc.perform(get("/api/v1/data-store/sensors/"+uid+"/temperature/history")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[1].value").value(24.5));
            publisher.disconnect();
        } finally {if(publisher.isConnected())publisher.disconnect();publisher.close();}
    }
}
