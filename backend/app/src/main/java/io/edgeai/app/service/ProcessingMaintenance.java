package io.edgeai.app.service;

import io.edgeai.domain.repository.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="edgeai.continuous.enabled",havingValue="true")
public class ProcessingMaintenance {
    private final ContinuousRepository executions;private final ProcessingHistoryRepository history;private final Clock clock;private final boolean inspection;
    @org.springframework.beans.factory.annotation.Autowired private ProcessingHistoryDelivery delivery;
    @Scheduled(fixedDelay=1000,initialDelay=1000) public void deliver(){if(!inspection)delivery.flush();}
    public ProcessingMaintenance(ContinuousRepository executions,ProcessingHistoryRepository history,Clock clock,
        @org.springframework.beans.factory.annotation.Value("${edgeai.recovery.inspect-only:false}") boolean inspection){this.executions=executions;this.history=history;this.clock=clock;this.inspection=inspection;}
    @Scheduled(fixedDelay=5000,initialDelay=30000) public void maintain() {
        if(inspection)return;
        try{executions.maintainReceipts(clock.instant());}catch(RuntimeException error){log("Receipt maintenance deferred",error);}
        if(history.enabled())try{history.maintain(clock.instant());}catch(RuntimeException error){log("History maintenance deferred",error);}
    }
    private static void log(String message,RuntimeException error){org.slf4j.LoggerFactory.getLogger(ProcessingMaintenance.class).warn("{} ({})",message,error.getClass().getSimpleName());}
}
