package io.edgeai.app.support;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VirtualDeviceInputTest {
    private final JsonDocuments json=new JsonDocuments();
    private Map<String,Object> spec() throws Exception {
        var original=(Map<?,?>)json.decode(Files.readString(Path.of("../../contracts/profiles/vd-profile.example.json")));
        var result=new HashMap<String,Object>();original.forEach((k,v)->result.put((String)k,v));return result;
    }
    @Test void sourceOrderAndUuidCaseDoNotChangeCreationIdentity() {
        UUID p=UUID.randomUUID(),d=UUID.randomUUID();
        var one=Map.of("sourceKey","one","deviceId",d.toString());var two=Map.of("sourceKey","two","deviceId",d.toString());
        var a=new HashMap<String,Object>(Map.of("key","test","displayName","VD","profileVersionId",p.toString(),"sources",List.of(one,two),"placement",Map.of("mode","AUTO")));
        var first=VirtualDeviceInput.create(json.canonical(a));a.put("sources",List.of(two,one));a.put("profileVersionId",p.toString().toUpperCase(Locale.ROOT));
        assertThat(VirtualDeviceInput.create(json.canonical(a)).digest()).isEqualTo(first.digest());
        a.put("sources",List.of(one,one));assertThatThrownBy(()->VirtualDeviceInput.create(json.canonical(a))).isInstanceOf(IllegalArgumentException.class);
        a.put("sources",List.of());a.put("placement",Map.of("mode","AUTO","nodeId",d.toString()));
        assertThatThrownBy(()->VirtualDeviceInput.create(json.canonical(a))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void vdSpecEnforcesRequiredSourcesStateAndBoundedRuntime() throws Exception {
        var spec=spec();assertThat(VirtualDeviceInput.spec(json.canonical(spec)).sources()).hasSize(1);
        spec.put("sources",Map.of());assertThatThrownBy(()->VirtualDeviceInput.spec(json.canonical(spec))).isInstanceOf(IllegalArgumentException.class);
        spec.put("type","emulation");assertThat(VirtualDeviceInput.spec(json.canonical(spec)).sources()).isEmpty();
        spec.put("state",Map.of("mode","CHECKPOINT"));assertThatThrownBy(()->VirtualDeviceInput.spec(json.canonical(spec))).isInstanceOf(IllegalArgumentException.class);
        spec.put("state",Map.of("mode","STATELESS"));
        for(var n:List.of(-1,0,17,1.5,true,"2")) {
            spec.put("runtime",Map.of("maxConcurrentTasks",n,"startupTimeoutSeconds",60,"drainTimeoutSeconds",60));
            assertThatThrownBy(()->VirtualDeviceInput.spec(json.canonical(spec))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void duplicateModesUnknownFieldsAndUnsafeRevisionsAreRejected() throws Exception {
        var spec=spec();spec.put("sources",Map.of("input",Map.of("required",true,"deviceProfileVersionId",UUID.randomUUID().toString(),"sourceModes",List.of("LIVE","LIVE"))));
        assertThatThrownBy(()->VirtualDeviceInput.spec(json.canonical(spec))).isInstanceOf(IllegalArgumentException.class);
        var unknown=spec();unknown.put("ignored",true);String invalid=json.canonical(unknown);
        assertThatThrownBy(()->VirtualDeviceInput.spec(invalid)).isInstanceOf(IllegalArgumentException.class);
        for(var n:List.of(-1,9007199254740992L,1.5,true,"1")) {
            String update=json.canonical(Map.of("revision",n,"displayName","VD","sources",List.of(),"placement",Map.of("mode","AUTO")));
            assertThatThrownBy(()->VirtualDeviceInput.update(update)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
