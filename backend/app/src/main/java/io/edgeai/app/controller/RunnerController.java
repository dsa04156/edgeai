package io.edgeai.app.controller;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.service.RunnerApiService;
import io.edgeai.app.service.RuntimeTelemetryService;
import io.edgeai.app.service.StreamBindingService;
import io.edgeai.app.service.StreamCheckpointService;
import io.edgeai.app.exception.ControlPlaneException;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<StreamBindingService> streams;
    private final StreamCheckpointService checkpoints;
    public RunnerController(RunnerApiService service,RuntimeTelemetryService telemetry,ObjectProvider<StreamBindingService> streams,StreamCheckpointService checkpoints){this.service=service;this.telemetry=telemetry;this.streams=streams;this.checkpoints=checkpoints;}
    @PostMapping("/streams/checkpoints/uploads") public ResponseEntity<String> checkpointUpload(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,checkpoints.upload(principal,body));}
    @PostMapping("/streams/checkpoints/commit") public ResponseEntity<String> checkpointCommit(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var value=checkpoints.commit(principal,body);return response(value.created()?201:200,Map.of("checkpoint",StreamCheckpointService.receipt(value.value())));
    }
    @PostMapping("/streams/checkpoints/latest") public ResponseEntity<String> checkpointLatest(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,checkpoints.latest(principal,body));}
    @PostMapping("/streams/checkpoints/finalized") public ResponseEntity<String> checkpointFinalized(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,checkpoints.finalized(principal,body));}
    @PostMapping("/streams/checkpoints/handover") public ResponseEntity<String> checkpointHandover(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,checkpoints.handover(principal,body));}
    @PostMapping("/streams") public ResponseEntity<String> streams(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var bindings=streams.getIfAvailable();if(bindings==null)throw new ControlPlaneException(501,"STREAM_DISABLED","스트림 배정 기능이 비활성입니다.");
        return response(200,bindings.runner(principal,body));
    }
    @PostMapping("/claim") public ResponseEntity<String> claim(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){return response(200,service.claim(principal,body));}
    @PostMapping("/streams/execution") public ResponseEntity<String> streamExecution(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var bindings=streams.getIfAvailable();if(bindings==null)throw new ControlPlaneException(501,"STREAM_DISABLED","스트림 배정 기능이 비활성입니다.");
        return response(200,bindings.execution(principal,body));
    }
    @PostMapping("/streams/complete") public ResponseEntity<String> streamComplete(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var bindings=streams.getIfAvailable();if(bindings==null)throw new ControlPlaneException(501,"STREAM_DISABLED","스트림 배정 기능이 비활성입니다.");
        return response(200,bindings.complete(principal,body));
    }
    @PostMapping("/streams/heartbeat") public ResponseEntity<String> heartbeat(@AuthenticationPrincipal RunnerPrincipal principal,@RequestBody String body){
        var bindings=streams.getIfAvailable();if(bindings==null)throw new ControlPlaneException(501,"STREAM_DISABLED","스트림 배정 기능이 비활성입니다.");
        return response(200,bindings.runnerHeartbeat(principal,body));
    }
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
