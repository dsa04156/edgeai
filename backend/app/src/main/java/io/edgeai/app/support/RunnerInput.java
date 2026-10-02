package io.edgeai.app.support;
import io.edgeai.app.config.RunnerPrincipal;
import io.edgeai.app.exception.ControlPlaneException;
import io.edgeai.domain.storage.ResultManifest;
import java.math.BigDecimal;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

public final class RunnerInput {
    private RunnerInput() {}
    public static Map<?,?> parse(String body,RunnerPrincipal principal,String... extra) {
        var fields=new ArrayList<>(List.of("epoch","podUid"));fields.addAll(List.of(extra));
        var root=object(JSON.parse(body,262144),fields.toArray(String[]::new));JSON.boundedCanonical(root,262144);
        if(integer(root.get("epoch"))!=principal.epoch() || !uuid(root.get("podUid")).equals(principal.podUid()))
            throw new ControlPlaneException(409,"PRODUCER_FENCED","인증된 Pod와 실행 epoch가 일치하지 않습니다.");
        return root;
    }
    public static long integer(Object value) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Integer required");
        try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Integer out of range");}
    }
    public static List<Map<?,?>> outputs(Object value,boolean version) {
        if(!(value instanceof List<?> items)||items.isEmpty()||items.size()>16)throw new IllegalArgumentException("Output list required");
        var result=new ArrayList<Map<?,?>>();var seen=new HashSet<String>();
        for(var item:items) {
            var fields=version?object(item,"port","bytes","sha256","mediaType","versionId"):object(item,"port","bytes","sha256","mediaType");
            if(!seen.add(text(fields.get("port"),100)))throw new IllegalArgumentException("Duplicate output port");result.add(fields);
        }
        return List.copyOf(result);
    }
    public static ResultManifest manifest(Object value) {
        return new ResultManifest(outputs(value,true).stream().map(o->new ResultManifest.Output(text(o.get("port"),100),integer(o.get("bytes")),
            text(o.get("sha256"),64),text(o.get("mediaType"),128),text(o.get("versionId"),1024))).toList());
    }
}
