package io.edgeai.app.controller;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.service.RunnerApiService;
import io.edgeai.app.service.RuntimeTelemetryService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

@RestController
@ConditionalOnProperty(name="edgeai.runtime.enabled",havingValue="true")
@RequestMapping(value="/internal/v1/attempts/{attemptId}",consumes="application/json",produces="application/json")
public class RunnerController {
    private final RunnerApiService service;
    private final RuntimeTelemetryService telemetry;
    public RunnerController(RunnerApiService service,RuntimeTelemetryService telemetry){this.service=service;this.telemetry=telemetry;}
    @PostMapping("/claim") public ResponseEntity<String> claim(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,service.claim(principal,body));}
    @PostMapping("/uploads") public ResponseEntity<String> uploads(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,service.uploads(principal,body));}
    @PostMapping("/commit") public ResponseEntity<String> commit(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var result=service.commit(principal,body);var value=result.value();
        return response(result.created()?201:200,Map.of("resultId",value.id().toString(),"taskId",value.taskId().toString(),"attemptId",value.attemptId().toString(),"state","SUCCEEDED"));
    }
    @PostMapping("/fail") public ResponseEntity<String> fail(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,service.fail(principal,body));}
    @PostMapping("/telemetry") public ResponseEntity<String> telemetry(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var sample=telemetry.record(principal,body);return response(200,Map.of("attemptId",sample.attemptId().toString(),"sequence",sample.sequence(),"receivedAt",sample.receivedAt().toString()));
    }
    private static ResponseEntity<String> response(int status,Object body){return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(JSON.boundedCanonical(body,262144));}
}
