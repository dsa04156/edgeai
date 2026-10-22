package io.edgeai.app.service;

import io.edgeai.app.support.*;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.edgeai.domain.device.SensorObservationSubscriber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

/** Dedicated subscriber: ACK follows durable archive and workflow feed writes. History failure does not block twins or VD execution. */
@Service
public class SensorArchiveCollector implements AutoCloseable {
    private final ManagedDeviceService devices;
    private final SensorTelemetryRepository telemetry;
    private final WorkflowSourceFeedService feeds;
    private final SensorArchiveReconciler reconciler;
    private final Clock clock;
    private final boolean enabled;
    private final SensorObservationSubscriber subscriber;
    private final Map<String,Object> overrides;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("sensor-archive-catalog").factory());
    private volatile Map<String,Map<String,Object>> routes=Map.of();
    private volatile boolean closed;
    private volatile String status="DISABLED";
    private final AtomicLong stored=new AtomicLong(),invalid=new AtomicLong();
    private final AtomicLong waiting=new AtomicLong();
    // Broker retries can use new packet IDs after a durable ACK. Cache only completed
    // deliveries, with a fixed memory bound; failures and restarts always retry storage.
    private final Map<String,Boolean> completed=Collections.synchronizedMap(new LinkedHashMap<String,Boolean>(256,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest){return size()>4096;}
    });
    // Only active/waiting deliveries own entries, bounded by subscriber admission.
    // Hash stripes made unrelated high-rate measurements delay each other's sources.
    private final ConcurrentHashMap<String,MeasurementLock> measurementLocks=new ConcurrentHashMap<>();
    private static final class MeasurementLock {
        final java.util.concurrent.locks.ReentrantLock lock=new java.util.concurrent.locks.ReentrantLock(true);
        int users;
    }
    public SensorArchiveCollector(ManagedDeviceService devices,SensorTelemetryRepository telemetry,WorkflowSourceFeedService feeds,SensorArchiveReconciler reconciler,Clock clock,SensorObservationSubscriber subscriber,
        @Value("${edgeai.data-store.raw.enabled:false}") boolean enabled,@Value("${edgeai.data-store.raw.broker-node-overrides:{}}") String overrides,
        @Value("${edgeai.data-store.raw.interval-ms:5000}") long intervalMs,@Value("${edgeai.recovery.inspect-only:false}") boolean inspection) {
        this.devices=devices;this.telemetry=telemetry;this.feeds=feeds;this.reconciler=reconciler;this.clock=clock;this.enabled=enabled&&!inspection;this.overrides=map(WorkflowInput.JSON.decode(overrides));this.subscriber=subscriber;
        this.intervalMs=intervalMs;if(intervalMs<100||intervalMs>60000)throw new IllegalArgumentException("Invalid archive refresh interval");
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){if(enabled&&telemetry.enabled())scheduler.scheduleWithFixedDelay(this::refresh,0,intervalMs,TimeUnit.MILLISECONDS);}
    /** Whether qualified observations reach the archive and the ALL_OBSERVATIONS feed at all. */
    public boolean collecting(){return enabled&&telemetry.enabled();}
    public Map<String,Object> state() {
        var state=new TreeMap<String,Object>(Map.of("enabled",enabled&&telemetry.enabled(),"status",waiting.get()>0?"STORE_UNAVAILABLE":status,"storedDeliveries",stored.get(),"rejectedDeliveries",invalid.get()));
        if(enabled&&telemetry.enabled())try{state.putAll(reconciler.state());}catch(RuntimeException e){/* Queue counts are diagnostic; the collector state stays available. */}
        return state;
    }
    public synchronized void refresh() {
        if(closed||!enabled||!telemetry.enabled())return;
        try {
            var grouped=new TreeMap<String,Map<String,Map<String,Object>>>();boolean unresolved=false;
            for(var route:devices.archiveCatalog(overrides)) {
                if(!route.containsKey("broker")){unresolved=true;continue;}
                var topics=grouped.computeIfAbsent((String)route.get("broker"),key->new TreeMap<>());
                if(topics.put((String)route.get("topic"),Map.copyOf(route))!=null)throw new IllegalArgumentException("Ambiguous archive topic");
            }
            var current=new HashMap<String,Map<String,Object>>();var subscriptions=new HashMap<String,Set<String>>();
            for(var entry:grouped.entrySet()){subscriptions.put(entry.getKey(),entry.getValue().keySet());entry.getValue().forEach((topic,route)->current.put(entry.getKey()+"|"+topic,route));}
            routes=Map.copyOf(current);subscriber.configure(subscriptions,this::receive);
            status=unresolved?"PARTIAL":subscriptions.isEmpty()?"NO_SOURCES":subscriber.connected()?"COLLECTING":"CONNECTING";
        }catch(RuntimeException e){status="CATALOG_UNAVAILABLE";}
    }
    private void receive(SensorObservationSubscriber.Delivery delivery)throws Exception {
        var route=routes.get(delivery.broker()+"|"+delivery.topic());if(route==null||delivery.retained())return;
        SensorTelemetryRepository.Observation observation;
        try{observation=decode(route,delivery.payload());}catch(RuntimeException e){invalid.incrementAndGet();return;}
        String identity=WorkflowInput.JSON.digest("edgeai-archive-delivery-v1",Map.of("route",route,"payload",new String(delivery.payload(),StandardCharsets.UTF_8)));
        boolean pending=false;
        try {
            while(!closed) {
                try{
                    // Original and qualified topics for one measurement must reconcile serially.
                    String measurement=observation.deviceUid()+"/"+observation.property();
                    var entry=measurementLocks.compute(measurement,(key,current)->{
                        var value=current==null?new MeasurementLock():current;value.users++;return value;
                    });
                    try {
                        entry.lock.lockInterruptibly();
                        try{
                            // ALL_OBSERVATIONS consumers see the observation only after its archive write; all writes are idempotent on retry.
                            // Hiding a legacy duplicate is queued, so a slow or failing reconciliation never withholds this ACK.
                            if(!completed.containsKey(identity)) {
                                telemetry.append(observation);
                                reconciler.enqueue(observation);
                                feeds.acceptSensor(observation);completed.put(identity,true);
                            }
                        }finally{entry.lock.unlock();}
                    }finally{
                        measurementLocks.compute(measurement,(key,current)->--current.users==0?null:current);
                    }
                    stored.incrementAndGet();return;
                }
                catch(RuntimeException e){if(!pending){waiting.incrementAndGet();pending=true;}Thread.sleep(500);}
            }
            throw new IllegalStateException("Archive stopped before acknowledgement");
        }finally{if(pending)waiting.decrementAndGet();}
    }
    private SensorTelemetryRepository.Observation decode(Map<String,Object> route,byte[] bytes) {
        if(bytes.length>65536)throw new IllegalArgumentException("Original payload exceeds archive limit");
        String payload=new String(bytes,StandardCharsets.UTF_8);var value=map(WorkflowInput.JSON.decode(payload));
        var document=new TreeMap<String,Object>();for(var key:List.of("deviceId","deviceUid","namespace","property","unit","type","configurationSha256"))document.put(key,route.get(key));
        boolean qualified="QUALIFIED".equals(route.get("format"));Object measured;long timestamp;
        if(qualified) {
            if(!"edgeai.observation/v1".equals(value.get("apiVersion"))||!Objects.equals(route.get("deviceId"),value.get("deviceName")))throw new IllegalArgumentException("Wrong observation identity");
            for(var key:List.of("namespace","property","sourceGeneration","configurationSha256","type","unit"))if(!Objects.equals(route.get(key),value.get(key)))throw new IllegalArgumentException("Observation configuration changed");
            String session=(String)value.get("sessionId");if(session==null||!session.matches("[a-z0-9]{1,13}"))throw new IllegalArgumentException("Invalid observation session");
            long incarnation=Long.parseLong(session,36);if(incarnation<=0||!Long.toString(incarnation,36).equals(session))throw new IllegalArgumentException("Invalid observation session");
            long sequence=new java.math.BigDecimal(value.get("sequence").toString()).longValueExact();if(sequence<1||sequence>9007199254740991L)throw new IllegalArgumentException("Invalid observation sequence");
            String basis=Objects.toString(value.get("timeBasis"),"");if(!(value.get("timeBasis") instanceof String)||!Set.of("SOURCE","RECEIVED","COLLECTED").contains(basis))throw new IllegalArgumentException("Invalid observation time basis");
            timestamp=new java.math.BigDecimal(value.get("observedAt").toString()).longValueExact();measured=value.get("value");
            String type=(String)route.get("type");if(type.equals("bool")?!(measured instanceof Boolean):type.equals("string")?!(measured instanceof String):!(measured instanceof Number))throw new IllegalArgumentException("Observation value type mismatch");
            document.put("sessionId",value.get("sessionId"));document.put("sequence",sequence);document.put("sourceGeneration",route.get("sourceGeneration"));document.put("timeBasis",basis);document.put("quality","QUALIFIED");
        }else {
            for(var key:Map.of("DeviceName","deviceId","PropertyName","property","Namespace","namespace").entrySet())if(!Objects.equals(value.get(key.getKey()),route.get(key.getValue())))throw new IllegalArgumentException("Wrong original DataModel identity");
            Object raw=value.containsKey("Value")?value.get("Value"):value.get("value");Object at=value.containsKey("TimeStamp")?value.get("TimeStamp"):value.get("timeStamp");
            timestamp=Long.parseLong(Objects.toString(at,""));String type=(String)route.get("type");String text=Objects.toString(raw,"");
            if(type.equals("bool")){if(!Set.of("true","false","1","0").contains(text))throw new IllegalArgumentException("Invalid boolean observation");measured=text.equals("true")||text.equals("1");}
            else if(type.equals("string"))measured=text;else measured=Double.parseDouble(text);
            document.put("quality","UNVERIFIED");document.put("timeBasis","MAPPER_REPORTED");
        }
        if(!(measured instanceof Number || measured instanceof Boolean || measured instanceof String)||measured instanceof Number n&&!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid observed value");
        Instant at=Instant.ofEpochMilli(timestamp);if(at.isAfter(clock.instant().plusSeconds(60))||at.isBefore(clock.instant().minusSeconds(telemetry.rawRetentionDays()*86400L)))throw new IllegalArgumentException("Observation outside archive period");
        document.put("observedAt",at.toString());document.put("receivedAt",clock.instant().toString());document.put("value",measured);document.put("rawPayload",payload);
        return new SensorTelemetryRepository.Observation(UUID.fromString((String)route.get("deviceUid")),(String)route.get("property"),at,WorkflowInput.JSON.canonical(document));
    }
    public synchronized void close(){closed=true;scheduler.shutdownNow();subscriber.close();}
}
