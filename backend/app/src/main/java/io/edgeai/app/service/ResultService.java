package io.edgeai.app.service;

import io.edgeai.app.dto.TaskResultsResponse;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.repository.*;
import io.edgeai.domain.storage.*;
import io.edgeai.app.support.WorkflowInput;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ResultService {
    private final ExecutionRepository executions;
    private final RuntimeRepository runtimes;
    private final RemoteRepository remotes;
    private final Optional<ArtifactFiles> files;
    private final Semaphore readers=new Semaphore(4);
    public ResultService(ExecutionRepository executions,RuntimeRepository runtimes,RemoteRepository remotes,Optional<ArtifactFiles> files){this.executions=executions;this.runtimes=runtimes;this.remotes=remotes;this.files=files;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TaskResultsResponse results(UUID taskId){
        if(executions.task(taskId).isEmpty())throw new ControlPlaneException(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.");
        var result=runtimes.result(taskId);
        var source=result.map(r->r.remoteAllocationId()).map(id->remotes.find(id).orElseThrow().target().sourceMode()).orElse(null);
        return TaskResultsResponse.from(taskId,result,source);
    }
    /** Immutable committed identity is selected before bounded storage I/O, without holding DB locks. */
    public String content(UUID taskId,String port) {
        if(executions.task(taskId).isEmpty())throw new ControlPlaneException(404,"TASK_NOT_FOUND","작업을 찾을 수 없습니다.");
        var artifact=runtimes.result(taskId).stream().flatMap(result->result.outputs().stream()).filter(output->output.port().equals(port)).map(TaskResult.Output::artifact).findFirst()
            .orElseThrow(()->new ControlPlaneException(404,"RESULT_NOT_FOUND","확정된 출력 파일이 없습니다."));
        if(!"application/json".equals(artifact.mediaType()) || artifact.bytes()>1048576)
            throw new ControlPlaneException(422,"RESULT_PREVIEW_UNSUPPORTED","결과 조회는 1 MiB 이하 JSON 파일을 지원합니다.");
        var store=files.orElseThrow(ArtifactStoreUnavailableException::new);
        if(!readers.tryAcquire())throw new ControlPlaneException(429,"RESULT_PREVIEW_BUSY","결과 조회가 진행 중입니다. 잠시 후 다시 조회하세요.");
        Path directory=null;
        try {
            directory=Files.createTempDirectory("edgeai-result-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var file=directory.resolve("result.json");store.downloadFile(artifact,file);
            try {var content=Files.readString(file);WorkflowInput.JSON.parse(content,1048576);return content;}
            catch(IllegalArgumentException e){throw new ControlPlaneException(422,"RESULT_JSON_INVALID","결과 파일의 JSON 형식을 확인하세요.");}
        } catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
        finally {
            try {if(directory!=null){Files.deleteIfExists(directory.resolve("result.json"));Files.deleteIfExists(directory);}}
            catch(java.io.IOException e){throw new ArtifactStoreUnavailableException();}
            finally {readers.release();}
        }
    }
}
