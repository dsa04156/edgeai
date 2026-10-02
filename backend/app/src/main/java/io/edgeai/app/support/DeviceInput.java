package io.edgeai.app.support;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public final class DeviceInput {
    private final Map<?, ?> values;
    public static final JsonDocuments JSON = new JsonDocuments();
    public DeviceInput(String body, String... fields) {
        if (!(JSON.parse(body, 16384) instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(fields)))
            throw new IllegalArgumentException("Unexpected fields");
        values = map;
        JSON.boundedCanonical(map, 16384);
    }
    public String text(String field, int max) {
        if (!(values.get(field) instanceof String s) || s.isBlank() || s.length() > max || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid text");
        return s;
    }
    public UUID uuid(String field) {
        String s = text(field, 36);
        UUID id = UUID.fromString(s);
        if (!id.toString().equalsIgnoreCase(s)) throw new IllegalArgumentException("Invalid UUID");
        return id;
    }
    public long number(String field) {
        if (!(values.get(field) instanceof BigInteger n) || n.signum() < 0 || n.compareTo(BigInteger.valueOf(9007199254740991L)) > 0)
            throw new IllegalArgumentException("Invalid nonnegative integer");
        return n.longValueExact();
    }
    public Instant instant(String field) {
        try { return Instant.parse(text(field, 64)); }
        catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Invalid timestamp"); }
    }
    public Map<?, ?> object(String field) {
        if (!(values.get(field) instanceof Map<?, ?> map)) throw new IllegalArgumentException("Object required");
        return map;
    }
    public String digest(String scope) { return JSON.digest(scope, values); }
}
