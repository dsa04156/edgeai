package io.edgeai.adapters.metrics;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against an explicitly selected isolated NanoMQ broker; never publishes sensor topics. */
class NanoMqArchiveIntegrationTest {
    @Test @Timeout(30) void burstSurvivesSlowDurableReceiversWithoutWaitingForBrokerRetry() throws Exception {
        String address=System.getenv("EDGEAI_NANOMQ_TEST_URL");
        String run=UUID.randomUUID().toString();String topic="edgeai/test/archive-burst/"+run;
        var received=ConcurrentHashMap.<String>newKeySet();var complete=new CountDownLatch(128);
        try(var subscriber=new MqttObservationSubscriber("burst-"+run.substring(0,8))) {
            subscriber.configure(Map.of(address,Set.of(topic)),delivery->{
                Thread.sleep(200);
                if(received.add(new String(delivery.payload(),StandardCharsets.UTF_8)))complete.countDown();
            });
            try {
                for(int n=0;n<100&&!subscriber.connected();n++)Thread.sleep(30);
                assertTrue(subscriber.connected(),"The owned NanoMQ test subscription must be ready");
                var publisher=new MqttClient(address,"burst-publisher-"+run);
                publisher.setTimeToWait(3000);
                try {
                    var options=new MqttConnectionOptions();options.setCleanStart(true);options.setSessionExpiryInterval(0L);publisher.connect(options);
                    for(int n=0;n<128;n++){
                        var message=new MqttMessage(Integer.toString(n).getBytes(StandardCharsets.UTF_8));message.setQos(1);publisher.publish(topic,message);
                    }
                    assertTrue(complete.await(8,TimeUnit.SECONDS),"All128 measurements must complete durable writes before NanoMQ's delayed retry: received="+received.size());
                    assertEquals(128,received.size());
                }finally{if(publisher.isConnected())publisher.disconnect(1000);publisher.close();}
            }finally{
                subscriber.configure(Map.of(address,Set.of()),delivery->{});
                for(int n=0;n<50&&!subscriber.connected();n++)Thread.sleep(20);
            }
        }
    }
    @Test @Timeout(30) void currentSensorRateDoesNotAccumulateSlowDurableWrites() throws Exception {
        String address=System.getenv("EDGEAI_NANOMQ_TEST_URL");
        String run=UUID.randomUUID().toString();String topic="edgeai/test/archive-rate/"+run;
        var received=ConcurrentHashMap.<String>newKeySet();var complete=new CountDownLatch(204);
        try(var subscriber=new MqttObservationSubscriber("rate-"+run.substring(0,8))) {
            subscriber.configure(Map.of(address,Set.of(topic)),delivery->{
                Thread.sleep(350);
                if(received.add(new String(delivery.payload(),StandardCharsets.UTF_8)))complete.countDown();
            });
            try {
                for(int n=0;n<100&&!subscriber.connected();n++)Thread.sleep(30);
                assertTrue(subscriber.connected());
                var publisher=new MqttClient(address,"rate-publisher-"+run);publisher.setTimeToWait(3000);
                try {
                    var options=new MqttConnectionOptions();options.setCleanStart(true);options.setSessionExpiryInterval(0L);publisher.connect(options);
                    //17 sensor properties can each publish original and qualified observations once a second.
                    long started=System.nanoTime();
                    for(int n=0;n<204;n++){
                        var message=new MqttMessage(Integer.toString(n).getBytes(StandardCharsets.UTF_8));message.setQos(1);publisher.publish(topic,message);
                        long remaining=started+(n+1)*1_000_000_000L/34-System.nanoTime();
                        if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                    assertTrue(complete.await(1500,TimeUnit.MILLISECONDS),"The34/s sensor rate must not leave a growing durable-write backlog: received="+received.size());
                    assertEquals(204,received.size());
                }finally{if(publisher.isConnected())publisher.disconnect(1000);publisher.close();}
            }finally{subscriber.configure(Map.of(address,Set.of()),delivery->{});}
        }
    }

}
