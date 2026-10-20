package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Public selection intent, without resolved identities or transport credentials. */
public final class WorkflowSourceInput {
    private WorkflowSourceInput() {}
    public static Map<String,Object> parse(Object value) {
        var input=parameters(value);String kind=text(input.get("kind"),32);
        if(kind.equals("SENSOR")) {
            object(input,"kind","deviceId","property");
            String device=text(input.get("deviceId"),63),property=port(input.get("property"));
            if(!device.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"))throw new IllegalArgumentException("Invalid sensor name");
            return Map.of("kind",kind,"deviceId",device,"property",property);
        }
        if(kind.equals("CONTINUOUS_VD")) {
            object(input,"kind","vdId","outputId");
            return Map.of("kind",kind,"vdId",uuid(input.get("vdId")).toString(),"outputId",port(input.get("outputId")));
        }
        throw new IllegalArgumentException("Input kind must be SENSOR or CONTINUOUS_VD");
    }
    private static String port(Object value) {
        String port=text(value,100);
        if(!port.matches("[a-z][a-z0-9]*([._-][a-z0-9]+)*"))throw new IllegalArgumentException("Invalid measurement name");
        return port;
    }
}
