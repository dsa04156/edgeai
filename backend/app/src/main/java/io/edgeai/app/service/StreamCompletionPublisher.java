package io.edgeai.app.service;

import io.edgeai.domain.repository.*;
import io.edgeai.domain.storage.*;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes committed, immutable facts. It cannot issue a grant or reopen a producer. */
public final class StreamCompletionPublisher {
    private final StreamCompletionPublicationRepository publications;
    private final StreamExecutionRepository executions;
    private final StreamCompletionJournal journal;
    public StreamCompletionPublisher(StreamCompletionPublicationRepository publications,StreamExecutionRepository executions,StreamCompletionJournal journal){
        this.publications=publications;this.executions=executions;this.journal=journal;
    }
    private static void outsideTransaction(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stream completion publication requires a completed database transaction");
    }
    public void publish(UUID id){outsideTransaction();retain(publications.find(id).orElseThrow());}
    private void retain(io.edgeai.domain.stream.StreamCompletionAuthority authority){
        var history=publications.checkpointHistory(authority.id());
        if(history.isEmpty())throw new ArtifactVerificationException("Original completion checkpoint history is missing");
        for(var checkpoint:history){
            if(!checkpoint.runId().equals(authority.runId()))throw new ArtifactVerificationException("Original checkpoint history belongs to another Run");
            journal.retainCheckpoint(checkpoint);
        }
        journal.retainCompletion(authority);
    }
    public void forAttempt(UUID attempt){
        outsideTransaction();
        if(executions.granted(attempt).isPresent())retain(publications.forAttempt(attempt)
            .orElseThrow(()->new ArtifactVerificationException("Original complete stream barrier is not publishable")));
    }
    public void forGeneration(UUID generation){
        outsideTransaction();
        if(executions.device(generation).filter(c->c.grantedAt()!=null).isPresent())retain(publications.forGeneration(generation)
            .orElseThrow(()->new ArtifactVerificationException("Original complete stream barrier is not publishable")));
    }
}
