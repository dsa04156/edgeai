package io.edgeai.app.support;

import java.util.*;
import static io.edgeai.app.support.WorkflowInput.JSON;

public final class RuntimeParameters {
    private RuntimeParameters() {}
    public static String merge(String task,String run,String vdInputs) {
        var merged=new TreeMap<String,Object>();
        for(String document:List.of(task,run)) {
            var values=(Map<?,?>)JSON.decode(document);
            if(values.containsKey("vdInputs") || values.containsKey("vdProcessed"))throw new IllegalArgumentException("vdInputs and vdProcessed are reserved for VD processing");
            values.forEach((k,v)->merged.put((String)k,v));
        }
        if(vdInputs!=null)merged.put("vdInputs",JSON.decode(vdInputs));
        boolean processing=vdInputs!=null && ((Map<?,?>)JSON.decode(vdInputs)).containsKey("processing");
        return JSON.boundedCanonical(merged,processing?1179648:65536);
    }
}
