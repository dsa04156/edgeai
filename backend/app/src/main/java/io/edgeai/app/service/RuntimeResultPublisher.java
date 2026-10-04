package io.edgeai.app.service;

import io.edgeai.domain.repository.*;
import io.edgeai.domain.runtime.RuntimeResultAuthority;
import io.edgeai.domain.vd.VDTaskResultAuthority;
import io.edgeai.domain.storage.*;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class RuntimeResultPublisher {
    private final RuntimeRepository runtimes;
    private final RuntimeResultJournal journal;
    private final VDTaskRepository allocations;
    private final VDRuntimeRepository supervisors;
    private final VDTaskResultJournal vdJournal;
    public RuntimeResultPublisher(RuntimeRepository runtimes,RuntimeResultJournal journal,VDTaskRepository allocations,VDRuntimeRepository supervisors,VDTaskResultJournal vdJournal){
        this.runtimes=runtimes;this.journal=journal;this.allocations=allocations;this.supervisors=supervisors;this.vdJournal=vdJournal;
    }
    public TaskResult publish(UUID runtimeId){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Result publication requires a completed database transaction");
        var runtime=runtimes.runtime(runtimeId).orElseThrow();
        // Always reread committed data: PostgreSQL normalizes timestamps to microsecond precision.
        var result=runtimes.result(runtime.taskId()).orElseThrow();
        if(runtime.vd()){
            var allocation=allocations.byRuntime(runtime.id()).orElseThrow();
            vdJournal.retainVDResult(new VDTaskResultAuthority(runtime,result,allocation,supervisors.runtime(allocation.vdRuntimeId()).orElseThrow()));
        }else journal.retainResult(new RuntimeResultAuthority(runtime,result));
        return result;
    }
}
