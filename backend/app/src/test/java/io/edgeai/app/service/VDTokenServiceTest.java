package io.edgeai.app.service;

import io.edgeai.domain.vd.VDRuntime;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class VDTokenServiceTest {
    @TempDir Path directory;
    private VDRuntime runtime(UUID vd,UUID id,long generation,UUID nonce,String namespace) {
        var now=Instant.now();return new VDRuntime(id,vd,generation,0,"{}","fixture",namespace,"edgeai-vd-"+id,nonce,
            "RUNNING","PENDING",null,null,null,null,null,null,now.plusSeconds(60),null,null,now,now);
    }
    @Test void stableAfterRecreationButBoundToNamespaceVdRuntimeGenerationAndNonce()throws Exception {
        var file=directory.resolve("key");Files.writeString(file,"1".repeat(64));
        var service=new VDTokenService(file.toString());UUID vd=UUID.randomUUID(),id=UUID.randomUUID(),nonce=UUID.randomUUID();
        var r=runtime(vd,id,1,nonce,"edgeai-test");String token=service.issue(r);
        assertThat(new VDTokenService(file.toString()).matches(r,token)).isTrue();
        for(var other:new VDRuntime[]{runtime(UUID.randomUUID(),id,1,nonce,"edgeai-test"),runtime(vd,UUID.randomUUID(),1,nonce,"edgeai-test"),
            runtime(vd,id,2,nonce,"edgeai-test"),runtime(vd,id,1,UUID.randomUUID(),"edgeai-test"),runtime(vd,id,1,nonce,"other")})
            assertThat(service.matches(other,token)).isFalse();
        assertThat(service.matches(r,null)).isFalse();assertThat(service.matches(r,"x".repeat(129))).isFalse();
        assertThat(service.matches(r,"v1."+token.substring(4))).isFalse();
        Files.writeString(file,"2".repeat(64));assertThat(new VDTokenService(file.toString()).matches(r,token)).isFalse();
    }
    @Test void invalidKeyDoesNotExposeFileContentOrPath()throws Exception {
        var file=directory.resolve("private-key");Files.writeString(file,"invalid-private-key-fixture");
        assertThatThrownBy(()->new VDTokenService(file.toString())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("VD signing key requires a file containing 32 random bytes in hex").hasNoCause();
    }
}
