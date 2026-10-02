package io.edgeai.app.service;
import io.edgeai.domain.runtime.RuntimeInstance;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Restart-stable attempt credential. The separate projected token proves the caller's actual Pod identity. */
public final class RunnerTokenService {
    private final SecretKeySpec key;
    public RunnerTokenService(String keyFile) {
        try {
            String value=Files.readString(Path.of(keyFile)).strip();
            if(!value.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();
            key=new SecretKeySpec(HexFormat.of().parseHex(value),"HmacSHA256");
        } catch(Exception error) { throw new IllegalArgumentException("Runner signing key requires a file containing 32 random bytes in hex"); }
    }
    public String issue(RuntimeInstance runtime) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(key);
            String message="edgeai-runner-v1\n"+runtime.namespace()+"\n"+runtime.attemptId()+"\n"+runtime.epoch()+"\n"+runtime.claimNonce();
            return "v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception error) { throw new IllegalStateException("Runner token generation failed"); }
    }
    public boolean matches(RuntimeInstance runtime,String token) {
        return token!=null && token.length()<=128 && MessageDigest.isEqual(issue(runtime).getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII));
    }
}
