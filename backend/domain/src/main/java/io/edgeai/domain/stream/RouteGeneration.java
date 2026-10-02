package io.edgeai.domain.stream;

import java.time.Instant;
import java.util.*;

public record RouteGeneration(UUID id,UUID routeId,long generation,Actor producer,Actor consumer,String brokerDigest,String policyDigest,
        String requestDigest,Instant createdAt,Instant updatedAt,Instant leaseUntil,Instant activatedAt,Instant fencedAt,String fenceReason,Instant closedAt) {
    public record Actor(UUID id,long epoch) {
        public Actor {Objects.requireNonNull(id);if(epoch<1 || epoch>9007199254740991L)throw new IllegalArgumentException("Invalid stream actor epoch");}
    }
    public record BrokerReceipt(UUID generationId,String brokerDigest,String policyDigest) {
        public BrokerReceipt {Objects.requireNonNull(generationId);digest(brokerDigest);digest(policyDigest);}
    }
    public RouteGeneration {
        Objects.requireNonNull(id);Objects.requireNonNull(routeId);Objects.requireNonNull(producer);Objects.requireNonNull(consumer);
        Objects.requireNonNull(createdAt);Objects.requireNonNull(updatedAt);Objects.requireNonNull(leaseUntil);digest(brokerDigest);digest(policyDigest);digest(requestDigest);
        if(generation<1 || generation>9007199254740991L || !leaseUntil.isAfter(createdAt))throw new IllegalArgumentException("Invalid stream generation or lease");
        if((fencedAt==null)!=(fenceReason==null) || (closedAt!=null && fencedAt==null))throw new IllegalArgumentException("Invalid stream closure");
    }
    public static void digest(String value){if(value==null || !value.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid stream binding digest");}
    public String state(){return closedAt!=null?"CLOSED":fencedAt!=null?"FENCED":activatedAt!=null?"ACTIVE":"PREPARING";}
    public boolean usableAt(Instant now){return activatedAt!=null && fencedAt==null && now.isBefore(leaseUntil);}
    public boolean matches(BrokerReceipt receipt){return id.equals(receipt.generationId()) && brokerDigest.equals(receipt.brokerDigest()) && policyDigest.equals(receipt.policyDigest());}
}
