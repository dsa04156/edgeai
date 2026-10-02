package io.edgeai.app.controller;

import io.edgeai.app.dto.*;
import io.edgeai.app.service.VDExecutionService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/virtual-devices/{vdId}")
public class VDExecutionController {
    private final VDExecutionService service;
    public VDExecutionController(VDExecutionService service) { this.service=service; }
    @GetMapping("/execution")
    public VDExecutionResponse status(@PathVariable UUID vdId) { return service.status(vdId); }
    @PostMapping(value="/provision",consumes="application/json")
    public ResponseEntity<VDOperationResponse> provision(@PathVariable UUID vdId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) { return request(vdId,key,body,VDExecutionService.Action.PROVISION); }
    @PostMapping(value="/replace",consumes="application/json")
    public ResponseEntity<VDOperationResponse> replace(@PathVariable UUID vdId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) { return request(vdId,key,body,VDExecutionService.Action.REPLACE); }
    @PostMapping(value="/drain",consumes="application/json")
    public ResponseEntity<VDOperationResponse> drain(@PathVariable UUID vdId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body) { return request(vdId,key,body,VDExecutionService.Action.DRAIN); }
    private ResponseEntity<VDOperationResponse> request(UUID id,String key,String body,VDExecutionService.Action action) {
        var result=service.request(id,key,body,action);
        return ResponseEntity.status(result.created()?202:200).location(URI.create("/api/v1/operations/"+result.value().id())).body(result.value());
    }
}
