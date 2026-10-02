package io.edgeai.app.support;

import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded, lossless JSON parsing and deterministic content identity shared by API inputs. */
public final class JsonDocuments {
    private final JsonMapper mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Object parse(String body, int maxBytes) {
        if (body.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new ProfilePayloadTooLargeException();
        Object value;
        try { value = mapper.readValue(body, Object.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid JSON"); }
        return value;
    }
    public Object decode(String json) { return mapper.readValue(json, Object.class); }
    public String canonical(Object value) { return canonical(value, 0); }
    public String boundedCanonical(Object value, int maxBytes) {
        String result = canonical(value);
        if (result.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new ProfilePayloadTooLargeException();
        return result;
    }
    public String digest(String scope, Object value) {
        try {
            byte[] bytes = (scope + "\n" + canonical(value)).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String canonical(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("spec exceeds maximum JSON depth 32");
        if (value == null) return "null";
        if (value instanceof String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == 0) throw new IllegalArgumentException("JSON strings cannot contain null characters");
                if (Character.isHighSurrogate(c)) {
                    if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
                        throw new IllegalArgumentException("JSON strings require valid Unicode");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("JSON strings require valid Unicode");
            }
            return mapper.writeValueAsString(s);
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number n) {
            BigDecimal decimal = new BigDecimal(n.toString()).stripTrailingZeros();
            if (Math.abs((long) decimal.scale()) > 1000 || decimal.precision() > 1000)
                throw new IllegalArgumentException("JSON number exceeds precision or scale limit 1000");
            return decimal.signum() == 0 ? "0" : decimal.toPlainString();
        }
        if (value instanceof List<?> list) {
            var result = new StringJoiner(",", "[", "]");
            for (Object item : list) result.add(canonical(item, depth + 1));
            return result.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) -> sorted.put((String) k, v));
            var result = new StringJoiner(",", "{", "}");
            sorted.forEach((k, v) -> result.add(canonical(k, depth + 1) + ":" + canonical(v, depth + 1)));
            return result.toString();
        }
        throw new IllegalArgumentException("unsupported JSON value");
    }

}
