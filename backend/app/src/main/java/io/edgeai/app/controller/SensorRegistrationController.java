package io.edgeai.app.controller;

import io.edgeai.app.service.SensorRegistrationService;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorRegistrationController {
    private final SensorRegistrationService service;
    public SensorRegistrationController(SensorRegistrationService service) { this.service = service; }
    @GetMapping("/registration-options")
    public ResponseEntity<Catalog> options() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.catalog());
    }
    @PostMapping(value = "/registrations", consumes = "application/json")
    public ResponseEntity<Result> register(@RequestBody Request request) {
        Result result = service.register(request);
        return ResponseEntity.status(result.created() ? 201 : 200).header("Cache-Control", "no-store").body(result);
    }
}
