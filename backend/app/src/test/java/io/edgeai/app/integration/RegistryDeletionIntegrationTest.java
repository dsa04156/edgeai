package io.edgeai.app.integration;

import io.edgeai.app.exception.*;
import io.edgeai.app.service.*;
import tools.jackson.databind.json.JsonMapper;
import io.edgeai.domain.profile.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RegistryDeletionIntegrationTest {
    @Autowired ProfileService profiles;
    @Autowired DeviceService devices;
    private final JsonMapper json = new JsonMapper();
    @Autowired JdbcTemplate jdbc;

    private ProfileVersion profile(ProfileIdentity.Kind kind, Map<String, ?> spec) {
        return profiles.publish(kind, json.writeValueAsString(Map.of("key", "delete-" + UUID.randomUUID(), "version", "1.0.0", "spec", spec))).version();
    }
    private UUID device(ProfileVersion profile) {
        return devices.create(json.writeValueAsString(Map.of("key", "delete-" + UUID.randomUUID(), "displayName", "Deletion fixture",
            "profileVersionId", profile.id(), "sourceMode", "LIVE"))).value().id();
    }
    @Test void referencedProfileIsBlockedUntilItsUnusedDeviceIsDeleted() {
        var profile = profile(ProfileIdentity.Kind.DEVICE, Map.of("protocol", "mqtt"));
        var device = device(profile);
        assertThatThrownBy(() -> profiles.delete(profile.identity())).isInstanceOf(ProfileInUseException.class);
        assertThat(profiles.find(profile.identity()).id()).isEqualTo(profile.id());
        devices.delete(device);
        assertThatThrownBy(() -> devices.detail(device)).isInstanceOf(ControlPlaneException.class);
        profiles.delete(profile.identity());
        assertThatThrownBy(() -> profiles.find(profile.identity())).isInstanceOf(ProfileNotFoundException.class);
    }
    @Test void activeSessionBlocksDeletionAndReleaseAllowsRemovingClosedHistory() {
        var profile = profile(ProfileIdentity.Kind.DEVICE, Map.of("protocol", "mqtt"));
        var device = device(profile);
        devices.openSession(device, json.writeValueAsString(Map.of("bootId", UUID.randomUUID())));
        assertThatThrownBy(() -> devices.delete(device)).isInstanceOfSatisfying(ControlPlaneException.class,
            failure -> assertThat(failure.code()).isEqualTo("DEVICE_IN_USE"));
        assertThat(devices.detail(device).sessions()).hasSize(1);
        devices.release(device);
        devices.delete(device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM edgeai.device_session WHERE device_id=?", Integer.class, device)).isZero();
        profiles.delete(profile.identity());
    }
    @Test void vdProfileJsonReferencesProtectServiceAndDeviceVersions() {
        var service = profile(ProfileIdentity.Kind.SERVICE, Map.of("image", "fixture"));
        var source = profile(ProfileIdentity.Kind.DEVICE, Map.of("protocol", "mqtt"));
        var vd = profile(ProfileIdentity.Kind.VD, Map.of("serviceProfileVersionId", service.id().toString().toUpperCase(),
            "sources", Map.of("sensor", Map.of("deviceProfileVersionId", source.id()))));
        assertThatThrownBy(() -> profiles.delete(service.identity())).isInstanceOf(ProfileInUseException.class);
        assertThatThrownBy(() -> profiles.delete(source.identity())).isInstanceOf(ProfileInUseException.class);
        profiles.delete(vd.identity());
        profiles.delete(service.identity());
        profiles.delete(source.identity());
    }
    @Test void deletingOneVersionDoesNotRemoveOtherVersions() {
        var first = profile(ProfileIdentity.Kind.SERVICE, Map.of("image", "fixture"));
        var second = profiles.publish(ProfileIdentity.Kind.SERVICE, json.writeValueAsString(Map.of("key", first.identity().key(),
            "version", "2.0.0", "spec", Map.of("image", "fixture-2")))).version();
        profiles.delete(first.identity());
        assertThat(profiles.find(second.identity()).id()).isEqualTo(second.id());
        profiles.delete(second.identity());
    }
}
