package io.edgeai.app.service;

import io.edgeai.domain.device.DeviceSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Only authenticated management may issue these session-scoped bootstrap credentials. */
public final class DeviceStreamTokenService {
    private final SecretKeySpec key;
    public DeviceStreamTokenService(Path file){
        try(var input=Files.newByteChannel(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).stream()
                .anyMatch(p->p!=PosixFilePermission.OWNER_READ && p!=PosixFilePermission.OWNER_WRITE) || input.size()!=64)throw new IllegalArgumentException();
            var bytes=java.nio.ByteBuffer.allocate(65);while(input.read(bytes)>0){}bytes.flip();var value=StandardCharsets.US_ASCII.decode(bytes).toString();
            if(!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();key=new SecretKeySpec(HexFormat.of().parseHex(value),"HmacSHA256");
        }catch(Exception e){throw new IllegalArgumentException("Device stream signing key requires a private regular file with 64 lowercase hex characters");}
    }
    public String issue(DeviceSession session){
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(key);
            String message="edgeai-device-stream-v1\n"+session.deviceId()+"\n"+session.id()+"\n"+session.epoch()+"\n"+session.bootId();
            return "ds1."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.US_ASCII)));
        }catch(Exception e){throw new IllegalStateException("Device stream signing unavailable");}
    }
    public boolean matches(DeviceSession session,String token){return token!=null && token.length()<=128
        && MessageDigest.isEqual(issue(session).getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII));}
}
