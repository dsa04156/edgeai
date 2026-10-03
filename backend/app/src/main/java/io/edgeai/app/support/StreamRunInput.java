package io.edgeai.app.support;

import io.edgeai.domain.profile.ProfileIdentity;
import java.math.BigDecimal;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Public source selection; the server pins the current authenticated Device session. */
public record StreamRunInput(UUID deviceId,String sourcePort,String toTask,String toPort,int maxPayloadBytes) {
    public StreamRunInput {
        Objects.requireNonNull(deviceId);ProfileIdentity.validateKey(sourcePort);ProfileIdentity.validateKey(toTask);ProfileIdentity.validateKey(toPort);
        if(maxPayloadBytes<1 || maxPayloadBytes>262144)throw new IllegalArgumentException("Invalid stream input budget");
    }
    public static List<StreamRunInput> parse(Object value){
        if(!(value instanceof List<?> list) || list.size()>128)throw new IllegalArgumentException("At most 128 Device inputs are supported per Run");
        var result=new ArrayList<StreamRunInput>();var targets=new HashSet<String>();
        for(var item:list){
            var m=object(item,"deviceId","sourcePort","toTask","toPort","maxPayloadBytes");
            if(!(m.get("maxPayloadBytes") instanceof Number))throw new IllegalArgumentException("Payload limit must be an integer");
            int bytes;try{bytes=new BigDecimal(m.get("maxPayloadBytes").toString()).intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid payload limit");}
            var input=new StreamRunInput(uuid(m.get("deviceId")),text(m.get("sourcePort"),100),text(m.get("toTask"),100),text(m.get("toPort"),100),bytes);
            if(!targets.add(input.toTask()+"/"+input.toPort()))throw new IllegalArgumentException("Duplicate stream input target");result.add(input);
        }
        result.sort(Comparator.comparing(StreamRunInput::toTask).thenComparing(StreamRunInput::toPort));return List.copyOf(result);
    }
    public Map<String,Object> document(){return Map.of("deviceId",deviceId.toString(),"sourcePort",sourcePort,"toTask",toTask,"toPort",toPort,"maxPayloadBytes",maxPayloadBytes);}
}
