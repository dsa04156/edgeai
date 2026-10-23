package io.edgeai.app.controller;

import io.edgeai.app.dto.HawkeyeSafetyRunResponse;
import io.edgeai.app.dto.HawkeyeSafetyScenarioResponse;
import io.edgeai.app.dto.ResourcePageResponse;
import io.edgeai.app.service.HawkeyeSafetyService;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scenarios/hawkeye-safety")
public class HawkeyeSafetyController {
    private final HawkeyeSafetyService service;
    public HawkeyeSafetyController(HawkeyeSafetyService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<HawkeyeSafetyScenarioResponse> capability() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.capability());
    }
    @GetMapping("/samples/{sampleId}/image")
    public ResponseEntity<byte[]> sample(@PathVariable String sampleId) {
        // Bytes are pinned by SHA-256, so a browser may keep them.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .contentType(MediaType.IMAGE_JPEG).body(service.sampleImage(sampleId));
    }
    @PostMapping(value = "/runs", consumes = "application/json")
    public ResponseEntity<HawkeyeSafetyRunResponse> start(@RequestHeader("Idempotency-Key") String key, @RequestBody String body) {
        var result = service.start(key, body);
        return ResponseEntity.status(result.created() ? 202 : 200).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/v1/scenarios/hawkeye-safety/runs/" + result.value().id())).body(result.value());
    }
    @GetMapping("/runs")
    public ResponseEntity<ResourcePageResponse<HawkeyeSafetyRunResponse>> list(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var values = service.list(limit, offset);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResourcePageResponse<>(
            values.stream().limit(limit).map(HawkeyeSafetyService::response).toList(), values.size() > limit ? offset + limit : null));
    }
    @GetMapping("/runs/{runId}")
    public ResponseEntity<HawkeyeSafetyRunResponse> detail(@PathVariable UUID runId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(runId));
    }
}
