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
    @MockitoBean DeviceResourceGateway gateway;
    static final JsonDocuments json=new JsonDocuments();
    static final UUID uid=UUID.randomUUID(),generation=UUID.randomUUID(),nodeUid=UUID.randomUUID();
    static final Path run=Path.of("../../output/sensor-storage").toAbsolutePath().normalize();
    static final Map<?,?> connection=(Map<?,?>)json.decode(read(run.resolve("influx-test/connection.json")));
    static final int brokerPort=19083;
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
        for(int n=0;n<100;n++){try(var socket=new java.net.Socket("127.0.0.1",brokerPort)){return;}catch(Exception e){Thread.sleep(20);}}
        throw new IllegalStateException("Owned test broker unavailable");
    }
    @AfterAll static void stopBroker() throws Exception {if(broker!=null){broker.destroy();if(!broker.waitFor(3,java.util.concurrent.TimeUnit.SECONDS))broker.destroyForcibly();}}
    @BeforeEach void source(){
        var profile=Map.of("protocol","virtual","host","sensor-input.example","properties",List.of(Map.of("name","temperature","dataType","float64","unit","°C")));
        var device=Map.of("metadata",Map.of("name","archive-sensor","namespace","edgeai-devices","uid",uid.toString(),"labels",Map.of("app.kubernetes.io/part-of","edgeai"),"annotations",Map.of("edgeai.io/device-profile",json.canonical(profile),ObservationIdentity.ANNOTATION,generation.toString())),
            "spec",Map.of("nodeName","edge-a","deviceModelRef",Map.of("name","temperature-model"),"properties",List.of(Map.of("name","temperature","visitors",Map.of("configData",Map.of("dataType","float64","unit","°C","sourceGeneration",generation.toString(),"configurationSha256",DeviceProfileCompiler.configuration(profile))),"pushMethod",Map.of("mqtt",Map.of("address","tcp://127.0.0.1:"+brokerPort,"topic","edgeai/devices/archive-sensor/temperature","qos",1))))));
        when(gateway.request(eq("GET"),anyString(),isNull())).thenAnswer(call->{String path=call.getArgument(1);if(path.contains("/devices?"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("items",List.of(device))));if(path.endsWith("/nodes/edge-a"))return new DeviceResourceGateway.Reply(200,json.canonical(Map.of("metadata",Map.of("uid",nodeUid.toString()),"status",Map.of("addresses",List.of(Map.of("type","InternalIP","address","127.0.0.1"))))));return new DeviceResourceGateway.Reply(404,"{}");});
    }
    @Test void originalMqttObservationsAreRetainedAndRepeatedDeliveryDoesNotDuplicateHistory() throws Exception {
        long at=Instant.now().toEpochMilli();String session=UUID.randomUUID().toString();
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
