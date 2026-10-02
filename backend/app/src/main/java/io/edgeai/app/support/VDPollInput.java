package io.edgeai.app.support;

import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

public final class VDPollInput {
    private VDPollInput() {}
    public record Attempt(UUID id,long epoch,Integer exitCode) {}
    public record Request(UUID vdId,UUID runtimeId,long generation,UUID podUid,UUID sessionId,long sequence,String state,List<Attempt> active,List<Attempt> completed,String digest) {}
    public static Request parse(byte[] body) {
        if(body.length>262144)throw new ProfilePayloadTooLargeException();String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();}
        catch(CharacterCodingException e){throw new IllegalArgumentException("UTF-8 JSON required");}
        var m=object(JSON.parse(text,262144),"vdId","runtimeId","generation","podUid","sessionId","sequence","state","active","completed");
        var active=attempts(m.get("active"),false,16);var completed=attempts(m.get("completed"),true,32);var seen=new HashSet<UUID>();
        for(var a:active)if(!seen.add(a.id()))throw new IllegalArgumentException("Duplicate attempt");
        for(var a:completed)if(!seen.add(a.id()))throw new IllegalArgumentException("Duplicate attempt");
        String state=text(m.get("state"),8);if(!Set.of("RUNNING","DRAINING").contains(state))throw new IllegalArgumentException("Invalid supervisor state");
        String digest;
        try{var hash=MessageDigest.getInstance("SHA-256");hash.update("edgeai-vd-poll-v1\n".getBytes(StandardCharsets.US_ASCII));digest="sha256:"+HexFormat.of().formatHex(hash.digest(body));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return new Request(id(m.get("vdId")),id(m.get("runtimeId")),number(m.get("generation"),1,9007199254740991L),id(m.get("podUid")),id(m.get("sessionId")),
            number(m.get("sequence"),0,9007199254740991L),state,active,completed,digest);
    }
    private static List<Attempt> attempts(Object raw,boolean completed,int max) {
        if(!(raw instanceof List<?> list) || list.size()>max)throw new IllegalArgumentException("Invalid attempt list");
        return list.stream().map(value->{var m=completed?object(value,"attemptId","epoch","exitCode"):object(value,"attemptId","epoch");
            return new Attempt(id(m.get("attemptId")),number(m.get("epoch"),1,9007199254740991L),completed?(int)number(m.get("exitCode"),-64,255):null);}).toList();
    }
    private static UUID id(Object value){UUID result=uuid(value);if(!result.toString().equals(value))throw new IllegalArgumentException("Lowercase UUID required");return result;}
    private static long number(Object value,long min,long max) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Integer required");
        try{long n=new BigDecimal(value.toString()).longValueExact();if(n<min || n>max)throw new IllegalArgumentException("Integer out of range");return n;}
        catch(ArithmeticException e){throw new IllegalArgumentException("Integer out of range");}
    }
}
