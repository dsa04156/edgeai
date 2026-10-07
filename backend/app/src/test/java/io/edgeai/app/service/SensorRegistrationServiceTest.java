package io.edgeai.app.service;

import io.edgeai.domain.node.SensorRegistrationSource;
import io.edgeai.domain.node.SensorRegistrationSource.*;
import io.edgeai.app.exception.ControlPlaneException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SensorRegistrationServiceTest {
    @Test void disabledSourceIsExplicit() {
        ObjectProvider<SensorRegistrationSource> provider = mock(ObjectProvider.class);
        var service = new SensorRegistrationService(provider, mock(InfrastructureService.class));
        assertEquals(503, assertThrows(ControlPlaneException.class, service::catalog).status());
    }
    @Test void successAndUncertainOutcomeBothInvalidateInventory() {
        ObjectProvider<SensorRegistrationSource> provider = mock(ObjectProvider.class);
        var source = mock(SensorRegistrationSource.class);
        var inventory = mock(InfrastructureService.class);
        when(provider.getIfAvailable()).thenReturn(source);
        var service = new SensorRegistrationService(provider, inventory);
        var request = new Request("sensor", "etri-arduino-temperature", "/dev/edgeai/arduino-002", "arduino-002", 115200);
        when(source.register(request)).thenReturn(new Result("sensor", "device-serial-jetson", "etri-arduino-temperature", true, "UNLOCKED", "DOWN"))
            .thenThrow(new Failure(Failure.Kind.UNKNOWN, "미확인"));
        assertTrue(service.register(request).created());
        assertEquals(502, assertThrows(ControlPlaneException.class, () -> service.register(request)).status());
        verify(inventory, times(2)).invalidate();
    }
}
