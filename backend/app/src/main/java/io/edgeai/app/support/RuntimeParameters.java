package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

public final class RuntimeParameters {
    private RuntimeParameters() {}
    public static String merge(String task,String run) {
        var merged=new TreeMap<String,Object>();
        for(String document:List.of(task,run)) {
            var values=(Map<?,?>)JSON.decode(document);
            if(values.containsKey("vdInputs"))throw new IllegalArgumentException("vdInputs is reserved for pinned sensor inputs");
            values.forEach((k,v)->merged.put((String)k,v));
        }
        return JSON.boundedCanonical(merged,65536);
    }
}
