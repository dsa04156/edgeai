package io.edgeai.app.service;

import io.edgeai.domain.repository.SensorReconciliationQueue;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import io.edgeai.domain.repository.SensorTelemetryRepository.Observation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SensorArchiveReconcilerTest {
    static class Queue implements SensorReconciliationQueue {
        final Map<String,Entry> entries=new LinkedHashMap<>();final Map<String,Instant> next=new HashMap<>();
        static String key(Observation o,String series,long sequence){return o.deviceUid()+"/"+o.property()+"/"+series+"/"+sequence;}
        public boolean enqueue(Observation o,String series,long sequence,Instant now,long limit) {
            String key=key(o,series,sequence);if(entries.containsKey(key))return true;if(entries.size()>=limit)return false;
            entries.put(key,new Entry(o,series,sequence,0));next.put(key,now);return true;
        }
        public List<Entry> due(Instant now,int limit){return entries.entrySet().stream().filter(e->!next.get(e.getKey()).isAfter(now)).limit(limit).map(Map.Entry::getValue).toList();}
        public void complete(Entry e){String key=key(e.observation(),e.series(),e.sequence());entries.remove(key);next.remove(key);}
        public void retry(Entry e,Instant at){String key=key(e.observation(),e.series(),e.sequence());entries.put(key,new Entry(e.observation(),e.series(),e.sequence(),e.attempts()+1));next.put(key,at);}
        public int expire(Instant before){var old=entries.values().stream().filter(e->e.observation().observedAt().isBefore(before)).toList();old.forEach(this::complete);return old.size();}
        public long count(){return entries.size();}
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-16T10:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    final MutableClock clock=new MutableClock();
    final Queue queue=new Queue();
    final SensorTelemetryRepository telemetry=mock(SensorTelemetryRepository.class);
    final SensorArchiveReconciler reconciler=new SensorArchiveReconciler(telemetry,queue,clock,64,true,false);
    { when(telemetry.rawRetentionDays()).thenReturn(30); }
    Observation observation(String quality,int sequence) {
        return new Observation(UUID.fromString("00000000-0000-0000-0000-000000000001"),"temperature",clock.now,
            "{\"quality\":\""+quality+"\",\"sequence\":"+sequence+",\"sessionId\":\"abc\",\"sourceGeneration\":\"g\",\"value\":1}");
    }
    @Test void onlyQualifiedOriginalsAreQueuedAndAFullQueueIsCountedInsteadOfBlocking() {
        reconciler.enqueue(observation("UNVERIFIED",0));
        assertEquals(0,queue.count());
        for(int sequence=1;sequence<=70;sequence++)reconciler.enqueue(observation("QUALIFIED",sequence));
        reconciler.enqueue(observation("QUALIFIED",1));
        assertEquals(Map.of("pendingReconciliations",64L,"droppedReconciliations",6L),reconciler.state());
    }
    @Test void storeFailureKeepsTheBatchWithBackoffAndRecoversLater() {
        for(int sequence=1;sequence<=3;sequence++)reconciler.enqueue(observation("QUALIFIED",sequence));
        when(telemetry.hideLegacyDuplicates(anyList())).thenThrow(new IllegalStateException("owned outage"));
        reconciler.drain();
        verify(telemetry,times(1)).hideLegacyDuplicates(argThat(batch->batch.size()==3));
        assertEquals(3,queue.count());
        // Backoff keeps the failed batch out of the next pass.
        reconciler.drain();
        verify(telemetry,times(1)).hideLegacyDuplicates(anyList());
        reset(telemetry);when(telemetry.rawRetentionDays()).thenReturn(30);when(telemetry.hideLegacyDuplicates(anyList())).thenReturn(2);
        clock.now=clock.now.plusSeconds(2);
        reconciler.drain();
        verify(telemetry,times(1)).hideLegacyDuplicates(argThat(batch->batch.size()==3));
        assertEquals(0,queue.count());
    }
    @Test void aLargeBacklogIsDrainedInBoundedBatchesInOnePass() {
        var many=new SensorArchiveReconciler(telemetry,queue,clock,1000,true,false);
        for(int sequence=1;sequence<=600;sequence++)many.enqueue(observation("QUALIFIED",sequence));
        when(telemetry.hideLegacyDuplicates(anyList())).thenReturn(0);
        many.drain();
        verify(telemetry,times(2)).hideLegacyDuplicates(argThat(batch->batch.size()==256));
        verify(telemetry,times(1)).hideLegacyDuplicates(argThat(batch->batch.size()==88));
        assertEquals(0,queue.count());
    }
    @Test void entriesOlderThanRawRetentionExpireWithoutTouchingTheStore() {
        reconciler.enqueue(observation("QUALIFIED",1));
        clock.now=clock.now.plusSeconds(31*86400L);
        reconciler.drain();
        assertEquals(0,queue.count());
        verify(telemetry,never()).hideLegacyDuplicates(anyList());
    }
}
