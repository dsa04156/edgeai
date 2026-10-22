package io.edgeai.app.controller;

import io.edgeai.app.dto.ResourcePageResponse;
import io.edgeai.app.dto.ScenarioCapabilityResponse;
import io.edgeai.app.dto.ScenarioImageResultResponse;
import io.edgeai.app.dto.ScenarioRunResponse;
import io.edgeai.app.service.ImageComparisonService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scenarios/image-comparison")
public class ScenarioController {
    private final ImageComparisonService service;
    public ScenarioController(ImageComparisonService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ScenarioCapabilityResponse> capability() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.capability());
    }
    @PostMapping(value = "/runs", consumes = "application/json")
    public ResponseEntity<ScenarioRunResponse> start(@RequestHeader("Idempotency-Key") String key, @RequestBody String body) {
        var result = service.start(key, body);
        return ResponseEntity.status(result.created() ? 202 : 200).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/v1/scenarios/image-comparison/runs/" + result.value().id())).body(result.value());
    }
    @GetMapping("/runs")
    public ResponseEntity<ResourcePageResponse<ScenarioRunResponse>> list(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var values = service.list(limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResourcePageResponse<>(
            values.stream().limit(limit).map(ImageComparisonService::summary).toList(), values.size() > limit ? offset + limit : null));
    }
    @GetMapping("/runs/{runId}")
    public ResponseEntity<ScenarioRunResponse> detail(@PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(runId));
    }
    @GetMapping("/runs/{runId}/results")
    public ResponseEntity<ResourcePageResponse<ScenarioImageResultResponse>> results(@PathVariable UUID runId,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        var page = service.results(runId, limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResourcePageResponse<>(page.items(), page.nextOffset()));
    }
    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<ScenarioRunResponse> stop(@PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.stop(runId));
    }
}
