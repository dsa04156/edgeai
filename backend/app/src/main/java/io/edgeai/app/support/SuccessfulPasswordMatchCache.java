package io.edgeai.app.support;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Memoizes only successful password comparisons, never users, roles or authentication. */
public final class SuccessfulPasswordMatchCache implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier clock;
    private final SecretKeySpec key;
    private final LinkedHashMap<String, Long> successes = new LinkedHashMap<>();

    public SuccessfulPasswordMatchCache(PasswordEncoder delegate) {
        this(delegate, 64, Duration.ofSeconds(30).toNanos(), System::nanoTime);
    }

    SuccessfulPasswordMatchCache(PasswordEncoder delegate, int capacity, long ttlNanos, LongSupplier clock) {
        if (capacity < 1 || ttlNanos < 1) throw new IllegalArgumentException("Positive cache bounds required");
        this.delegate = delegate;
        this.capacity = capacity;
        this.ttlNanos = ttlNanos;
        this.clock = clock;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        this.key = new SecretKeySpec(secret, "HmacSHA256");
        java.util.Arrays.fill(secret, (byte) 0);
    }

    @Override public String encode(CharSequence rawPassword) { return delegate.encode(rawPassword); }
    @Override public boolean upgradeEncoding(String encodedPassword) { return delegate.upgradeEncoding(encodedPassword); }

    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) return delegate.matches(rawPassword, encodedPassword);
        // Snapshot mutable CharSequence so the fingerprint and delegate compare exactly the same input.
        String raw = rawPassword.toString();
        String fingerprint = fingerprint(raw, encodedPassword);
        synchronized (successes) {
            Long verifiedAt = successes.get(fingerprint);
            if (verifiedAt != null && clock.getAsLong() - verifiedAt < ttlNanos) return true;
            successes.remove(fingerprint);
        }
        if (!delegate.matches(raw, encodedPassword)) return false;
        synchronized (successes) {
            long now = clock.getAsLong();
            successes.entrySet().removeIf(entry -> now - entry.getValue() >= ttlNanos);
            successes.put(fingerprint, now);
            while (successes.size() > capacity) successes.remove(successes.keySet().iterator().next());
        }
        return true;
    }

    private String fingerprint(String raw, String encoded) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            update(mac, encoded);
            update(mac, raw);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password match fingerprint unavailable", error);
        }
    }

    private static void update(Mac mac, String value) {
        // Length-framed UTF-16 preserves every Java character, including unpaired surrogates.
        for (int shift = 24; shift >= 0; shift -= 8) mac.update((byte) (value.length() >>> shift));
        for (int i = 0; i < value.length(); i++) {
            mac.update((byte) (value.charAt(i) >>> 8));
            mac.update((byte) value.charAt(i));
        }
    }
}
