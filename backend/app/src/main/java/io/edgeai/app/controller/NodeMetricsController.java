package io.edgeai.app.controller;

import io.edgeai.app.service.NodeMetricsService;
import io.edgeai.app.dto.NodeMetricsResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class NodeMetricsController {
    private final NodeMetricsService service;
    public NodeMetricsController(NodeMetricsService service) { this.service = service; }
    @GetMapping("/api/v1/node-metrics")
    public ResponseEntity<NodeMetricsResponse> metrics() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NodeMetricsResponse.from(service.snapshot()));
    }
}
