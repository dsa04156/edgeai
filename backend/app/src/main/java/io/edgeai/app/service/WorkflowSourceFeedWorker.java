package io.edgeai.app.service;

import java.time.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Bounds ALL_OBSERVATIONS feeds that receive no new input: each tick trims one page of feeds (each in its own
 * transaction, skipping feeds another transaction holds) and expires subscription metadata of terminal runs.
 * Live unacknowledged entries are kept unless the per-feed cap applies, which marks lagging consumers GAP.
 */
public final class WorkflowSourceFeedWorker {
    static final int FEEDS_PER_TICK=256,EXPIRED_PER_TICK=1000;
    private final WorkflowSourceFeedService service;
    private final boolean inspection;
    private final Clock clock;
    private UUID after;
    public WorkflowSourceFeedWorker(WorkflowSourceFeedService service,boolean inspection){this(service,inspection,Clock.systemUTC());}
    public WorkflowSourceFeedWorker(WorkflowSourceFeedService service,boolean inspection,Clock clock){this.service=service;this.inspection=inspection;this.clock=clock;}
    @Scheduled(fixedDelayString="${edgeai.workflow-data.feed.maintenance-ms:60000}",initialDelayString="${edgeai.workflow-data.feed.maintenance-ms:60000}")
    public void tick(){maintain(clock.instant());}
    public synchronized void maintain(Instant now) {
        if(inspection)return;
        try {
            var page=service.feeds(after,FEEDS_PER_TICK);
            for(var id:page) {
                try{service.maintain(id,now);}catch(RuntimeException error){log("Feed maintenance deferred",error);}
            }
            after=page.size()<FEEDS_PER_TICK?null:page.getLast();
            service.expire(now,EXPIRED_PER_TICK);
        }catch(RuntimeException error){log("Feed maintenance deferred",error);}
    }
    private static void log(String message,RuntimeException error){LoggerFactory.getLogger(WorkflowSourceFeedWorker.class).warn("{} ({})",message,error.getClass().getSimpleName());}
}
