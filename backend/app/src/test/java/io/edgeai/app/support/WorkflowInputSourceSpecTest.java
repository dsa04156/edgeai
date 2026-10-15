package io.edgeai.app.support;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowInputSourceSpecTest {
    @Test void explicitWorkflowSourceCanProduceAStreamWithoutAnUpstreamInput() throws Exception {
        var profile = new TreeMap<String,Object>((Map<String,Object>)WorkflowInput.JSON.decode(
            Files.readString(Path.of("../../contracts/profiles/service-stream.example.json"))));
        var stream = new TreeMap<String,Object>((Map<String,Object>)profile.get("stream"));
        stream.put("source", "WORKFLOW_DATA");
        stream.put("command", List.of("edgeai-workflow-input"));
        stream.put("inputs", Map.of());
        stream.put("outputs", Map.of("sample", Map.of("mediaType", "application/json", "maxPayloadBytes", 4096)));
        profile.put("stream", stream);
        var accepted = ServiceExecutionInput.parseSpec(WorkflowInput.JSON.canonical(profile));
        assertTrue(accepted.stream().inputs().isEmpty());
        assertEquals(Set.of("sample"), accepted.stream().outputs().keySet());
    }
}
