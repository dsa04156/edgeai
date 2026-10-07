package io.edgeai.app.controller;

import io.edgeai.app.dto.*;
import io.edgeai.app.service.DeviceService;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
    private final DeviceService service;
    private final Clock clock;
    public DeviceController(DeviceService service,Clock clock) { this.service=service;this.clock=clock; }
    @PostMapping(consumes="application/json")
    public ResponseEntity<DeviceResponse> create(@RequestBody String body) {
        var result=service.create(body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/devices/"+result.value().id()))
            .body(DeviceResponse.from(result.value(),clock.instant()));
    }
    @GetMapping
    public ResourcePageResponse<DeviceResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);var now=clock.instant();
        return new ResourcePageResponse<>(values.stream().limit(limit).map(d->DeviceResponse.from(d,now)).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{deviceId}")
    public DeviceDetailResponse detail(@PathVariable UUID deviceId) {
        var value=service.detail(deviceId);
        return new DeviceDetailResponse(DeviceResponse.from(value.device(),clock.instant()),
            value.attachments().stream().map(DeviceAttachmentResponse::from).toList(),
            value.sessions().stream().map(DeviceSessionResponse::from).toList(),
            value.observations().stream().map(DeviceObservationResponse::from).toList());
    }
    @PatchMapping(value="/{deviceId}",consumes="application/json")
    public DeviceResponse update(@PathVariable UUID deviceId,@RequestBody String body) { return DeviceResponse.from(service.rename(deviceId,body),clock.instant()); }
    @DeleteMapping("/{deviceId}")
    public DeviceResponse release(@PathVariable UUID deviceId) { return DeviceResponse.from(service.release(deviceId),clock.instant()); }
    @DeleteMapping("/{deviceId}/registration")
    public ResponseEntity<Void> delete(@PathVariable UUID deviceId) {
        service.delete(deviceId);
        return ResponseEntity.noContent().build();
    }
    @PutMapping(value="/{deviceId}/attachments/{nodeId}",consumes="application/json")
    public DeviceAttachmentResponse attach(@PathVariable UUID deviceId,@PathVariable UUID nodeId,@RequestBody String body) {
        return DeviceAttachmentResponse.from(service.attach(deviceId,nodeId,body));
    }
    @PostMapping(value="/{deviceId}/sessions",consumes="application/json")
    public ResponseEntity<DeviceSessionResponse> session(@PathVariable UUID deviceId,@RequestBody String body) {
        var result=service.openSession(deviceId,body);
        return ResponseEntity.status(result.created()?201:200).body(DeviceSessionResponse.from(result.value()));
    }
    @PostMapping(value="/{deviceId}/observations",consumes="application/json")
    public ResponseEntity<DeviceObservationResponse> observe(@PathVariable UUID deviceId,@RequestBody String body) {
        var result=service.observe(deviceId,body);
        return ResponseEntity.status(result.created()?201:200).body(DeviceObservationResponse.from(result.value()));
    }
}
