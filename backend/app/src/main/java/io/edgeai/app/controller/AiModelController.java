package io.edgeai.app.controller;

import io.edgeai.app.service.AiModelService;
import io.edgeai.app.service.ModelProfilingService;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** AI model catalog: immutable execution configurations (registry image digest + run conditions) of model versions, with their measured availability. */
@ConditionalOnProperty(name="edgeai.ai-models.enabled",havingValue="true")
@RestController
@RequestMapping("/api/v1/ai-models")
public class AiModelController {
    private final AiModelService models;
    private final ModelProfilingService profiling;
    public AiModelController(AiModelService models,ModelProfilingService profiling) { this.models=models;this.profiling=profiling; }

    @GetMapping("/registry")
    public Map<String,Object> registry() { return models.registryStatus(); }
    @GetMapping("/registry/repositories")
    public Map<String,Object> repositories(@RequestParam(required=false) String last,@RequestParam(defaultValue="50") int limit) {
        return models.repositories(last,limit);
    }
    @GetMapping("/registry/tags")
    public Map<String,Object> tags(@RequestParam String repository,@RequestParam(required=false) String last,@RequestParam(defaultValue="50") int limit) {
        return models.tags(repository,last,limit);
    }
    @GetMapping("/registry/manifest")
    public Map<String,Object> manifest(@RequestParam String repository,@RequestParam String reference) { return models.manifest(repository,reference); }

    @PostMapping(consumes="application/json")
    public ResponseEntity<Map<String,Object>> register(@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=models.register(key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/ai-models/"+result.value().id()))
            .body(profiling.entry(result.value()));
    }
    @GetMapping
    public Map<String,Object> list(@RequestParam(required=false) String q,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var items=models.list(q,limit,offset);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items.stream().limit(limit).map(profiling::entry).toList());
        result.put("nextOffset",items.size()>limit?offset+limit:null);
        return result;
    }
    @GetMapping("/{modelId}")
    public Map<String,Object> get(@PathVariable UUID modelId) { return profiling.entry(models.get(modelId)); }
}
