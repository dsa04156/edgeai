package io.edgeai.domain.stream;

import io.edgeai.domain.stream.RouteGeneration.*;
import java.util.Objects;

/** Trusted broker administration boundary. Calls perform network I/O outside DB transactions. */
public interface StreamBrokerGateway {
    record Principal(String kind,Actor actor) {
        public Principal {Objects.requireNonNull(actor);if(!"DEVICE".equals(kind) && !"TASK".equals(kind))throw new IllegalArgumentException("Invalid stream principal");}
        public String username(){return "edgeai-"+kind.toLowerCase(java.util.Locale.ROOT)+"-"+actor.id()+"-"+actor.epoch();}
    }
    record Permission(DataRoute route,RouteGeneration generation) {
        public Permission {Objects.requireNonNull(route);Objects.requireNonNull(generation);if(!route.id().equals(generation.routeId()))throw new IllegalArgumentException("Stream route mismatch");}
        public Principal producer(){return new Principal(route.deviceSource()?"DEVICE":"TASK",generation.producer());}
        public Principal consumer(){return new Principal("TASK",generation.consumer());}
        public String topic(String channel){if(!"frames".equals(channel) && !"acks".equals(channel))throw new IllegalArgumentException("Invalid stream channel");return "edgeai/streams/"+route.id()+"/"+generation.generation()+"/"+channel;}
        public String role(boolean producer){return "edgeai-generation-"+generation.id()+(producer?"-producer":"-consumer");}
        public BrokerReceipt receipt(){return new BrokerReceipt(generation.id(),generation.brokerDigest(),generation.policyDigest());}
    }
    BrokerReceipt grant(Permission permission);
    BrokerReceipt revoke(Permission permission);
}
