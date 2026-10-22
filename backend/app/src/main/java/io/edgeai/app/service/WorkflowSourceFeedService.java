package io.edgeai.app.service;

import io.edgeai.domain.repository.ContinuousRepository;
import io.edgeai.domain.repository.ObservationFeedRepository;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static io.edgeai.app.support.DeviceProfileCompiler.map;
import static io.edgeai.app.support.WorkflowInput.JSON;

/**
 * ALL_OBSERVATIONS feed boundary: qualified sensor observations after their durable archive write (before MQTT ACK)
 * and continuous VD results inside the transaction that accepts their receipt. Latest-value inputs never use it.
 */
@Service
public class WorkflowSourceFeedService {
    public static final String ALL_OBSERVATIONS="ALL_OBSERVATIONS";
    private final ObservationFeedRepository feeds;
    private final Clock clock;
    private final ObservationFeedRepository.Limits limits;
    public WorkflowSourceFeedService(ObservationFeedRepository feeds,Clock clock,
            @Value("${edgeai.workflow-data.feed.max-backlog:10000}") long maxBacklog,@Value("${edgeai.workflow-data.feed.max-entries:50000}") long maxEntries,
            @Value("${edgeai.workflow-data.feed.retention-seconds:3600}") long retentionSeconds) {
        this.feeds=feeds;this.clock=clock;this.limits=new ObservationFeedRepository.Limits(maxBacklog,maxEntries,retentionSeconds);
    }
    public ObservationFeedRepository.Limits limits(){return limits;}
    public static String sensorKey(Object deviceUid,Object property){return "SENSOR:"+deviceUid+":"+property;}
    public static String vdKey(Object vdId,Object outputId){return "CONTINUOUS_VD:"+vdId+":"+outputId;}
    public static String key(Map<?,?> binding) {
        return "SENSOR".equals(binding.get("kind"))?sensorKey(binding.get("deviceUid"),binding.get("property")):vdKey(binding.get("vdId"),binding.get("outputId"));
    }
    public static boolean all(Map<?,?> binding){return ALL_OBSERVATIONS.equals(binding.get("delivery"));}

    /** Retried MQTT deliveries reuse the immutable Mapper session/sequence identity, so a repeated call never appends twice. */
    @Transactional
    public void acceptSensor(SensorTelemetryRepository.Observation observation) {
        var document=map(JSON.decode(observation.documentJson()));
        if(!"QUALIFIED".equals(document.get("quality")))return;
        var entry=new TreeMap<String,Object>();
        for(String key:List.of("deviceUid","property","sourceGeneration","configurationSha256","type","unit","sessionId","sequence","observedAt","timeBasis","value","quality"))
            entry.put(key,document.get(key));
        String identity=document.get("sourceGeneration")+":"+document.get("sessionId")+":"+document.get("sequence");
        feeds.append(sensorKey(observation.deviceUid(),observation.property()),identity,observation.observedAt(),JSON.canonical(entry),clock.instant(),limits);
    }
    /** Joins the result-acceptance transaction; feeds are locked in key order to avoid deadlocks with run start/drain. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void acceptResults(List<ContinuousRepository.Result> results) {
        var now=clock.instant();
        for(var result:results.stream().sorted(Comparator.comparing((ContinuousRepository.Result r)->vdKey(r.vdId(),r.outputId())).thenComparingLong(ContinuousRepository.Result::sequence)).toList())
            feeds.append(vdKey(result.vdId(),result.outputId()),result.evaluationId(),result.evaluationAt(),result.json(),now,limits);
    }
    public Optional<ObservationFeedRepository.Subscription> subscription(UUID runId,String taskKey){return feeds.subscription(runId,taskKey);}
    public Optional<ObservationFeedRepository.Page> read(ObservationFeedRepository.Subscription subscription,long after,int limit){return feeds.read(subscription,after,limit);}
    /** The Runner presents a cursor only after its journal durably committed every emission up to it. */
    @Transactional
    public void acknowledge(ObservationFeedRepository.Subscription subscription,long position){feeds.acknowledge(subscription,position,clock.instant(),limits);}
    @Transactional
    public void close(ObservationFeedRepository.Subscription subscription){feeds.close(subscription,clock.instant());}
    @Transactional(propagation=Propagation.MANDATORY)
    public void drain(UUID runId){feeds.drain(runId,clock.instant());}
    public List<UUID> feeds(UUID after,int limit){return feeds.feeds(after,limit);}
    @Transactional
    public void maintain(UUID feedId,Instant now){feeds.maintain(feedId,now,limits);}
    @Transactional
    public int expire(Instant now,int limit){return feeds.expireSubscriptions(now,limits,limit);}
    /** Runs inside run creation: the start boundary is the feed head committed with the run. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void subscribe(UUID runId,Map<?,?> bindings) {
        var now=clock.instant();
        bindings.entrySet().stream().filter(e->all(map(e.getValue()))).sorted(Comparator.comparing(e->key(map(e.getValue()))))
            .forEach(e->{var binding=map(e.getValue());feeds.subscribe(runId,(String)e.getKey(),(String)binding.get("kind"),key(binding),now);});
    }
}
