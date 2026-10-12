package io.edgeai.app.support;

import java.math.*;
import java.util.*;
import static io.edgeai.app.support.WorkflowInput.*;

/** Validates the shared engine contract before it is stored in an immutable VD profile. */
public final class ProcessingInput {
    private ProcessingInput() {}
    public static String parse(Object value,Set<String> sourceKeys) {
        var config=object(value,"apiVersion","sampleCount","outputPort","pipelines");
        if(!"edgeai.processing/v1".equals(config.get("apiVersion")))throw new IllegalArgumentException("Unsupported processing version");
        int samples=integer(config.get("sampleCount"),1,1024);port(config.get("outputPort"));
        if(!(config.get("pipelines") instanceof List<?> pipelines) || pipelines.isEmpty() || pipelines.size()>16)throw new IllegalArgumentException("1–16 processing pipelines required");
        var names=new HashSet<String>();
        for(var raw:pipelines) {
            var pipeline=object(raw,"name","input","steps");if(!names.add(port(pipeline.get("name"))))throw new IllegalArgumentException("Duplicate pipeline name");
            var input=parameters(pipeline.get("input"));
            if(input.containsKey("sourceKey")) {
                object(input,"sourceKey","property");if(!sourceKeys.contains(text(input.get("sourceKey"),100)))throw new IllegalArgumentException("Unknown processing source");port(input.get("property"));
            } else {object(input,"port","output");port(input.get("port"));port(input.get("output"));}
            if(!(pipeline.get("steps") instanceof List<?> steps) || steps.isEmpty() || steps.size()>16)throw new IllegalArgumentException("1–16 processing steps required");
            int remaining=samples;
            boolean spectrum=false;
            for(int index=0;index<steps.size();index++) {
                var step=parameters(steps.get(index));String op=text(step.get("op"),32);
                switch(op) {
                    case "filter" -> {object(step,"op","mode","alpha");if(!Set.of("lowPass","highPass").contains(step.get("mode")) || decimal(step.get("alpha"))<=0 || decimal(step.get("alpha"))>1)throw new IllegalArgumentException("Invalid filter");}
                    case "movingAverage", "rms" -> {object(step,"op","window");remaining-=integer(step.get("window"),1,remaining)-1;}
                    case "fft" -> {object(step,"op","sampleRateHz");if(spectrum || remaining<2 || (remaining & (remaining-1))!=0 || decimal(step.get("sampleRateHz"))<=0 || decimal(step.get("sampleRateHz"))>1000000)throw new IllegalArgumentException("FFT requires time-domain power-of-two samples and positive sample rate");spectrum=true;remaining=remaining/2+1;}
                    case "threshold" -> {object(step,"op","operator","value");if(!Set.of("gt","gte","lt","lte","eq").contains(step.get("operator")) || index!=steps.size()-1)throw new IllegalArgumentException("Threshold must be the final step");decimal(step.get("value"));}
                    default -> throw new IllegalArgumentException("Unknown processing operation");
                }
            }
        }
        return JSON.boundedCanonical(config,16384);
    }
    public static int integer(Object value,int minimum,int maximum) {
        if(!(value instanceof BigInteger number) || number.compareTo(BigInteger.valueOf(minimum))<0 || number.compareTo(BigInteger.valueOf(maximum))>0)throw new IllegalArgumentException("Processing integer out of range");return number.intValueExact();
    }
    private static double decimal(Object value) {
        if(!(value instanceof BigInteger || value instanceof BigDecimal) || !Double.isFinite(((Number)value).doubleValue()))throw new IllegalArgumentException("Finite processing number required");return ((Number)value).doubleValue();
    }
    private static String port(Object value) {String name=text(value,63);if(!name.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Invalid processing port or property");return name;}
}
