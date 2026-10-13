package io.edgeai.app.controller;

import io.edgeai.app.service.ProcessingEngineService;
import io.edgeai.domain.vd.ProcessingEngineGateway;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/processing")
public class ProcessingEngineController {
    private final ProcessingEngineService engine;
    public ProcessingEngineController(ProcessingEngineService engine){this.engine=engine;}
    @PostMapping(value="/poll",consumes="application/json") public Map<String,Object> poll(@AuthenticationPrincipal ProcessingEngineGateway.Identity identity,@RequestBody String body){return engine.poll(identity,body);}
    @PostMapping(value="/reset",consumes="application/json") public Map<String,Object> reset(@AuthenticationPrincipal ProcessingEngineGateway.Identity identity,@RequestBody String body){return engine.reset(identity,body);}
    @PostMapping(value="/results",consumes="application/json") public Map<String,Object> results(@AuthenticationPrincipal ProcessingEngineGateway.Identity identity,@RequestBody String body){return engine.results(identity,body);}
}
