package io.edgeai.app.support;

import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StreamCheckpointDocumentTest {
    @TempDir Path directory;
    private final JsonDocuments json=new JsonDocuments();
    private final UUID own=UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private final List<Permission> permissions=new ArrayList<>();
    private Map<String,Object> example;
    @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception {
        example=(Map<String,Object>)json.decode(Files.readString(Path.of("../../contracts/streams/checkpoint.example.json")));
        var manifest=(Map<?,?>)example.get("manifest");UUID run=UUID.randomUUID(),task=UUID.randomUUID();Instant now=Instant.now();
        for(String direction:List.of("inputs","outputs"))for(var item:(List<?>)manifest.get(direction)){
            var binding=(Map<?,?>)item;var producer=(Map<?,?>)binding.get("producer");boolean device=producer.get("kind").equals("DEVICE_SESSION");
            UUID id=UUID.fromString((String)binding.get("routeId"));long epoch=((Number)producer.get("epoch")).longValue();
            var actor=new RouteGeneration.Actor(UUID.fromString((String)producer.get(device?"sessionId":"attemptId")),epoch);
            var consumer=new RouteGeneration.Actor(direction.equals("inputs")?own:UUID.randomUUID(),3);
            var route=new DataRoute(id,run,device?null:task,device?UUID.fromString((String)producer.get("deviceId")):null,UUID.randomUUID(),
                device?"SYNTHETIC":null,"samples",direction.equals("inputs")?task:UUID.randomUUID(),"input","application/json",262144,now);
            var g=new RouteGeneration(UUID.randomUUID(),id,((Number)binding.get("generation")).longValue(),actor,consumer,
                "sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),now,now,now.plusSeconds(60),now,null,null,null);
            permissions.add(new Permission(route,g));
        }
    }
    private StreamCheckpoint.Request request(byte[] bytes)throws Exception {
        return new StreamCheckpoint.Request(null,((Number)example.get("serial")).longValue(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            bytes.length,(String)example.get("executionSha256"),permissions.stream().map(p->p.generation().id()).toList());
    }
    private StreamCheckpointDocument.Summary verify(String text)throws Exception {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);Path file=directory.resolve("snapshot.json");Files.write(file,bytes);
        return StreamCheckpointDocument.verify(file,request(bytes),permissions,own);
    }
    @Test void pythonExportMatchesJavaStreamingVerifierWithoutRetainingRawStateOrFrames()throws Exception {
        var result=verify(json.canonical(example));assertThat(result.revision()).isEqualTo(1);
        assertThat(result.json()).doesNotContain("\"payloadBase64\":","\"stateBase64\":","\"frames\":");
        assertThat(result.json()).contains("stateSha256","received","committed");
    }
    @Test void noncanonicalBytesWrongSerialAndDuplicatePropertiesAreRejected()throws Exception {
        String original=json.canonical(example);
        assertThatThrownBy(()->verify(original+"\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->verify(original.replace("\"serial\":3","\"serial\":2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->verify(original.replace("\"apiVersion\":","\"apiVersion\":null,\"apiVersion\":"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @SuppressWarnings("unchecked") void wrongBindingPayloadChecksumEndAndCursorGapAreRejected()throws Exception {
        var rows=(List<Map<String,Object>>)example.get("routes");var output=rows.getLast();var frame=((List<Map<String,Object>>)output.get("frames")).getFirst();
        var copy=new TreeMap<>(frame);
        for(var change:List.of(Map.of("generation",99),Map.of("sha256","f".repeat(64)),Map.of("sequence",2),Map.of("kind","END"),Map.of("mediaType","text/plain"))){
            frame.clear();frame.putAll(copy);frame.putAll(change);
            assertThatThrownBy(()->verify(json.canonical(example))).isInstanceOf(IllegalArgumentException.class);
        }
        frame.clear();frame.putAll(copy);output.put("received",2);
        assertThatThrownBy(()->verify(json.canonical(example))).isInstanceOf(IllegalArgumentException.class);
    }
}
