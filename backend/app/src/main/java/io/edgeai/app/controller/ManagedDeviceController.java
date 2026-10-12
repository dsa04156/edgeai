package io.edgeai.app.controller;

import io.edgeai.app.service.ManagedDeviceService;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ManagedDeviceController {
    private final ManagedDeviceService service;
    public ManagedDeviceController(ManagedDeviceService service){this.service=service;}
    @PostMapping("/api/devices") public ResponseEntity<Map<String,Object>> create(@RequestBody String body){return ResponseEntity.status(201).body(service.create(body));}
    @GetMapping("/api/devices") public Map<String,Object> list(){return service.list();}
    @GetMapping("/api/devices/{id}") public Map<String,Object> get(@PathVariable String id){return service.get(id);}
    @PutMapping("/api/devices/{id}") public Map<String,Object> update(@PathVariable String id,@RequestBody String body){return service.update(id,body);}
    @DeleteMapping("/api/devices/{id}") public ResponseEntity<Void> delete(@PathVariable String id){service.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/api/devices/{id}/properties") public ResponseEntity<Map<String,Object>> addProperty(@PathVariable String id,@RequestBody String body){return ResponseEntity.status(201).body(service.addProperty(id,body));}
    @PutMapping("/api/devices/{id}/properties/{property}") public Map<String,Object> updateProperty(@PathVariable String id,@PathVariable String property,@RequestBody String body){return service.updateProperty(id,property,body);}
    @DeleteMapping("/api/devices/{id}/properties/{property}") public ResponseEntity<Void> deleteProperty(@PathVariable String id,@PathVariable String property){service.deleteProperty(id,property);return ResponseEntity.noContent().build();}
    @PostMapping("/api/device-connections/test") public Map<String,Object> test(@RequestBody String body){return service.testConnection(body);}
    @GetMapping("/api/device-profiles") public Map<String,Object> profiles(){return service.profiles();}
    @PostMapping("/api/device-profiles") public ResponseEntity<Map<String,Object>> createProfile(@RequestBody String body){return ResponseEntity.status(201).body(service.createProfile(body));}
    @GetMapping("/api/protocols") public Map<String,Object> protocols(){return service.protocols();}
}
