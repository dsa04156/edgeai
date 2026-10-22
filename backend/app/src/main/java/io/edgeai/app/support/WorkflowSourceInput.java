package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Public selection intent, without resolved identities or transport credentials. */
public final class WorkflowSourceInput {
    private WorkflowSourceInput() {}
    /** Omitted or LATEST keeps the original latest-value polling; ALL_OBSERVATIONS is pinned only when explicitly selected. */
    public static Map<String,Object> parse(Object value) {
        var input=new TreeMap<String,Object>();parameters(value).forEach((k,v)->input.put((String)k,v));String kind=text(input.get("kind"),32);
        String delivery=input.containsKey("delivery")?text(input.remove("delivery"),32):"LATEST";
        if(!Set.of("LATEST","ALL_OBSERVATIONS").contains(delivery))throw new IllegalArgumentException("Delivery must be LATEST or ALL_OBSERVATIONS");
        var result=new TreeMap<String,Object>();
        if(kind.equals("SENSOR")) {
            object(input,"kind","deviceId","property");
            String device=text(input.get("deviceId"),63),property=port(input.get("property"));
            if(!device.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"))throw new IllegalArgumentException("Invalid sensor name");
            result.putAll(Map.of("kind",kind,"deviceId",device,"property",property));
        }
        else if(kind.equals("CONTINUOUS_VD")) {
            object(input,"kind","vdId","outputId");
            result.putAll(Map.of("kind",kind,"vdId",uuid(input.get("vdId")).toString(),"outputId",port(input.get("outputId"))));
        }
        else throw new IllegalArgumentException("Input kind must be SENSOR or CONTINUOUS_VD");
        if(delivery.equals("ALL_OBSERVATIONS"))result.put("delivery",delivery);
        return Collections.unmodifiableMap(result);
    }
    private static String port(Object value) {
        String port=text(value,100);
        if(!port.matches("[a-z][a-z0-9]*([._-][a-z0-9]+)*"))throw new IllegalArgumentException("Invalid measurement name");
        return port;
    }
}
