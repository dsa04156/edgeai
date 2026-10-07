package io.edgeai.app.controller;

import io.edgeai.app.service.SensorService;
import io.edgeai.domain.node.SensorAccessSource.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sensors")
public class SensorController {
    private final SensorService service;
    public SensorController(SensorService service) {this.service=service;}
    public record CommandRequest(String device,String command,String method,Map<String,String> values) {}
    @GetMapping("/readings")
    public ResponseEntity<Readings> readings(@RequestParam String device,@RequestParam(defaultValue="") String resource,@RequestParam(defaultValue="100") int limit) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.readings(device,resource,limit));
    }
    @GetMapping("/commands")
    public ResponseEntity<Commands> commands(@RequestParam String device) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.commands(device));
    }
    @PostMapping(value="/command",consumes="application/json")
    public ResponseEntity<CommandResult> command(@RequestBody CommandRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.execute(request.device(),request.command(),request.method(),request.values()));
    }
}
