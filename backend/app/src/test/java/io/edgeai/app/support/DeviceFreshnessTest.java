package io.edgeai.app.support;
import io.edgeai.domain.device.Device;
import io.edgeai.domain.node.ExecutionNode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DeviceFreshnessTest {
    @Test void observedStateExpiresButMissingObservationIsNeverOnline() {
        var now=Instant.parse("2026-10-02T00:00:00Z");
        assertThat(device(now,Device.State.ACTIVE).connectionStatus(now.plusSeconds(60))).isEqualTo("ONLINE");
        assertThat(device(now,Device.State.ACTIVE).connectionStatus(now.plusSeconds(61))).isEqualTo("STALE");
        assertThat(device(null,Device.State.ACTIVE).connectionStatus(now)).isEqualTo("UNKNOWN");
        assertThat(device(now,Device.State.RELEASED).connectionStatus(now)).isEqualTo("RELEASED");
        var node=new ExecutionNode(UUID.randomUUID(),"test","amd64","linux","READY","4","8Gi","{}",now);
        assertThat(node.status(now.plusSeconds(61))).isEqualTo("STALE");
    }
    @Test void deviceJsonRejectsCoercionDuplicateFieldsUnknownFieldsAndInvalidUnicode() {
        for(String input:java.util.List.of("{\"n\":-1}","{\"n\":1.5}","{\"n\":\"1\"}","{\"n\":9007199254740992}"))
            assertThatThrownBy(()->new DeviceInput(input,"n").number("n")).isInstanceOf(IllegalArgumentException.class);
        for(String input:java.util.List.of("{\"n\":1,\"n\":2}","{\"n\":1,\"extra\":2}","{\"n\":\"\\u0000\"}"))
            assertThatThrownBy(()->new DeviceInput(input,"n")).isInstanceOf(IllegalArgumentException.class);
    }
    private Device device(Instant lastSeen,Device.State state) { return new Device(UUID.randomUUID(),"test","test",UUID.randomUUID(),Device.SourceMode.SYNTHETIC,state,0,1,"",lastSeen==null?null:"ONLINE",lastSeen,Instant.EPOCH,Instant.EPOCH); }
}
