package io.edgeai.adapters.metrics;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against an explicitly selected isolated NanoMQ broker; never publishes sensor topics. */
@EnabledIfEnvironmentVariable(named="EDGEAI_NANOMQ_TEST_URL",matches="tcp://[^/]+:1884")
class NanoMqArchiveIntegrationTest {
    @Test void burstSurvivesSlowDurableReceiversWithoutWaitingForBrokerRetry() throws Exception {
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
                try {
                    var options=new MqttConnectionOptions();options.setCleanStart(true);options.setSessionExpiryInterval(0L);publisher.connect(options);
                    for(int n=0;n<128;n++){
                        var message=new MqttMessage(Integer.toString(n).getBytes(StandardCharsets.UTF_8));message.setQos(1);publisher.publish(topic,message);
                    }
                    assertTrue(complete.await(8,TimeUnit.SECONDS),"All128 measurements must complete durable writes before NanoMQ's delayed retry: received="+received.size());
                    assertEquals(128,received.size());
                }finally{if(publisher.isConnected())publisher.disconnect();publisher.close();}
            }finally{
                subscriber.configure(Map.of(address,Set.of()),delivery->{});
                for(int n=0;n<50&&!subscriber.connected();n++)Thread.sleep(20);
            }
        }
    }
}
