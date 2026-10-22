package io.edgeai.app.controller;

import io.edgeai.app.dto.InferenceModelsResponse;
import io.edgeai.app.dto.InferenceResultResponse;
import io.edgeai.app.service.InferenceService;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/inference")
public class InferenceController {
    private final InferenceService service;
    public InferenceController(InferenceService service) { this.service = service; }
    @GetMapping("/models")
    public ResponseEntity<InferenceModelsResponse> models() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.models());
    }
    @PostMapping(value = "/predict", consumes = "application/json")
    public ResponseEntity<InferenceResultResponse> predict(InputStream body) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.predict(body));
    }
}
