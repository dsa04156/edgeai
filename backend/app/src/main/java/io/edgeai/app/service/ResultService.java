package io.edgeai.app.service;

import io.edgeai.app.dto.TaskResultsResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.repository.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ResultService {
    private final ExecutionRepository executions;
    private final RuntimeRepository runtimes;
    public ResultService(ExecutionRepository executions,RuntimeRepository runtimes){this.executions=executions;this.runtimes=runtimes;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TaskResultsResponse results(UUID taskId){
        if(executions.task(taskId).isEmpty())throw new ControlPlaneException(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.");
        return TaskResultsResponse.from(taskId,runtimes.result(taskId));
    }
}
