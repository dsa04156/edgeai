package io.edgeai.app.support;

import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VDPollInputTest {
    private final JsonDocuments json=new JsonDocuments();
    private Map<String,Object> body(){String id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";return new LinkedHashMap<>(Map.of("vdId",id,"runtimeId",id,"generation",1,"podUid",id,"sessionId",id,"sequence",0,"state","RUNNING","active",List.of(),"completed",List.of()));}
    private VDPollInput.Request parse(Object v){return VDPollInput.parse(json.canonical(v).getBytes(StandardCharsets.UTF_8));}
    @Test void sequenceHashBindsExactBytesWhileSafeIntegerRangeAndUuidCasingAreStrict() {
        var body=body();byte[] bytes=json.canonical(body).getBytes(StandardCharsets.UTF_8);
        assertThat(VDPollInput.parse(bytes).digest()).isNotEqualTo(VDPollInput.parse((json.canonical(body)+" ").getBytes(StandardCharsets.UTF_8)).digest());
        for(Object invalid:new Object[]{-1,1.5,9007199254740992L,"0",true,null}){body.put("sequence",invalid);assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);}
        body.put("sequence",9007199254740991L);assertThat(parse(body).sequence()).isEqualTo(9007199254740991L);
        body.put("vdId",body.get("vdId").toString().toUpperCase(Locale.ROOT));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void allAttemptListsAreBoundedUniqueAndDisjointAndExitIsOnlyProcessMetadata() {
        var body=body();String id=UUID.randomUUID().toString();var active=Map.of("attemptId",id,"epoch",1);
        body.put("active",List.of(active,active));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);
        body.put("active",List.of(active));body.put("completed",List.of(Map.of("attemptId",id,"epoch",1,"exitCode",0)));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);
        body.put("active",List.of());assertThat(parse(body).completed().getFirst().exitCode()).isZero();
        for(int code:new int[]{-65,256}){body.put("completed",List.of(Map.of("attemptId",id,"epoch",1,"exitCode",code)));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);}
        body.put("completed",Collections.nCopies(33,Map.of("attemptId",id,"epoch",1,"exitCode",0)));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);
        body.put("completed",List.of());body.put("active",Collections.nCopies(17,active));assertThatThrownBy(()->parse(body)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedUtf8DuplicateFieldsAndExcessBodyNeverReachPersistence() {
        assertThatThrownBy(()->VDPollInput.parse(new byte[262145])).isInstanceOf(ProfilePayloadTooLargeException.class);
        assertThatThrownBy(()->VDPollInput.parse(new byte[]{(byte)0xc0,(byte)0xaf})).isInstanceOf(IllegalArgumentException.class);
        String duplicate=json.canonical(body()).replace("\"sequence\":0","\"sequence\":0,\"sequence\":0");
        assertThatThrownBy(()->VDPollInput.parse(duplicate.getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
