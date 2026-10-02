package io.edgeai.domain.runtime;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public record ResourceRequirements(Map<String, String> requests, Map<String, String> limits) {
    public ResourceRequirements {
        requests = Map.copyOf(requests); limits = Map.copyOf(limits);
        if (!requests.keySet().equals(limits.keySet()) || !requests.keySet().containsAll(Set.of("cpu", "memory")) || requests.size() > 16)
            throw new IllegalArgumentException("CPU/memory and matching request/limit keys are required");
        for (String key : requests.keySet()) {
            BigDecimal request = quantity(key, requests.get(key)), limit = quantity(key, limits.get(key));
            if (request.compareTo(limit) > 0) throw new IllegalArgumentException("Resource request exceeds limit");
            if (!Set.of("cpu", "memory", "ephemeral-storage").contains(key) && request.compareTo(limit) != 0)
                throw new IllegalArgumentException("Extended resources require equal integer requests and limits");
        }
    }
    public String qos() {
        return quantity("cpu", requests.get("cpu")).compareTo(quantity("cpu", limits.get("cpu"))) == 0
            && quantity("memory", requests.get("memory")).compareTo(quantity("memory", limits.get("memory"))) == 0
            ? "Guaranteed" : "Burstable";
    }
    public static BigDecimal quantity(String resource, String value) {
        if (value == null || value.length() > 32) throw new IllegalArgumentException("Invalid resource quantity");
        BigDecimal result;
        if (resource.equals("cpu")) {
            if (value.matches("[0-9]+m")) result = new BigDecimal(value.substring(0, value.length()-1)).movePointLeft(3);
            else if (value.matches("[0-9]+(\\.[0-9]{1,3})?")) result = new BigDecimal(value);
            else throw new IllegalArgumentException("CPU requires cores or integer millicores");
        } else if (resource.equals("memory") || resource.equals("ephemeral-storage")) {
            var matcher = java.util.regex.Pattern.compile("([0-9]+)(Ki|Mi|Gi|Ti)?").matcher(value);
            if (!matcher.matches()) throw new IllegalArgumentException("Storage quantity requires integer bytes or Ki/Mi/Gi/Ti");
            int exponent = matcher.group(2) == null ? 0 : switch (matcher.group(2)) { case "Ki" -> 1; case "Mi" -> 2; case "Gi" -> 3; default -> 4; };
            result = new BigDecimal(matcher.group(1)).multiply(BigDecimal.valueOf(1024).pow(exponent));
        } else {
            RuntimeNames.qualified(resource);
            if (!resource.contains("/") || resource.startsWith("kubernetes.io/") || resource.contains(".kubernetes.io/"))
                throw new IllegalArgumentException("Extended resource requires a vendor domain");
            if (!value.matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException("Extended resource must be a positive integer");
            result = new BigDecimal(value);
        }
        if (result.signum() <= 0) throw new IllegalArgumentException("Resource quantity must be positive");
        return result;
    }
}
