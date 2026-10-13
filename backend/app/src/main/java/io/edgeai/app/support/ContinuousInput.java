package io.edgeai.app.support;

import java.math.BigInteger;
import java.util.*;
import io.edgeai.domain.vd.ProcessingExpression;
import static io.edgeai.app.support.WorkflowInput.*;

/** Public continuous configuration validation; never accepts SQL or sink configuration. */
public final class ContinuousInput {
    private ContinuousInput() {}
    public static String parse(Object value, Set<String> aliases) {
        var config=DeviceProfileCompiler.map(parameters(value));
        object(value,config.containsKey("resultStorage")?new String[]{"intervalSeconds","retentionDays","outputs","resultStorage"}:new String[]{"intervalSeconds","retentionDays","outputs"});
        integer(config.get("intervalSeconds"),1,3600);
        integer(config.get("retentionDays"),1,30);
        if(aliases.isEmpty() || aliases.size()>16)throw new IllegalArgumentException("One to sixteen inputs required");
        for(var alias:aliases)if(!alias.matches("[a-z][a-z0-9_]{0,31}"))throw new IllegalArgumentException("Invalid input alias");
        if(!(config.get("outputs") instanceof List<?> outputs) || outputs.isEmpty() || outputs.size()>16)
            throw new IllegalArgumentException("One to sixteen outputs required");
        var names=new HashSet<String>();
        var inputTypes=new TreeMap<String,ProcessingExpression.Type>();aliases.forEach(a->inputTypes.put(a,ProcessingExpression.Type.UNKNOWN));
        for(var raw:outputs) {
            var output=parameters(raw);var fields=new ArrayList<>(List.of("name","expression","operation","unit"));
            if(output.containsKey("sampleCount"))fields.add("sampleCount");
            if(output.containsKey("condition"))fields.add("condition");
            object(output,fields.toArray(String[]::new));
            String name=text(output.get("name"),63);
            if(!name.matches("[a-z][a-z0-9_]{0,62}") || !names.add(name))throw new IllegalArgumentException("Invalid or duplicate output name");
            var expression=ProcessingExpression.parse(text(output.get("expression"),1024),inputTypes);
            if(!(output.get("unit") instanceof String unit) || unit.length()>32 || unit.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid output unit");
            String operation=text(output.get("operation"),32);
            if(!Set.of("scalar","movingAverage","minimum","maximum","threshold","filter").contains(operation))throw new IllegalArgumentException("Unsupported continuous operation");
            boolean window=Set.of("movingAverage","minimum","maximum").contains(operation);
            if(window!=output.containsKey("sampleCount"))throw new IllegalArgumentException("Window operations require sampleCount");
            if(window)integer(output.get("sampleCount"),1,1024);
            if(window && expression.type()==ProcessingExpression.Type.BOOLEAN)throw new IllegalArgumentException("Window input must be numeric");
            if(operation.equals("threshold") && expression.type()!=ProcessingExpression.Type.BOOLEAN)throw new IllegalArgumentException("Threshold expression must be boolean");
            if(operation.equals("filter") && !output.containsKey("condition"))throw new IllegalArgumentException("Filter condition required");
            if(output.containsKey("condition") && ProcessingExpression.parse(text(output.get("condition"),1024),inputTypes).type()!=ProcessingExpression.Type.BOOLEAN)
                throw new IllegalArgumentException("Filter condition must be boolean");
        }
        ResultStoragePolicy.validate(config);
        return JSON.canonical(config);
    }
    public static int integer(Object value,int min,int max) {
        if(!(value instanceof BigInteger n) || n.compareTo(BigInteger.valueOf(min))<0 || n.compareTo(BigInteger.valueOf(max))>0)
            throw new IllegalArgumentException("Integer out of range");
        return n.intValueExact();
    }
}
