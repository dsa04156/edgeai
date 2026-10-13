package io.edgeai.adapters.metrics;

import io.edgeai.domain.device.SensorObservationSubscriber;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.*;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

/** MQTT acknowledgement follows successful receiver completion, including its durable write. */
public final class MqttObservationSubscriber implements SensorObservationSubscriber {
    private final String clientPrefix;
    private final Map<String,Connection> connections=new HashMap<>();
    private final ExecutorService connectors=Executors.newVirtualThreadPerTaskExecutor();
    private volatile Receiver receiver;
    private boolean closed;
    public MqttObservationSubscriber(String clientPrefix){this.clientPrefix=clientPrefix;if(!clientPrefix.matches("[a-zA-Z0-9-]{1,40}"))throw new IllegalArgumentException("Invalid archive client ID");}
    public synchronized void configure(Map<String,Set<String>> routes,Receiver receiver) {
        if(closed)return;this.receiver=receiver;
        for(var address:new ArrayList<>(connections.keySet()))if(!routes.containsKey(address))connections.remove(address).close();
        for(var entry:routes.entrySet()){var connection=connections.computeIfAbsent(entry.getKey(),Connection::new);connection.routes=Set.copyOf(entry.getValue());connection.connect();}
    }
    public synchronized boolean connected(){return !connections.isEmpty()&&connections.values().stream().allMatch(c->c.client.isConnected()&&c.subscribed.equals(c.routes));}
    private final class Connection implements AutoCloseable {
        final MqttAsyncClient client;
        final AtomicBoolean connecting=new AtomicBoolean();
        volatile Set<String> routes=Set.of(),subscribed=Set.of();
        Connection(String address) {
            try {
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8))).substring(0,16);
                client=new MqttAsyncClient(address.replace("tls://","ssl://"),clientPrefix+"-"+digest,new MemoryPersistence());
                client.setCallback(new MqttCallback() {
                    public void disconnected(MqttDisconnectResponse response){subscribed=Set.of();}
                    public void mqttErrorOccurred(MqttException error){}
                    public void deliveryComplete(IMqttToken token){}
                    public void connectComplete(boolean reconnect,String serverURI){subscribed=Set.of();connectors.submit(Connection.this::subscribe);}
                    public void authPacketArrived(int reasonCode,MqttProperties properties){}
                    public void messageArrived(String topic,MqttMessage message)throws Exception {receiver.receive(new Delivery(address,topic,message.getPayload(),message.isRetained()));}
                });
            }catch(Exception e){throw new IllegalStateException("Archive subscriber cannot start",e);}
        }
        void connect() {
            if(client.isConnected()){subscribe();return;}
            if(!connecting.compareAndSet(false,true))return;
            // Dual publication of 17 properties can burst above 16. Keep the window bounded
            // without hitting NanoMQ 0.25.6's delayed resend path during normal collection.
            connectors.submit(()->{try{var options=new MqttConnectionOptions();options.setCleanStart(false);options.setSessionExpiryInterval(86400L);options.setReceiveMaximum(64);options.setAutomaticReconnect(true);options.setConnectionTimeout(2);options.setKeepAliveInterval(15);client.connect(options).waitForCompletion(3000);}catch(MqttException ignored){}finally{connecting.set(false);}});
        }
        synchronized void subscribe() {
            try {
                var current=routes;if(current.equals(subscribed))return;
                var removed=new HashSet<>(subscribed);removed.removeAll(current);if(!removed.isEmpty())client.unsubscribe(removed.toArray(String[]::new));
                if(!current.isEmpty()){var token=client.subscribe(current.toArray(String[]::new),current.stream().mapToInt(topic->1).toArray());token.waitForCompletion(2000);if(Arrays.stream(token.getGrantedQos()).anyMatch(qos->qos!=1))return;}subscribed=Set.copyOf(current);
            }catch(MqttException ignored){}
        }
        public void close(){try{if(client.isConnected())client.disconnectForcibly(100,500,false);client.close();}catch(MqttException ignored){}}
    }
    public synchronized void close(){closed=true;for(var connection:connections.values())connection.close();connections.clear();connectors.shutdownNow();}
}
