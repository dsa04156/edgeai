package io.edgeai.app.controller;

import io.edgeai.app.service.ModelProfilingService;
import io.edgeai.app.support.ModelProfilingDocuments;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Resource measurement runs of a registered AI model and the requirement revisions confirmed from them. */
@ConditionalOnProperty(name="edgeai.ai-models.enabled",havingValue="true")
@RestController
@RequestMapping("/api/v1/ai-models/{modelId}")
public class ModelProfilingController {
    private final ModelProfilingService profiling;
    public ModelProfilingController(ModelProfilingService profiling) { this.profiling=profiling; }

    @GetMapping("/profiling/options")
    public Map<String,Object> options(@PathVariable UUID modelId) { return profiling.options(modelId); }

    @PostMapping(path="/profiling-runs",consumes="application/json")
    public ResponseEntity<Map<String,Object>> start(@PathVariable UUID modelId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=profiling.start(modelId,key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/ai-models/"+modelId+"/profiling-runs/"+result.value().id()))
            .body(profiling.view(result.value()));
    }
    @GetMapping("/profiling-runs")
    public Map<String,Object> runs(@PathVariable UUID modelId,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var items=profiling.runs(modelId,limit,offset);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items.stream().limit(limit).map(profiling::view).toList());
        result.put("nextOffset",items.size()>limit?offset+limit:null);
        return result;
    }
    @GetMapping("/profiling-runs/{runId}")
    public Map<String,Object> run(@PathVariable UUID modelId,@PathVariable UUID runId) { return profiling.view(profiling.run(modelId,runId)); }
    @PostMapping("/profiling-runs/{runId}/cancel")
    public Map<String,Object> cancel(@PathVariable UUID modelId,@PathVariable UUID runId,@RequestBody(required=false) String body) {
        return profiling.view(profiling.cancel(modelId,runId,body));
    }

    @PostMapping(path="/resource-profiles",consumes="application/json")
    public ResponseEntity<Map<String,Object>> confirm(@PathVariable UUID modelId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=profiling.confirm(modelId,key,body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/ai-models/"+modelId+"/resource-profiles/"+result.value().id()))
            .body(ModelProfilingDocuments.profile(result.value()));
    }
    @GetMapping("/resource-profiles")
    public Map<String,Object> profiles(@PathVariable UUID modelId) {
        return Map.of("items",profiling.profiles(modelId).stream().map(ModelProfilingDocuments::profile).toList());
    }
    @GetMapping("/resource-profiles/{profileId}")
    public Map<String,Object> profile(@PathVariable UUID modelId,@PathVariable UUID profileId) {
        return ModelProfilingDocuments.profile(profiling.profile(modelId,profileId));
    }
}
