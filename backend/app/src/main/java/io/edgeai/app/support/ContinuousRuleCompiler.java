package io.edgeai.app.support;

import io.edgeai.domain.vd.ProcessingExpression;
import java.util.*;
import static io.edgeai.app.support.DeviceProfileCompiler.map;

/** Produces only server-owned rules. The public language contains no SQL identifiers, functions or sinks. */
public final class ContinuousRuleCompiler {
    private ContinuousRuleCompiler() {}
    public record Rule(String id,String outputId,String sql,String type,String unit,int warmupTicks,boolean conditional) {}
    public static ProcessingExpression.Type inputType(String type) {
        if(type.equals("bool"))return ProcessingExpression.Type.BOOLEAN;
        if(Set.of("float32","float64","int16","uint16","int32","uint32").contains(type))return ProcessingExpression.Type.NUMBER;
        throw new IllegalArgumentException("Continuous inputs require numeric or boolean measurements");
    }
    public static List<Rule> compile(UUID vd,UUID generation,Object value,Map<String,ProcessingExpression.Type> types) {
        var config=map(WorkflowInput.JSON.decode(ContinuousInput.parse(value,types.keySet())));
        var columns=new TreeMap<String,String>();int index=0;
        for(var key:new TreeSet<>(types.keySet()))columns.put(key,"x"+index++);
        String selector="vdId='"+vd+"' AND generation='"+generation+"'";
        var rules=new ArrayList<Rule>();var used=new HashSet<String>();
        for(var raw:(List<?>)config.get("outputs")) {
            var output=map(raw);String name=(String)output.get("name"),operation=(String)output.get("operation");
            var expression=ProcessingExpression.parse((String)output.get("expression"),types);used.addAll(expression.inputs());
            ResultStoragePolicy.validateType(config,name,expression.type());
            boolean window=Set.of("movingAverage","minimum","maximum").contains(operation);
            if(expression.type()==ProcessingExpression.Type.UNKNOWN || window && expression.type()!=ProcessingExpression.Type.NUMBER || operation.equals("threshold") && expression.type()!=ProcessingExpression.Type.BOOLEAN)
                throw new IllegalArgumentException("Output expression type is incompatible with operation");
            int count=window?((Number)output.get("sampleCount")).intValue():1;
            String calculation=expression.sql(columns,false);
            if(window)calculation=Map.of("movingAverage","AVG","minimum","MIN","maximum","MAX").get(operation)+"("+calculation+")";
            var selected=new ArrayList<String>();
            // eKuiper resolves SELECT aliases in window FILTER too. Use distinct
            // output names for the two selector fields to avoid an aggregate alias.
            for(var field:List.of("vdId","generation","evaluationId","evaluationAt","sequence"))selected.add((window?"LAST_VALUE("+field+",false)":field)+" AS "+(window&&field.equals("vdId")?"frameVdId":window&&field.equals("generation")?"frameGeneration":field));
            selected.add(calculation+" AS value");
            String condition="";
            if(output.containsKey("condition")) {
                var filter=ProcessingExpression.parse((String)output.get("condition"),types);used.addAll(filter.inputs());
                if(filter.type()!=ProcessingExpression.Type.BOOLEAN)throw new IllegalArgumentException("Output condition must be boolean");
                condition=" AND "+filter.sql(columns,window);
            }
            String sql="SELECT "+String.join(",",selected)+" FROM edgeai_vd_frames "+(window?
                "GROUP BY COUNTWINDOW("+count+",1) FILTER(WHERE "+selector+") HAVING COUNT(*)="+count+condition:
                "WHERE "+selector+condition);
            rules.add(new Rule("edgeai_vd_"+vd.toString().replace("-","")+"_"+generation.toString().replace("-","")+"_"+name,name,sql,
                expression.type()==ProcessingExpression.Type.BOOLEAN?"boolean":"number",(String)output.get("unit"),count,output.containsKey("condition")));
        }
        if(!used.equals(types.keySet()))throw new IllegalArgumentException("Every selected input must be used by an output");
        return List.copyOf(rules);
    }
}
