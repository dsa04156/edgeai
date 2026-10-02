package io.edgeai.app.service;

import io.edgeai.domain.vd.VDRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Separate HMAC domain from Task Runner credentials; stable across control-plane restarts. */
public final class VDTokenService {
    private final SecretKeySpec key;
    public VDTokenService(String keyFile) {
        try {
            String value=Files.readString(Path.of(keyFile)).strip();
            if(!value.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException();
            key=new SecretKeySpec(HexFormat.of().parseHex(value),"HmacSHA256");
        } catch(Exception error) { throw new IllegalArgumentException("VD signing key requires a file containing 32 random bytes in hex"); }
    }
    public String issue(VDRuntime r) {
        try {
            var mac=Mac.getInstance("HmacSHA256");mac.init(key);
            String message="edgeai-vd-v1\n"+r.namespace()+"\n"+r.vdId()+"\n"+r.id()+"\n"+r.generation()+"\n"+r.claimNonce();
            return "vd1."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception error) { throw new IllegalStateException("VD token generation failed"); }
    }
    public boolean matches(VDRuntime r,String token) {
        return token!=null && token.length()<=128 && MessageDigest.isEqual(issue(r).getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII));
    }
}
