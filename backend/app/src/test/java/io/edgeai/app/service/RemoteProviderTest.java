package io.edgeai.app.service;
import io.edgeai.app.exception.ControlPlaneException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class RemoteProviderTest {
    @TempDir Path root;
    @Test void disabledProviderCannotAcceptWork(){
        try(var p=new RemoteProvider(false,"reference","","","","SYNTHETIC",10)){
            assertThatThrownBy(()->p.select("reference")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("REMOTE_DISABLED"));
        }
    }
    @Test void originAndSourceArePinnedWhileCredentialRotationKeepsBinding() throws Exception {
        Path token=root.resolve("token");Files.writeString(token,"a".repeat(32));
        try(var first=new RemoteProvider(true,"reference","http://127.0.0.1:1",token.toString(),"","SYNTHETIC",1)) {
            var target=first.select("reference");assertThat(target.configurationDigest()).matches("sha256:[a-f0-9]{64}");Files.writeString(token,"b".repeat(32));
            try(var rotated=new RemoteProvider(true,"reference","http://127.0.0.1:1",token.toString(),"","SYNTHETIC",1);
                var moved=new RemoteProvider(true,"reference","http://127.0.0.1:2",token.toString(),"","SYNTHETIC",1);
                var external=new RemoteProvider(true,"reference","http://127.0.0.1:1",token.toString(),"","EXTERNAL",1)) {
                assertThat(rotated.select("reference")).isEqualTo(target);assertThat(moved.select("reference")).isNotEqualTo(target);assertThat(external.select("reference")).isNotEqualTo(target);
                assertThatThrownBy(()->moved.gateway(target)).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("REMOTE_CONFIGURATION_CHANGED"));
                assertThatThrownBy(()->first.select("absent")).isInstanceOfSatisfying(ControlPlaneException.class,e->assertThat(e.code()).isEqualTo("REMOTE_PROVIDER_NOT_FOUND"));
            }
        }
    }
}
