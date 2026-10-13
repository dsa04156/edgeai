package io.edgeai.app.service;

import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.app.support.*;
import io.edgeai.domain.repository.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingHistoryDelivery {
    private final ProcessingHistoryQueue queue;
    private final ProcessingHistoryRepository history;
    private final ContinuousRepository executions;
    private final Clock clock;
    private final long limit;
    public ProcessingHistoryDelivery(ProcessingHistoryQueue queue,ProcessingHistoryRepository history,ContinuousRepository executions,Clock clock,@Value("${edgeai.data-store.result-queue-limit:100000}") long limit){this.queue=queue;this.history=history;this.executions=executions;this.clock=clock;this.limit=limit;if(limit<64||limit>1000000)throw new IllegalArgumentException("Invalid history delivery queue limit");}
    public void reserve(int count){if(count>0&&queue.count()+count>limit)throw new ControlPlaneException(503,"HISTORY_BACKPRESSURE","결과 저장 대기열이 가득 찼습니다. 저장소를 확인하세요.");}
    public void enqueue(ProcessingHistoryRepository.Sample sample){queue.enqueue(sample);}
    @Transactional public boolean flush() {
        if(!history.enabled())return false;
        executions.sessionLocked("central");
        queue.expire(clock.instant());var samples=queue.pending(64);if(samples.isEmpty())return true;
        try{history.append(samples);}catch(RuntimeException e){return false;}
        for(var sample:samples) {
            var r=sample.result();var document=DeviceProfileCompiler.map(WorkflowInput.JSON.decode(r.json()));document.put("historyStored",true);document.put("storageStatus","STORED");
            executions.markHistoryStored(r.vdId(),r.outputId(),r.generation(),r.sequence(),WorkflowInput.JSON.canonical(document));
            executions.gap(r.vdId(),r.generation(),"HISTORY_UNAVAILABLE",false,clock.instant());queue.complete(sample);
        }
        return queue.count()==0;
    }
    public long pending(){return queue.count();}
}
