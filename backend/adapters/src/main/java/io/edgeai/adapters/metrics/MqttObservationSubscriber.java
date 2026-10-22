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
    // Admission stays bounded by deliverySlots; blocked measurements must not consume
    // a fixed worker pool and starve independent measurements or broker acknowledgements.
    private final ExecutorService deliveries=Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("sensor-archive-write-",0).factory());
    private final Semaphore deliverySlots=new Semaphore(64,true);
    private volatile Receiver receiver;
    private volatile boolean closed;
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
        private final Object sessionLock=new Object();
        private volatile long session;
        private final LinkedHashMap<Integer,PendingPacket> pendingPackets=new LinkedHashMap<>();
        private volatile boolean retired;
        private final class PendingPacket {
            final int id,qos;
            boolean completed;
            PendingPacket(MqttMessage message){id=message.getId();qos=message.getQos();}
        }
        Connection(String address) {
            try {
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8))).substring(0,16);
                client=new MqttAsyncClient(address.replace("tls://","ssl://"),clientPrefix+"-"+digest,new MemoryPersistence());
                client.setManualAcks(true);
                client.setCallback(new MqttCallback() {
                    public void disconnected(MqttDisconnectResponse response){synchronized(sessionLock){session++;pendingPackets.clear();subscribed=Set.of();}}
                    public void mqttErrorOccurred(MqttException error){}
                    public void deliveryComplete(IMqttToken token){}
                    public void connectComplete(boolean reconnect,String serverURI){synchronized(sessionLock){session++;pendingPackets.clear();subscribed=Set.of();}connectors.submit(Connection.this::subscribe);}
                    public void authPacketArrived(int reasonCode,MqttProperties properties){}
                    public void messageArrived(String topic,MqttMessage message)throws Exception {
                        long incarnation;PendingPacket packet;
                        synchronized(sessionLock){
                            incarnation=session;
                            // QoS1 DUP copies share one durable completion and one ACK owner.
                            packet=message.getQos()>0?new PendingPacket(message):null;
                            if(packet!=null&&pendingPackets.putIfAbsent(message.getId(),packet)!=null)return;
                        }
                        while(!deliverySlots.tryAcquire(100,TimeUnit.MILLISECONDS)){
                            if(closed||retired||session!=incarnation||!client.isConnected())throw new IllegalStateException("Archive admission stopped");
                        }
                        if(closed||retired||session!=incarnation){deliverySlots.release();throw new IllegalStateException("Archive admission stopped");}
                        var target=receiver;
                        try {deliveries.submit(()->{
                            try {
                                target.receive(new Delivery(address,topic,message.getPayload(),message.isRetained()));
                                synchronized(sessionLock){
                                    // Packet IDs may be reused after reconnect. Old work must never ACK a new session.
                                    if(!closed&&!retired&&session==incarnation&&client.isConnected()){
                                        if(packet!=null&&pendingPackets.get(packet.id)==packet){
                                            packet.completed=true;
                                            // MQTT requires PUBACKs in receive order, even when writes finish out of order.
                                            var pending=pendingPackets.values().iterator();
                                            while(pending.hasNext()){
                                                var next=pending.next();if(!next.completed)break;
                                                client.messageArrivedComplete(next.id,next.qos);pending.remove();
                                            }
                                        }
                                    }
                                }
                            }catch(Exception error){
                                boolean reconnect; synchronized(sessionLock){reconnect=!closed&&!retired&&session==incarnation;if(reconnect)session++;}
                                if(reconnect){try{client.disconnectForcibly(100,500,false);}catch(MqttException ignored){}connectors.submit(Connection.this::connect);}
                            }finally{deliverySlots.release();}
                        });}catch(RejectedExecutionException error){deliverySlots.release();throw error;}
                    }
                });
            }catch(Exception e){throw new IllegalStateException("Archive subscriber cannot start",e);}
        }
        void connect() {
            if(closed||retired)return;
            if(client.isConnected()){subscribe();return;}
            if(!connecting.compareAndSet(false,true))return;
            // A connection may have more deliveries awaiting durable FIFO ACKs than the
            // global64 write slots: e.g.22/s with4.5s ACK latency exceeds64. Keep
            // the broker window bounded while avoiding NanoMQ's quota-exhaustion retry path.
            connectors.submit(()->{try{var options=new MqttConnectionOptions();options.setCleanStart(false);options.setSessionExpiryInterval(86400L);options.setReceiveMaximum(256);options.setAutomaticReconnect(true);options.setConnectionTimeout(2);options.setKeepAliveInterval(15);client.connect(options).waitForCompletion(3000);}catch(MqttException ignored){}finally{connecting.set(false);}});
        }
        synchronized void subscribe() {
            try {
                var current=routes;if(current.equals(subscribed))return;
                var removed=new HashSet<>(subscribed);removed.removeAll(current);if(!removed.isEmpty())client.unsubscribe(removed.toArray(String[]::new));
                if(!current.isEmpty()){var token=client.subscribe(current.toArray(String[]::new),current.stream().mapToInt(topic->1).toArray());token.waitForCompletion(2000);if(Arrays.stream(token.getGrantedQos()).anyMatch(qos->qos!=1))return;}subscribed=Set.copyOf(current);
            }catch(MqttException ignored){}
        }
        public void close(){synchronized(sessionLock){retired=true;session++;}try{if(client.isConnected())client.disconnectForcibly(100,500,false);client.close();}catch(MqttException ignored){}}
    }
    public synchronized void close(){closed=true;deliveries.shutdownNow();for(var connection:connections.values())connection.close();connections.clear();connectors.shutdownNow();}
}
