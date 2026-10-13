package io.edgeai.domain.device;

import java.util.*;

public interface SensorObservationSubscriber extends AutoCloseable {
    record Delivery(String broker,String topic,byte[] payload,boolean retained) {}
    @FunctionalInterface interface Receiver {void receive(Delivery delivery)throws Exception;}
    void configure(Map<String,Set<String>> subscriptions,Receiver receiver);
    boolean connected();
    void close();
}
