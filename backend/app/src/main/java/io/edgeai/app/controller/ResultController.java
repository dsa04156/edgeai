package io.edgeai.app.controller;

import io.edgeai.app.dto.TaskResultsResponse;
import io.edgeai.app.service.ResultService;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class ResultController {
    private final ResultService service;
    public ResultController(ResultService service){this.service=service;}
    @GetMapping("/{taskId}/results")
    public ResponseEntity<TaskResultsResponse> results(@PathVariable UUID taskId){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.results(taskId));}
}
