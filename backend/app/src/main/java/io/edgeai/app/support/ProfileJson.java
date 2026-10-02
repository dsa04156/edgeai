package io.edgeai.app.support;

import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import io.edgeai.domain.profile.ProfileIdentity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ProfileJson {
    static final int MAX_BYTES = 65536;
    private final JsonMapper mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Parsed parse(ProfileIdentity.Kind kind, String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ProfilePayloadTooLargeException();
        Object value;
        try { value = mapper.readValue(body, Object.class); }
        catch (RuntimeException e) { throw new IllegalArgumentException("body must be valid JSON without duplicate properties"); }
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("key", "version", "spec")))
            throw new IllegalArgumentException("body requires exactly key, version and spec");
        if (!(map.get("key") instanceof String key) || !(map.get("version") instanceof String version))
            throw new IllegalArgumentException("key and version must be strings");
        var identity = new ProfileIdentity(kind, key, version);
        if (!(map.get("spec") instanceof Map<?, ?> spec) || spec.isEmpty())
            throw new IllegalArgumentException("spec must be a nonempty JSON object");
        String canonical = canonical(spec, 0);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new ProfilePayloadTooLargeException();
        return new Parsed(identity, canonical, digest(kind, canonical));
    }

    public Object decode(String json) { return mapper.readValue(json, Object.class); }

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

    private String digest(ProfileIdentity.Kind kind, String spec) {
        try {
            byte[] bytes = ("edgeai-profile-v1\n" + kind.name() + "\n" + spec).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public record Parsed(ProfileIdentity identity, String spec, String digest) {}
}
