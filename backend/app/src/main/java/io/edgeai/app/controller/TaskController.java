package io.edgeai.app.controller;
import io.edgeai.app.dto.TaskDetailResponse;
import io.edgeai.app.service.ExecutionService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final ExecutionService service;
    public TaskController(ExecutionService service) { this.service=service; }
    @GetMapping("/{taskId}")
    public TaskDetailResponse detail(@PathVariable UUID taskId) { return TaskDetailResponse.from(service.taskDetail(taskId)); }
    @PostMapping(value="/{taskId}/cancel",consumes="application/json")
    public TaskDetailResponse cancel(@PathVariable UUID taskId,@RequestBody String body) { return TaskDetailResponse.from(service.cancelTask(taskId,body)); }
}
