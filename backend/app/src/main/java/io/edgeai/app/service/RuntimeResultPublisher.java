package io.edgeai.app.service;

import io.edgeai.domain.repository.RuntimeRepository;
import io.edgeai.domain.runtime.RuntimeResultAuthority;
import io.edgeai.domain.storage.*;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class RuntimeResultPublisher {
    private final RuntimeRepository runtimes;
    private final RuntimeResultJournal journal;
    public RuntimeResultPublisher(RuntimeRepository runtimes,RuntimeResultJournal journal){this.runtimes=runtimes;this.journal=journal;}
    public TaskResult publish(UUID runtimeId){
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Result publication requires a completed database transaction");
        var runtime=runtimes.runtime(runtimeId).orElseThrow();
        // Always reread committed data: PostgreSQL normalizes timestamps to microsecond precision.
        var result=runtimes.result(runtime.taskId()).orElseThrow();
        journal.retainResult(new RuntimeResultAuthority(runtime,result));return result;
    }
}
