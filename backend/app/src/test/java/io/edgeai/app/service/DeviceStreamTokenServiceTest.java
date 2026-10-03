package io.edgeai.app.service;
import io.edgeai.domain.device.DeviceSession;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DeviceStreamTokenServiceTest {
    @TempDir Path root;
    private Path key()throws Exception {var file=root.resolve("device.key");Files.writeString(file,"a".repeat(64));Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));return file;}
    @Test void restartStableTokensRemainBoundToDeviceSessionEpochAndBoot()throws Exception {
        var file=key();var tokens=new DeviceStreamTokenService(file);var s=new DeviceSession(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,-1,Instant.now(),null);
        var credential=tokens.issue(s);assertThat(new DeviceStreamTokenService(file).matches(s,credential)).isTrue();
        assertThat(tokens.matches(new DeviceSession(s.id(),UUID.randomUUID(),s.bootId(),s.epoch(),-1,s.openedAt(),null),credential)).isFalse();
        assertThat(tokens.matches(new DeviceSession(UUID.randomUUID(),s.deviceId(),s.bootId(),s.epoch(),-1,s.openedAt(),null),credential)).isFalse();
        assertThat(tokens.matches(new DeviceSession(s.id(),s.deviceId(),s.bootId(),2,-1,s.openedAt(),null),credential)).isFalse();
        assertThat(tokens.matches(new DeviceSession(s.id(),s.deviceId(),UUID.randomUUID(),s.epoch(),-1,s.openedAt(),null),credential)).isFalse();
        assertThat(tokens.matches(s,"v1."+credential.substring(4))).isFalse();assertThat(tokens.matches(s,"x".repeat(129))).isFalse();
        Files.writeString(file,"b".repeat(64));assertThat(new DeviceStreamTokenService(file).matches(s,credential)).isFalse();
    }
    @Test void publicSymlinkAndMalformedKeyFilesFailWithoutRawValues()throws Exception {
        var file=key();Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(()->new DeviceStreamTokenService(file)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("a".repeat(64));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));var link=root.resolve("link");Files.createSymbolicLink(link,file);
        assertThatThrownBy(()->new DeviceStreamTokenService(link)).isInstanceOf(IllegalArgumentException.class);
        for(var value:java.util.List.of("a".repeat(63),"a".repeat(64)+"\n","x".repeat(64))){Files.writeString(file,value);assertThatThrownBy(()->new DeviceStreamTokenService(file)).isInstanceOf(IllegalArgumentException.class);}
    }
}
