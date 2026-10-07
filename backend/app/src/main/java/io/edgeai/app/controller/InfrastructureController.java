package io.edgeai.app.controller;

import io.edgeai.app.service.InfrastructureService;
import io.edgeai.domain.node.InfrastructureSource.Snapshot;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InfrastructureController {
    private final InfrastructureService service;
    public InfrastructureController(InfrastructureService service) { this.service = service; }
    @GetMapping("/api/v1/infrastructure")
    public ResponseEntity<Snapshot> inventory() { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot()); }
}
