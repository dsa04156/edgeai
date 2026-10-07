package io.edgeai.app.service;

import io.edgeai.adapters.kubernetes.KubernetesInfrastructureInventory;
import io.edgeai.adapters.metrics.EdgeXSensorInventory;
import io.edgeai.domain.node.InfrastructureSource;
import io.edgeai.domain.node.SensorInventorySource;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfrastructureTest {
    private final JsonMapper json = new JsonMapper();
    private final Instant now = Instant.parse("2026-10-06T08:00:00Z");
    @Test void classifiesCurrentLabelsNotNodeNamesOrCpuArchitecture() {
        var nodes = KubernetesInfrastructureInventory.decodeNodes(List.of(
            json.readTree("""
              {"metadata":{"name":"anything-arm-server","labels":{"environment":"cloud"}},"status":{"nodeInfo":{"architecture":"arm64"},"conditions":[{"type":"Ready","status":"True"}]}}
              """), json.readTree("""
              {"metadata":{"name":"anything-edge","labels":{"node-role.kubernetes.io/edge":""}},"status":{"conditions":[{"type":"Ready","status":"False"}]}}
              """)));
        assertEquals("EDGE_AI_SERVER", nodes.getFirst().kind()); assertEquals("READY", nodes.getFirst().status());
        assertEquals("EDGE_DEVICE", nodes.getLast().kind()); assertEquals("NOT_READY", nodes.getLast().status());
    }
    @Test void missingOrContradictoryLabelsRemainUnknown() {
        for (String labels : List.of("{}", "{\"environment\":\"cloud\",\"edge.device/class\":\"raspi\"}")) {
            var node = json.readTree("{\"metadata\":{\"name\":\"etri-ser0001\",\"labels\":" + labels + "}}");
            assertEquals("UNKNOWN", KubernetesInfrastructureInventory.decodeNodes(List.of(node)).getFirst().kind());
        }
    }
    @Test void readsOnlyEdgeXInventoryFieldsWithoutLeakingProtocolSecretsOrInventingOnline() {
        var device = json.readTree("""
          {"name":"sensor-a","profileName":"temperature-v1","serviceName":"device-serial","adminState":"LOCKED","operatingState":"DOWN",
           "tags":{"nodeName":"edge-node","hardwareId":"private-hardware-id"},"protocols":{"mqtt":{"password":"do-not-expose"}}}
          """);
        var profile = json.readTree("""
          {"name":"temperature-v1","deviceResources":[{"name":"temperature","properties":{"valueType":"Float64"}}]}
          """);
        var sensor = EdgeXSensorInventory.decode(List.of(device), List.of(profile)).getFirst();
        assertEquals("edge-node", sensor.nodeName()); assertEquals("DOWN", sensor.operatingState());
        assertEquals("LOCKED", sensor.adminState()); assertEquals(List.of("temperature"), sensor.properties());
        String response = json.writeValueAsString(sensor);
        assertFalse(response.contains("password")); assertFalse(response.contains("do-not-expose")); assertFalse(response.contains("private-hardware-id"));
    }
    @Test void duplicateSensorIdentityIsNotSilentlyMerged() {
        var device = json.readTree("{\"name\":\"sensor-a\"}");
        assertThrows(IllegalStateException.class, () -> EdgeXSensorInventory.decode(List.of(device, device), List.of()));
    }
    @Test void edgeXFailureDoesNotEraseNodesOrFallBackToKubeEdgeSensors() {
        @SuppressWarnings("unchecked") ObjectProvider<InfrastructureSource> sources = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<SensorInventorySource> sensors = mock(ObjectProvider.class);
        var source = mock(InfrastructureSource.class); var sensorSource = mock(SensorInventorySource.class);
        when(sources.getIfAvailable()).thenReturn(source); when(sensors.getIfAvailable()).thenReturn(sensorSource);
        when(source.nodes()).thenReturn(List.of(new InfrastructureSource.Node("server-a", "EDGE_AI_SERVER", "arm64", "linux", "READY")));
        when(sensorSource.sensors()).thenReturn(List.of(new InfrastructureSource.Sensor("sensor-a", "edge-a", "profile", "service", "UNLOCKED", "UP", List.of("temp"))));
        var clock = mock(Clock.class); when(clock.instant()).thenReturn(now);
        var service = new InfrastructureService(sources, sensors, clock);
        assertEquals(1, service.snapshot().sensors().size()); service.snapshot(); verify(sensorSource, times(1)).sensors();
        when(clock.instant()).thenReturn(now.plusSeconds(11)); when(sensorSource.sensors()).thenThrow(new IllegalStateException("private upstream details"));
        var result = service.snapshot();
        assertEquals("AVAILABLE", result.nodesStatus()); assertEquals(1, result.nodes().size());
        assertEquals("UNAVAILABLE", result.sensorsStatus()); assertTrue(result.sensors().isEmpty());
    }
    @Test void disabledAndEmptySourcesAreDistinct() {
        @SuppressWarnings("unchecked") ObjectProvider<InfrastructureSource> nodes = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<SensorInventorySource> sensors = mock(ObjectProvider.class);
        var result = new InfrastructureService(nodes, sensors, Clock.fixed(now, ZoneOffset.UTC)).snapshot();
        assertEquals("DISABLED", result.nodesStatus()); assertEquals("DISABLED", result.sensorsStatus());
    }
}
