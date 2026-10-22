package io.edgeai.adapters.metrics;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MqttObservationSubscriberTest {
    record Packet(int type,byte[] body) {}
    static Packet read(Socket socket)throws IOException {
        var in=socket.getInputStream();int type=in.read();if(type<0)throw new EOFException();
        int length=0,multiplier=1,digit;
        do{digit=in.read();if(digit<0)throw new EOFException();length+=(digit&127)*multiplier;multiplier*=128;}while((digit&128)!=0);
        byte[] body=in.readNBytes(length);if(body.length!=length)throw new EOFException();return new Packet(type,body);
    }
    static void publish(Socket socket,int id,String value,boolean duplicate)throws IOException {
        var bytes=new ByteArrayOutputStream();var body=new DataOutputStream(bytes);
        body.writeShort(12);body.write("archive/test".getBytes(StandardCharsets.UTF_8));body.writeShort(id);body.writeByte(0);body.write(value.getBytes(StandardCharsets.UTF_8));
        var out=socket.getOutputStream();out.write(duplicate?0x3a:0x32);out.write(bytes.size());out.write(bytes.toByteArray());out.flush();
    }
    static int acknowledgement(Socket socket)throws IOException {
        var packet=read(socket);assertEquals(0x40,packet.type());return ((packet.body()[0]&255)<<8)|(packet.body()[1]&255);
    }
    @Test void slowMeasurementBurstCannotStarveAnIndependentDelivery()throws Exception {
        var blocked=new CountDownLatch(16);var release=new CountDownLatch(1);var independent=new CountDownLatch(1);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            var subscriber=new MqttObservationSubscriber("fair-test-"+UUID.randomUUID().toString().substring(0,8))) {
            subscriber.configure(Map.of("tcp://127.0.0.1:"+server.getLocalPort(),Set.of("archive/test")),delivery->{
                String value=new String(delivery.payload(),StandardCharsets.UTF_8);
                if(value.equals("independent"))independent.countDown();
                else {blocked.countDown();release.await();}
            });
            server.setSoTimeout(3000);
            try(var peer=server.accept()) {
                peer.setSoTimeout(2000);assertEquals(0x10,read(peer).type());
                peer.getOutputStream().write(new byte[]{0x20,3,0,0,0});
                var subscribe=read(peer);assertEquals(0x82,subscribe.type());
                peer.getOutputStream().write(new byte[]{(byte)0x90,4,subscribe.body()[0],subscribe.body()[1],0,1});
                for(int id=1;id<=16;id++)publish(peer,id,"slow",false);
                assertTrue(blocked.await(2,TimeUnit.SECONDS));
                publish(peer,17,"independent",false);
                assertTrue(independent.await(1,TimeUnit.SECONDS),"One backed-up measurement must not occupy every durable delivery worker");
                peer.setSoTimeout(100);assertThrows(SocketTimeoutException.class,()->read(peer));
                release.countDown();peer.setSoTimeout(2000);
                for(int id=1;id<=17;id++)assertEquals(id,acknowledgement(peer));
            }finally{release.countDown();}
        }
    }
    @Test void durableAcknowledgementsStayOrderedAndDuplicatePacketsHaveOneOwner()throws Exception {
        var firstEntered=new CountDownLatch(1);var secondDone=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);
        var reusedEntered=new CountDownLatch(1);var releaseReused=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            var subscriber=new MqttObservationSubscriber("ack-test-"+UUID.randomUUID().toString().substring(0,8))) {
            subscriber.configure(Map.of("tcp://127.0.0.1:"+server.getLocalPort(),Set.of("archive/test")),delivery->{
                calls.incrementAndGet();String value=new String(delivery.payload(),StandardCharsets.UTF_8);
                if(value.equals("first")){firstEntered.countDown();releaseFirst.await();}
                else if(value.equals("second"))secondDone.countDown();
                else{reusedEntered.countDown();releaseReused.await();}
            });
            server.setSoTimeout(3000);
            try(var peer=server.accept()) {
                peer.setSoTimeout(2000);assertEquals(0x10,read(peer).type());
                peer.getOutputStream().write(new byte[]{0x20,3,0,0,0});
                var subscribe=read(peer);assertEquals(0x82,subscribe.type());
                peer.getOutputStream().write(new byte[]{(byte)0x90,4,subscribe.body()[0],subscribe.body()[1],0,1});
                publish(peer,7,"first",false);assertTrue(firstEntered.await(2,TimeUnit.SECONDS));
                publish(peer,8,"second",false);assertTrue(secondDone.await(2,TimeUnit.SECONDS));
                publish(peer,7,"first",true);
                peer.setSoTimeout(200);assertThrows(SocketTimeoutException.class,()->read(peer));
                releaseFirst.countDown();peer.setSoTimeout(2000);
                assertEquals(7,acknowledgement(peer));assertEquals(8,acknowledgement(peer));
                assertEquals(2,calls.get());
                publish(peer,7,"reused",false);assertTrue(reusedEntered.await(2,TimeUnit.SECONDS));
                peer.setSoTimeout(200);assertThrows(SocketTimeoutException.class,()->read(peer));
                releaseReused.countDown();peer.setSoTimeout(2000);assertEquals(7,acknowledgement(peer));assertEquals(3,calls.get());
            }finally{releaseFirst.countDown();releaseReused.countDown();}
        }
    }
}
