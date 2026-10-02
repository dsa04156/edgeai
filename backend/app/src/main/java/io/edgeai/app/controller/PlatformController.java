package io.edgeai.app.controller;

import io.edgeai.app.dto.PlatformResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformController {
    @GetMapping("/api/v1/platform")
    public PlatformResponse platform() {
        return new PlatformResponse("edgeai", "0.1.0", "M4", List.of("profiles", "devices", "nodes", "workflows", "runs", "tasks", "results"));
    }

}
