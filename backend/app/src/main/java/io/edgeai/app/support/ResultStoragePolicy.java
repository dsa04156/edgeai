package io.edgeai.app.support;

import io.edgeai.domain.vd.ProcessingExpression;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Result retention is independent from computation and output filtering. */
public final class ResultStoragePolicy {
    private ResultStoragePolicy() {}
    public static Map<String,Object> policy(Map<String,Object> configuration) {
        return configuration.containsKey("resultStorage")?DeviceProfileCompiler.map(parameters(configuration.get("resultStorage"))):Map.of("mode","ALL");
    }
    public static void validate(Map<String,Object> configuration) {
        var policy=policy(configuration);String mode=text(policy.get("mode"),16);
        if(!Set.of("NONE","SELECTED","ALL").contains(mode))throw new IllegalArgumentException("Invalid result storage mode");
        object(policy,mode.equals("SELECTED")?new String[]{"mode","outputs"}:new String[]{"mode"});
        if(!mode.equals("SELECTED"))return;
        if(!(policy.get("outputs") instanceof List<?> outputs) || outputs.isEmpty() || outputs.size()>16)throw new IllegalArgumentException("Select one to sixteen stored outputs");
        var definitions=new HashSet<String>();for(var raw:(List<?>)configuration.get("outputs"))definitions.add(text(parameters(raw).get("name"),63));
        var seen=new HashSet<String>();
        for(var raw:outputs) {
            var selection=parameters(raw);object(selection,selection.containsKey("condition")?new String[]{"name","condition"}:new String[]{"name"});
            String name=text(selection.get("name"),63);
            if(!definitions.contains(name) || !seen.add(name))throw new IllegalArgumentException("Stored output must be defined and unique");
            if(selection.containsKey("condition") && ProcessingExpression.parse(text(selection.get("condition"),1024),Map.of("value",ProcessingExpression.Type.UNKNOWN)).type()!=ProcessingExpression.Type.BOOLEAN)
                throw new IllegalArgumentException("Storage condition must be boolean and use the computed value");
        }
    }
    public static boolean selected(Map<String,Object> configuration,String output) {
        var policy=policy(configuration);
        return switch((String)policy.get("mode")) {
            case "ALL" -> true;
            case "NONE" -> false;
            default -> ((List<?>)policy.get("outputs")).stream().map(WorkflowInput::parameters).anyMatch(row->output.equals(row.get("name")));
        };
    }
    public static void validateType(Map<String,Object> configuration,String output,ProcessingExpression.Type type) {
        selection(configuration,output).filter(row->row.containsKey("condition")).ifPresent(row->condition(row,type));
    }
    public static boolean stores(Map<String,Object> configuration,String output,Object value) {
        if(!selected(configuration,output))return false;
        var choice=selection(configuration,output);
        return choice.isEmpty() || !choice.get().containsKey("condition") || Boolean.TRUE.equals(condition(choice.get(),value instanceof Boolean?ProcessingExpression.Type.BOOLEAN:ProcessingExpression.Type.NUMBER).evaluate(Map.of("value",value)));
    }
    private static ProcessingExpression condition(Map<String,Object> selection,ProcessingExpression.Type type) {
        var result=ProcessingExpression.parse((String)selection.get("condition"),Map.of("value",type));
        if(result.type()!=ProcessingExpression.Type.BOOLEAN)throw new IllegalArgumentException("Storage condition must be boolean");return result;
    }
    private static Optional<Map<String,Object>> selection(Map<String,Object> configuration,String output) {
        var policy=policy(configuration);
        if(!"SELECTED".equals(policy.get("mode")))return Optional.empty();
        return ((List<?>)policy.get("outputs")).stream().map(DeviceProfileCompiler::map).filter(row->output.equals(row.get("name"))).findFirst();
    }
}
