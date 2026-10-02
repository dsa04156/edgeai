package io.edgeai.app.controller;
import io.edgeai.app.dto.OffloadOperationResponse;
import io.edgeai.app.service.OffloadService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationController {
    private final OffloadService service;
    public OperationController(OffloadService service){this.service=service;}
    @GetMapping("/operations/{operationId}")
    public OffloadOperationResponse detail(@PathVariable UUID operationId){return OffloadOperationResponse.from(service.find(operationId));}
    @PostMapping(value="/tasks/{taskId}/offload",consumes="application/json")
    public ResponseEntity<OffloadOperationResponse> offload(@PathVariable UUID taskId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) {
        var result=service.request(taskId,key,body);
        return ResponseEntity.status(result.created()?202:200).location(URI.create("/api/v1/operations/"+result.value().id())).body(OffloadOperationResponse.from(result.value()));
    }
}
