package io.edgeai.app;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformController {
    @GetMapping("/api/v1/platform")
    public PlatformInfo platform() {
        return new PlatformInfo("edgeai", "0.1.0", "M1", List.of("profiles"));
    }

    public record PlatformInfo(String name, String version, String milestone,
                               List<String> capabilities) {}
}
