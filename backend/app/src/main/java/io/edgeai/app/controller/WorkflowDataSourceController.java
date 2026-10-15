package io.edgeai.app.controller;

import io.edgeai.app.service.WorkflowDataInputService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="edgeai.workflow.enabled",havingValue="true")
@RequestMapping(value="/api/v1/workflow-data-sources",produces="application/json")
public class WorkflowDataSourceController {
    private final WorkflowDataInputService service;
    public WorkflowDataSourceController(WorkflowDataInputService service){this.service=service;}
    @GetMapping public ResponseEntity<Map<String,Object>> catalog(){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.catalog());}
}
