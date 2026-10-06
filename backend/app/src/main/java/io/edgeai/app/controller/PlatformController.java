package io.edgeai.app.controller;

import io.edgeai.app.dto.PlatformResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformController {
    private final boolean workflowsEnabled;

    public PlatformController(@org.springframework.beans.factory.annotation.Value("${edgeai.workflow.enabled:false}") boolean workflowsEnabled) {
        this.workflowsEnabled = workflowsEnabled;
    }
    @GetMapping("/api/v1/platform")
    public PlatformResponse platform() {
        return new PlatformResponse("edgeai", "0.1.0", "M4", workflowsEnabled
            ? List.of("profiles", "devices", "nodes", "workflows", "runs", "tasks", "results", "virtual-devices")
            : List.of("profiles", "devices", "nodes", "tasks", "results", "virtual-devices"));
    }

}
