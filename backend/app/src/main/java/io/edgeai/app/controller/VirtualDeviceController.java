package io.edgeai.app.controller;

import io.edgeai.app.dto.*;
import io.edgeai.app.service.VirtualDeviceService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/virtual-devices")
public class VirtualDeviceController {
    private final VirtualDeviceService service;
    public VirtualDeviceController(VirtualDeviceService service) { this.service=service; }
    @PostMapping(consumes="application/json")
    public ResponseEntity<VirtualDeviceResponse> create(@RequestBody String body) {
        var result=service.create(body);
        return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/virtual-devices/"+result.value().id()))
            .body(VirtualDeviceResponse.from(result.value()));
    }
    @GetMapping
    public ResourcePageResponse<VirtualDeviceResponse> list(@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        var values=service.list(limit,offset);
        return new ResourcePageResponse<>(values.stream().limit(limit).map(VirtualDeviceResponse::from).toList(),values.size()>limit?offset+limit:null);
    }
    @GetMapping("/{vdId}")
    public VirtualDeviceDetailResponse detail(@PathVariable UUID vdId) { return VirtualDeviceDetailResponse.from(service.detail(vdId)); }
    @PatchMapping(value="/{vdId}",consumes="application/json")
    public VirtualDeviceResponse update(@PathVariable UUID vdId,@RequestBody String body) { return VirtualDeviceResponse.from(service.update(vdId,body)); }
    @DeleteMapping("/{vdId}")
    public VirtualDeviceResponse release(@PathVariable UUID vdId) { return VirtualDeviceResponse.from(service.release(vdId)); }
}
