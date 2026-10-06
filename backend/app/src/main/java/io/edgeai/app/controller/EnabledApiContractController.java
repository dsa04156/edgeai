package io.edgeai.app.controller;

import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Serve the reviewed contract with deferred workflow endpoints omitted. */
@RestController
@ConditionalOnProperty(name = "edgeai.workflow.enabled", havingValue = "false", matchIfMissing = true)
public class EnabledApiContractController {
    private final String contract;

    public EnabledApiContractController() throws IOException {
        var yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (var input = new ClassPathResource("static/openapi.yaml").getInputStream()) {
            Map<String, Object> document = yaml.load(input);
            var paths = (Map<?, ?>) document.get("paths");
            paths.keySet().removeIf(path -> path.toString().startsWith("/api/v1/workflows")
                || path.toString().startsWith("/api/v1/workflow-runs"));
            @SuppressWarnings("unchecked")
            var info = (Map<String, Object>) document.get("info");
            info.put("description", "Profile, 장치·노드, 가상 장치와 작업·결과 관리 API입니다. 워크플로 등록·실행 API는 기관 간 통합 전까지 제공하지 않습니다.");
            contract = yaml.dump(document);
        }
    }

    @GetMapping(value = "/openapi.yaml", produces = "application/yaml")
    public String contract() {
        return contract;
    }
}
