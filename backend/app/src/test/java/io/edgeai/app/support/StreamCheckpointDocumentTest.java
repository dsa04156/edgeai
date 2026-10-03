package io.edgeai.app.support;

import io.edgeai.domain.stream.*;
import io.edgeai.domain.stream.StreamBrokerGateway.Permission;
import io.edgeai.domain.storage.VerifiedArtifact;
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
    @Test @SuppressWarnings("unchecked") void handoverRewritesOnlyBindingsAndSerialWhilePreservingSealedStateAndPendingFrames()throws Exception {
        String original=json.canonical(example);var summary=verify(original);var q=request(original.getBytes(StandardCharsets.UTF_8));
        UUID task=permissions.getFirst().route().consumerTaskId(),nextAttempt=UUID.randomUUID();
        var source=new StreamCheckpoint(UUID.randomUUID(),permissions.getFirst().route().runId(),task,own,UUID.randomUUID(),3,UUID.randomUUID(),UUID.randomUUID(),
            q,summary.revision(),summary.json(),new VerifiedArtifact("fixture-only",q.content(task,own).objectKey(),"fixture-version",q.sha256(),q.bytes(),StreamCheckpoint.MEDIA_TYPE),Instant.now(),null);
        var current=permissions.stream().map(p->{var g=p.generation();var actor=new RouteGeneration.Actor(nextAttempt,4);
            return new Permission(p.route(),new RouteGeneration(UUID.randomUUID(),g.routeId(),g.generation()+1,
                g.producer().id().equals(own)?actor:g.producer(),g.consumer().id().equals(own)?actor:g.consumer(),g.brokerDigest(),g.policyDigest(),g.requestDigest(),
                g.createdAt(),g.updatedAt(),g.leaseUntil(),g.activatedAt(),null,null,null));}).toList();
        Path target=directory.resolve("new.json");
        var rebound=StreamCheckpointDocument.rebind(directory.resolve("snapshot.json"),source,permissions,current,nextAttempt,target);
        var next=(Map<String,Object>)json.decode(Files.readString(target));
        assertThat(next.get("serial").toString()).isEqualTo("4");assertThat(next.get("stateBase64")).isEqualTo(example.get("stateBase64"));
        assertThat(next.get("revision")).isEqualTo(example.get("revision"));assertThat(next.get("executionSha256")).isEqualTo(example.get("executionSha256"));
        var oldRows=(List<Map<String,Object>>)example.get("routes");var newRows=(List<Map<String,Object>>)next.get("routes");
        for(int i=0;i<oldRows.size();i++){
            var oldRow=new TreeMap<>(oldRows.get(i));var newRow=new TreeMap<>(newRows.get(i));
            var oldFrames=(List<Map<String,Object>>)oldRow.remove("frames");var newFrames=(List<Map<String,Object>>)newRow.remove("frames");
            assertThat(newRow).isEqualTo(oldRow);assertThat(newFrames).hasSize(oldFrames.size());
            for(int n=0;n<oldFrames.size();n++){
                var oldFrame=new TreeMap<>(oldFrames.get(n));var newFrame=new TreeMap<>(newFrames.get(n));
                assertThat(newFrame.remove("generation").toString()).isEqualTo("4");oldFrame.remove("generation");
                assertThat(json.canonical(newFrame.remove("producer"))).isEqualTo(json.canonical(Map.of("kind","TASK_ATTEMPT","attemptId",nextAttempt.toString(),"epoch",4)));
                oldFrame.remove("producer");assertThat(newFrame).isEqualTo(oldFrame);
            }
        }
        assertThat(rebound.request().previousId()).isEqualTo(source.id());assertThat(rebound.summary().revision()).isEqualTo(1);
        assertThat(Files.readString(directory.resolve("snapshot.json"))).isEqualTo(original);
        Files.writeString(directory.resolve("snapshot.json"),original+"\n");
        assertThatThrownBy(()->StreamCheckpointDocument.rebind(directory.resolve("snapshot.json"),source,permissions,current,nextAttempt,directory.resolve("invalid.json")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
