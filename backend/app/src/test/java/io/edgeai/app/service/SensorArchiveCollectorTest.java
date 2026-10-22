package io.edgeai.app.service;

import io.edgeai.domain.device.SensorObservationSubscriber;
import io.edgeai.domain.repository.SensorTelemetryRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SensorArchiveCollectorTest {
    static class Subscriber implements SensorObservationSubscriber {
        Receiver receiver;
        public void configure(Map<String,Set<String>> routes,Receiver receiver){this.receiver=receiver;}
        public boolean connected(){return true;}
        public void close(){}
    }
    @Test void completedDuplicateDeliveriesDoNotRepeatDurableWritesButNewValuesStillDo()throws Exception {verifyDelivery(false);}
    @Test void failedWriteIsRetriedBeforeADuplicateCanBeAcknowledged()throws Exception {verifyDelivery(true);}
    @Test void measurementsWithCollidingHashesNeverBlockEachOther()throws Exception {
        var devices=mock(ManagedDeviceService.class);var telemetry=mock(SensorTelemetryRepository.class);var subscriber=new Subscriber();
        when(telemetry.enabled()).thenReturn(true);when(telemetry.rawRetentionDays()).thenReturn(30);
        UUID first=UUID.fromString("00000000-0000-0000-0000-000000000001"),second=UUID.fromString("00000000-0000-0000-0000-000000000041");
        var routes=new ArrayList<Map<String,Object>>();
        for(var id:List.of(first,second))routes.add(Map.of("broker","tcp://owned-test:1883","topic","archive/"+id,"format","DATAMODEL","deviceId","sensor-"+id,"deviceUid",id.toString(),"namespace","edgeai-devices","property","temperature","unit","C","type","float64","configurationSha256","sha256:test"));
        when(devices.archiveCatalog(anyMap())).thenReturn(routes);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{if(((SensorTelemetryRepository.Observation)call.getArgument(0)).deviceUid().equals(first)){entered.countDown();release.await();}return null;}).when(telemetry).append(any(SensorTelemetryRepository.Observation.class));
        try(var collector=new SensorArchiveCollector(devices,telemetry,mock(WorkflowSourceFeedService.class),Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"),ZoneOffset.UTC),subscriber,true,"{}",5000,false);
            var tasks=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            collector.refresh();
            java.util.function.Function<UUID,java.util.concurrent.Callable<Void>> receive=id->()->{
                String payload="{\"DeviceName\":\"sensor-"+id+"\",\"Namespace\":\"edgeai-devices\",\"PropertyName\":\"temperature\",\"TimeStamp\":1792144800000,\"Value\":\"23.5\"}";
                subscriber.receiver.receive(new SensorObservationSubscriber.Delivery("tcp://owned-test:1883","archive/"+id,payload.getBytes(StandardCharsets.UTF_8),false));return null;
            };
            try {
                var slow=tasks.submit(receive.apply(first));assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
                tasks.submit(receive.apply(second)).get(1,java.util.concurrent.TimeUnit.SECONDS);
                release.countDown();slow.get(1,java.util.concurrent.TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    void verifyDelivery(boolean failFirst)throws Exception {
        var devices=mock(ManagedDeviceService.class);var telemetry=mock(SensorTelemetryRepository.class);var subscriber=new Subscriber();
        when(telemetry.enabled()).thenReturn(true);when(telemetry.rawRetentionDays()).thenReturn(30);
        var route=Map.<String,Object>of("broker","tcp://owned-test:1883","topic","archive/test","format","DATAMODEL","deviceId","sensor","deviceUid",UUID.randomUUID().toString(),"namespace","edgeai-devices","property","temperature","unit","C","type","float64","configurationSha256","sha256:test");
        when(devices.archiveCatalog(anyMap())).thenReturn(List.of(route));
        if(failFirst)doThrow(new IllegalStateException("owned outage")).doNothing().when(telemetry).append(any(SensorTelemetryRepository.Observation.class));
        var clock=Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"),ZoneOffset.UTC);var feeds=mock(WorkflowSourceFeedService.class);
        String payload="{\"DeviceName\":\"sensor\",\"Namespace\":\"edgeai-devices\",\"PropertyName\":\"temperature\",\"TimeStamp\":1792144800000,\"Value\":\"23.5\"}";
        try(var collector=new SensorArchiveCollector(devices,telemetry,feeds,clock,subscriber,true,"{}",5000,false)) {
            collector.refresh();var delivery=new SensorObservationSubscriber.Delivery("tcp://owned-test:1883","archive/test",payload.getBytes(StandardCharsets.UTF_8),false);
            subscriber.receiver.receive(delivery);subscriber.receiver.receive(delivery);
            verify(telemetry,times(failFirst?2:1)).append(any(SensorTelemetryRepository.Observation.class));
            verify(feeds,times(1)).acceptSensor(any(SensorTelemetryRepository.Observation.class));
            subscriber.receiver.receive(new SensorObservationSubscriber.Delivery(delivery.broker(),delivery.topic(),payload.replace("23.5","24.5").getBytes(StandardCharsets.UTF_8),false));
            verify(telemetry,times(failFirst?3:2)).append(any(SensorTelemetryRepository.Observation.class));
            verify(feeds,times(2)).acceptSensor(any(SensorTelemetryRepository.Observation.class));
            assertEquals(3L,collector.state().get("storedDeliveries"));
        }
    }
}
